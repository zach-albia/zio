/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.internal

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import scala.annotation.{ switch, tailrec }
import scala.collection.JavaConverters._

import FiberContext.FiberRefLocals
import com.github.ghik.silencer.silent
import stacktracer.ZTraceElement
import tracing.ZIOFn

import zio._

/**
 * An implementation of Fiber that maintains context necessary for evaluation.
 */
private[zio] final class FiberContext[E, A](
  fiberId: Fiber.Id,
  @volatile var parentFiber: FiberContext[_, _],
  platform: Platform,
  startEnv: AnyRef,
  startExec: Executor,
  startIStatus: InterruptStatus,
  startDStatus: Boolean,
  parentTrace: Option[ZTrace],
  initialTracingStatus: Boolean,
  val fiberRefLocals: FiberRefLocals
) extends Fiber[E, A] { self =>

  import FiberContext._
  import FiberState._

  // Accessed from multiple threads:
  private val state = new AtomicReference[FiberState[E, A]](FiberState.initial)

  @volatile
  private[this] var asyncEpoch: Long = 0L

  private[this] val traceExec: Boolean =
    PlatformConstants.tracingSupported && platform.tracing.tracingConfig.traceExecution

  private[this] val traceStack: Boolean =
    PlatformConstants.tracingSupported && platform.tracing.tracingConfig.traceStack

  private[this] val traceEffects: Boolean =
    traceExec && platform.tracing.tracingConfig.traceEffectOpsInExecution

  private[this] val stack           = Stack[Any => IO[Any, Any]]()
  private[this] val environments    = Stack[AnyRef](startEnv)
  private[this] val executors       = Stack[Executor](startExec)
  private[this] val interruptStatus = StackBool(startIStatus.toBoolean)
  private[zio] val _children        = Platform.newConcurrentSet[FiberContext[Any, Any]]()
  private[this] val daemonStatus    = StackBool(startDStatus)

  private[this] val tracingStatus =
    if (traceExec || traceStack) StackBool()
    else null

  private[this] val execTrace =
    if (traceExec) SingleThreadedRingBuffer[ZTraceElement](platform.tracing.tracingConfig.executionTraceLength)
    else null

  private[this] val stackTrace =
    if (traceStack) SingleThreadedRingBuffer[ZTraceElement](platform.tracing.tracingConfig.stackTraceLength)
    else null

  private[this] val tracer = platform.tracing.tracer

  @noinline
  private[this] def inTracingRegion: Boolean =
    if (tracingStatus ne null) tracingStatus.peekOrElse(initialTracingStatus) else false

  @noinline
  private[this] def unwrap(lambda: AnyRef): AnyRef =
    // This is a huge hotspot, hiding loop under
    // the match allows a faster happy path
    lambda match {
      case fn: ZIOFn =>
        var unwrapped = fn.underlying
        while (unwrapped.isInstanceOf[ZIOFn]) {
          unwrapped = unwrapped.asInstanceOf[ZIOFn].underlying
        }
        unwrapped
      case _ => lambda
    }

  @noinline
  private[this] def traceLocation(lambda: AnyRef): ZTraceElement =
    tracer.traceLocation(unwrap(lambda))

  @noinline
  private[this] def addTrace(lambda: AnyRef): Unit =
    execTrace.put(traceLocation(lambda))

  @noinline private[this] def pushContinuation(k: Any => IO[Any, Any]): Unit = {
    if (traceStack && inTracingRegion) stackTrace.put(traceLocation(k))
    stack.push(k)
  }

  private[this] def popStackTrace(): Unit =
    stackTrace.dropLast()

  private[this] def captureTrace(lastStack: ZTraceElement): ZTrace = {
    val exec = if (execTrace ne null) execTrace.toReversedList else Nil
    val stack = {
      val stack0 = if (stackTrace ne null) stackTrace.toReversedList else Nil
      if (lastStack ne null) lastStack :: stack0 else stack0
    }
    ZTrace(fiberId, exec, stack, parentTrace)
  }

  private[this] def cutAncestryTrace(trace: ZTrace): ZTrace = {
    val maxExecLength  = platform.tracing.tracingConfig.ancestorExecutionTraceLength
    val maxStackLength = platform.tracing.tracingConfig.ancestorStackTraceLength
    val maxAncestors   = platform.tracing.tracingConfig.ancestryLength - 1

    val truncatedParentTrace = ZTrace.truncatedParentTrace(trace, maxAncestors)

    ZTrace(
      executionTrace = trace.executionTrace.take(maxExecLength),
      stackTrace = trace.stackTrace.take(maxStackLength),
      parentTrace = truncatedParentTrace,
      fiberId = trace.fiberId
    )
  }

  private[zio] def runAsync(k: Callback[E, A]): Unit =
    register0(xx => k(Exit.flatten(xx))) match {
      case null =>
      case v    => k(v)
    }

  private[this] object InterruptExit extends Function[Any, IO[E, Any]] {
    def apply(v: Any): IO[E, Any] =
      if (isInterruptible()) {
        interruptStatus.popDrop(())

        ZIO.succeed(v)
      } else {
        ZIO.effectTotal { interruptStatus.popDrop(v) }
      }
  }

  private[this] object TracingRegionExit extends Function[Any, IO[E, Any]] {
    def apply(v: Any): IO[E, Any] = {
      // don't use effectTotal to avoid TracingRegionExit appearing in execution trace twice with traceEffects=true
      tracingStatus.popDrop(())

      ZIO.succeed(v)
    }
  }

  /**
   * Unwinds the stack, looking for the first error handler, and exiting
   * interruptible / uninterruptible regions.
   */
  private[this] def unwindStack(): Unit = {
    var unwinding = true

    // Unwind the stack, looking for an error handler:
    while (unwinding && !stack.isEmpty) {
      stack.pop() match {
        case InterruptExit =>
          // do not remove InterruptExit from stack trace as it was not added
          interruptStatus.popDrop(())

        case TracingRegionExit =>
          // do not remove TracingRegionExit from stack trace as it was not added
          tracingStatus.popDrop(())

        case fold: ZIO.Fold[_, _, _, _, _] if !shouldInterrupt() =>
          // Push error handler back onto the stack and halt iteration:
          val k = fold.failure.asInstanceOf[Any => ZIO[Any, Any, Any]]

          if (traceStack && inTracingRegion) popStackTrace()
          pushContinuation(k)

          unwinding = false

        case _ =>
          if (traceStack && inTracingRegion) popStackTrace()
      }
    }
  }

  private[this] def executor: Executor = executors.peekOrElse(platform.executor)

  @inline private[this] def raceWithImpl[R, EL, ER, E, A, B, C](
    race: ZIO.RaceWith[R, EL, ER, E, A, B, C]
  ): ZIO[R, E, C] = {
    @inline def complete[E0, E1, A, B](
      winner: Fiber[E0, A],
      loser: Fiber[E1, B],
      cont: (Exit[E0, A], Fiber[E1, B]) => ZIO[R, E, C],
      winnerExit: Exit[E0, A],
      ab: AtomicBoolean,
      cb: ZIO[R, E, C] => Unit
    ): Unit =
      if (ab.compareAndSet(true, false)) {
        winnerExit match {
          case exit: Exit.Success[_] =>
            cb(winner.inheritRefs.flatMap(_ => cont(exit, loser)))
          case exit: Exit.Failure[_] =>
            cb(cont(exit, loser))
        }
      }

    val raceIndicator = new AtomicBoolean(true)

    val left  = fork[EL, A](race.left.interruptible.asInstanceOf[IO[EL, A]])
    val right = fork[ER, B](race.right.interruptible.asInstanceOf[IO[ER, B]])

    ZIO
      .effectAsync[R, E, C] { cb =>
        val leftRegister = left.register0 {
          case exit0: Exit.Success[Exit[EL, A]] =>
            complete[EL, ER, A, B](left, right, race.leftWins, exit0.value, raceIndicator, cb)
          case exit: Exit.Failure[_] => complete(left, right, race.leftWins, exit, raceIndicator, cb)
        }

        if (leftRegister ne null)
          complete(left, right, race.leftWins, leftRegister, raceIndicator, cb)
        else {
          val rightRegister = right.register0 {
            case exit0: Exit.Success[Exit[_, _]] =>
              complete(right, left, race.rightWins, exit0.value, raceIndicator, cb)
            case exit: Exit.Failure[_] => complete(right, left, race.rightWins, exit, raceIndicator, cb)
          }

          if (rightRegister ne null)
            complete(right, left, race.rightWins, rightRegister, raceIndicator, cb)
        }
      }
  }

  /**
   * The main interpreter loop for `IO` actions. For purely synchronous actions,
   * this will run to completion unless required to yield to other fibers.
   * For mixed actions, the loop will proceed no further than the first
   * asynchronous boundary.
   *
   * @param io0 The `IO` to evaluate on the fiber.
   */
  def evaluateNow(io0: IO[E, Any]): Unit =
    try {
      // Do NOT accidentally capture `curZio` in a closure, or Scala will wrap
      // it in `ObjectRef` and performance will plummet.
      var curZio: IO[E, Any] = io0

      // Put the stack reference on the stack:
      val stack = this.stack

      // Put the maximum operation count on the stack for fast access:
      val maxopcount = executor.yieldOpCount

      // Store the trace of the immediate future flatMap during evaluation
      // of a 1-hop left bind, to show a stack trace closer to the point of failure
      var fastPathFlatMapContinuationTrace: ZTraceElement = null

      @noinline def fastPathTrace(k: Any => ZIO[Any, E, Any], effect: AnyRef): ZTraceElement =
        if (inTracingRegion) {
          val kTrace = traceLocation(k)

          if (this.traceEffects) addTrace(effect)
          // record the nearest continuation for a better trace in case of failure
          if (this.traceStack) fastPathFlatMapContinuationTrace = kTrace

          kTrace
        } else null

      // Propagate ancestor interruption every once in a while:
      propagateAncestorInterruption()

      Fiber._currentFiber.set(this)

      while (curZio ne null) {
        try {
          var opcount: Int = 0

          while (curZio ne null) {
            val tag = curZio.tag

            // Check to see if the fiber should continue executing or not:
            if (tag == ZIO.Tags.Fail || !shouldInterrupt()) {
              // Fiber does not need to be interrupted, but might need to yield:
              if (opcount == maxopcount) {
                evaluateLater(curZio)
                curZio = null
              } else {
                // Fiber is neither being interrupted nor needs to yield. Execute
                // the next instruction in the program:
                (tag: @switch) match {
                  case ZIO.Tags.FlatMap =>
                    val zio = curZio.asInstanceOf[ZIO.FlatMap[Any, E, Any, Any]]

                    val nested = zio.zio
                    val k      = zio.k

                    // A mini interpreter for the left side of FlatMap that evaluates
                    // anything that is 1-hop away. This eliminates heap usage for the
                    // happy path.
                    (nested.tag: @switch) match {
                      case ZIO.Tags.Succeed =>
                        val io2 = nested.asInstanceOf[ZIO.Succeed[Any]]

                        if (traceExec && inTracingRegion) addTrace(k)

                        curZio = k(io2.value)

                      case ZIO.Tags.EffectTotal =>
                        val io2    = nested.asInstanceOf[ZIO.EffectTotal[Any]]
                        val effect = io2.effect

                        val kTrace = fastPathTrace(k, effect)

                        val value = effect()

                        // delete continuation as it was "popped" after success
                        if (traceStack && (kTrace ne null)) fastPathFlatMapContinuationTrace = null
                        // record continuation in exec as we're just "passing" it
                        if (traceExec && (kTrace ne null)) execTrace.put(kTrace)

                        curZio = k(value)

                      case ZIO.Tags.EffectPartial =>
                        val io2    = nested.asInstanceOf[ZIO.EffectPartial[Any]]
                        val effect = io2.effect

                        val kTrace = fastPathTrace(k, effect)

                        var failIO = null.asInstanceOf[IO[E, Any]]
                        val value = try effect()
                        catch {
                          case t: Throwable if !platform.fatal(t) =>
                            failIO = ZIO.fail(t.asInstanceOf[E])
                        }

                        if (failIO eq null) {
                          // delete continuation as it was "popped" after success
                          if (traceStack && (kTrace ne null)) fastPathFlatMapContinuationTrace = null
                          // record continuation in exec as we're just "passing" it
                          if (traceExec && (kTrace ne null)) execTrace.put(kTrace)

                          curZio = k(value)
                        } else {
                          curZio = failIO
                        }

                      case _ =>
                        // Fallback case. We couldn't evaluate the LHS so we have to
                        // use the stack:
                        curZio = nested
                        pushContinuation(k)
                    }

                  case ZIO.Tags.Succeed =>
                    val zio = curZio.asInstanceOf[ZIO.Succeed[Any]]

                    val value = zio.value

                    curZio = nextInstr(value)

                  case ZIO.Tags.EffectTotal =>
                    val zio    = curZio.asInstanceOf[ZIO.EffectTotal[Any]]
                    val effect = zio.effect

                    if (traceEffects && inTracingRegion) addTrace(effect)

                    curZio = nextInstr(effect())

                  case ZIO.Tags.Fail =>
                    val zio = curZio.asInstanceOf[ZIO.Fail[E, Any]]

                    // Put last trace into a val to avoid `ObjectRef` boxing.
                    val fastPathTrace = fastPathFlatMapContinuationTrace
                    fastPathFlatMapContinuationTrace = null

                    val cause0 = zio.fill(() => captureTrace(fastPathTrace))

                    unwindStack()

                    if (stack.isEmpty) {
                      // Error not caught, stack is empty:
                      val cause = {
                        // Add interruption information into the cause, if it's not already there:
                        val interrupted = state.get.interrupted

                        if (!cause0.contains(interrupted)) cause0 ++ interrupted else cause0
                      }

                      curZio = done(Exit.halt(cause))
                    } else {
                      // Error caught, next continuation on the stack will deal
                      // with it, so we just have to compute it here:
                      curZio = nextInstr(cause0)
                    }

                  case ZIO.Tags.Fold =>
                    val zio = curZio.asInstanceOf[ZIO.Fold[Any, E, Any, Any, Any]]

                    curZio = zio.value
                    pushContinuation(zio)

                  case ZIO.Tags.InterruptStatus =>
                    val zio = curZio.asInstanceOf[ZIO.InterruptStatus[Any, E, Any]]

                    interruptStatus.push(zio.flag.toBoolean)
                    // do not add InterruptExit to the stack trace
                    stack.push(InterruptExit)

                    curZio = zio.zio

                  case ZIO.Tags.CheckInterrupt =>
                    val zio = curZio.asInstanceOf[ZIO.CheckInterrupt[Any, E, Any]]

                    curZio = zio.k(InterruptStatus.fromBoolean(isInterruptible()))

                  case ZIO.Tags.TracingStatus =>
                    val zio = curZio.asInstanceOf[ZIO.TracingStatus[Any, E, Any]]

                    if (tracingStatus ne null) {
                      tracingStatus.push(zio.flag.toBoolean)
                      // do not add TracingRegionExit to the stack trace
                      stack.push(TracingRegionExit)
                    }

                    curZio = zio.zio

                  case ZIO.Tags.CheckTracing =>
                    val zio = curZio.asInstanceOf[ZIO.CheckTracing[Any, E, Any]]

                    curZio = zio.k(TracingStatus.fromBoolean(inTracingRegion))

                  case ZIO.Tags.EffectPartial =>
                    val zio    = curZio.asInstanceOf[ZIO.EffectPartial[Any]]
                    val effect = zio.effect

                    if (traceEffects && inTracingRegion) addTrace(effect)

                    var nextIo = null.asInstanceOf[IO[E, Any]]
                    val value = try effect()
                    catch {
                      case t: Throwable if !platform.fatal(t) =>
                        nextIo = ZIO.fail(t.asInstanceOf[E])
                    }
                    if (nextIo eq null) curZio = nextInstr(value)
                    else curZio = nextIo

                  case ZIO.Tags.EffectAsync =>
                    val zio = curZio.asInstanceOf[ZIO.EffectAsync[Any, E, Any]]

                    val epoch = asyncEpoch
                    asyncEpoch = epoch + 1

                    // Enter suspended state:
                    curZio = enterAsync(epoch, zio.register, zio.blockingOn)

                    if (curZio eq null) {
                      val k = zio.register

                      if (traceEffects && inTracingRegion) addTrace(k)

                      curZio = k(resumeAsync(epoch)) match {
                        case Some(zio) => if (exitAsync(epoch)) zio else null
                        case None      => null
                      }
                    }

                  case ZIO.Tags.Fork =>
                    val zio = curZio.asInstanceOf[ZIO.Fork[Any, Any, Any]]

                    curZio = nextInstr(fork(zio.value))

                  case ZIO.Tags.DaemonStatus =>
                    val zio = curZio.asInstanceOf[ZIO.DaemonStatus[Any, E, Any]]

                    curZio = daemonEnter(zio.flag).bracket_(daemonExit)(zio.zio)

                  case ZIO.Tags.CheckDaemon =>
                    val zio = curZio.asInstanceOf[ZIO.CheckDaemon[Any, E, Any]]

                    curZio = zio.k(DaemonStatus.fromBoolean(daemonStatus.peekOrElse(false)))

                  case ZIO.Tags.Descriptor =>
                    val zio = curZio.asInstanceOf[ZIO.Descriptor[Any, E, Any]]

                    val k = zio.k
                    if (traceExec && inTracingRegion) addTrace(k)

                    curZio = k(getDescriptor())

                  case ZIO.Tags.Lock =>
                    val zio = curZio.asInstanceOf[ZIO.Lock[Any, E, Any]]

                    curZio =
                      if (zio.executor eq executors.peek()) zio.zio
                      else lock(zio.executor).bracket_(unlock, zio.zio)

                  case ZIO.Tags.Yield =>
                    evaluateLater(ZIO.unit)

                    curZio = null

                  case ZIO.Tags.Access =>
                    val zio = curZio.asInstanceOf[ZIO.Read[Any, E, Any]]

                    val k = zio.k
                    if (traceExec && inTracingRegion) addTrace(k)

                    curZio = k(environments.peek())

                  case ZIO.Tags.Provide =>
                    val zio = curZio.asInstanceOf[ZIO.Provide[Any, E, Any]]

                    val push = ZIO.effectTotal(
                      environments
                        .push(zio.r.asInstanceOf[AnyRef])
                    )
                    val pop = ZIO.effectTotal(
                      environments
                        .pop()
                    )
                    curZio = push.bracket_(pop, zio.next)

                  case ZIO.Tags.EffectSuspendPartialWith =>
                    val zio = curZio.asInstanceOf[ZIO.EffectSuspendPartialWith[Any, Any]]

                    val k = zio.f
                    if (traceExec && inTracingRegion) addTrace(k)

                    curZio = try k(platform, fiberId).asInstanceOf[ZIO[Any, E, Any]]
                    catch {
                      case t: Throwable if !platform.fatal(t) => ZIO.fail(t.asInstanceOf[E])
                    }

                  case ZIO.Tags.EffectSuspendTotalWith =>
                    val zio = curZio.asInstanceOf[ZIO.EffectSuspendTotalWith[Any, E, Any]]

                    val k = zio.f
                    if (traceExec && inTracingRegion) addTrace(k)

                    curZio = k(platform, fiberId)

                  case ZIO.Tags.Trace =>
                    curZio = nextInstr(captureTrace(null))

                  case ZIO.Tags.FiberRefNew =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefNew[Any]]

                    val fiberRef = new FiberRef[Any](zio.initialValue, zio.combine)
                    fiberRefLocals.put(fiberRef, zio.initialValue)

                    curZio = nextInstr(fiberRef)

                  case ZIO.Tags.FiberRefModify =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefModify[Any, Any]]

                    val oldValue           = Option(fiberRefLocals.get(zio.fiberRef))
                    val (result, newValue) = zio.f(oldValue.getOrElse(zio.fiberRef.initial))
                    fiberRefLocals.put(zio.fiberRef, newValue)

                    curZio = nextInstr(result)

                  case ZIO.Tags.RaceWith =>
                    val zio = curZio.asInstanceOf[ZIO.RaceWith[Any, Any, Any, Any, Any, Any, Any]]
                    curZio = raceWithImpl(zio).asInstanceOf[IO[E, Any]]
                }
              }
            } else {
              // Fiber was interrupted
              curZio = ZIO.halt(state.get.interrupted)
            }

            opcount = opcount + 1
          }
        } catch {
          case _: InterruptedException =>
            Thread.interrupted()
            curZio = ZIO.interruptAs(Fiber.Id.None)

          // Catastrophic error handler. Any error thrown inside the interpreter is
          // either a bug in the interpreter or a bug in the user's code. Let the
          // fiber die but attempt finalization & report errors.
          case t: Throwable =>
            curZio = if (platform.fatal(t)) platform.reportFatal(t) else ZIO.die(t)
        }
      }
    } finally Fiber._currentFiber.remove()

  private[this] def lock(executor: Executor): UIO[Unit] =
    ZIO.effectTotal { executors.push(executor) } *> ZIO.yieldNow

  private[this] def unlock: UIO[Unit] =
    ZIO.effectTotal { executors.pop() } *> ZIO.yieldNow

  private[this] def getDescriptor(): Fiber.Descriptor =
    Fiber.Descriptor(
      fiberId,
      state.get.status,
      state.get.interrupted.interruptors,
      InterruptStatus.fromBoolean(isInterruptible()),
      children,
      executor
    )

  /**
   * Forks an `IO` with the specified failure handler.
   */
  def fork[E, A](zio: IO[E, A]): FiberContext[E, A] = {
    val isDaemon = daemonStatus.peekOrElse(false)

    val childFiberRefLocals: FiberRefLocals = Platform.newWeakHashMap()
    childFiberRefLocals.putAll(fiberRefLocals)

    val tracingRegion = inTracingRegion
    val ancestry =
      if ((traceExec || traceStack) && tracingRegion) Some(cutAncestryTrace(captureTrace(null)))
      else None

    val childContext = new FiberContext[E, A](
      Fiber.newFiberId(),
      if (isDaemon) null else self,
      platform,
      environments.peek(),
      executors.peek(),
      InterruptStatus.fromBoolean(interruptStatus.peekOrElse(true)),
      daemonStatus.peekOrElse(false),
      ancestry,
      tracingRegion,
      childFiberRefLocals
    )

    if (!isDaemon) {
      self._children.add(childContext.asInstanceOf[FiberContext[Any, Any]])
      childContext.onDone { _ =>
        val _ = {
          val iterator = childContext._children.iterator()
          while (iterator.hasNext()) {
            val child = iterator.next()
            self._children.add(child)
          }
          self._children.remove(childContext)
        }
      }
    } else {
      Fiber.track(childContext)
    }

    platform.executor.submitOrThrow(() => childContext.evaluateNow(zio))

    childContext
  }

  private[this] def evaluateLater(zio: IO[E, Any]): Unit =
    executor.submitOrThrow(() => evaluateNow(zio))

  /**
   * Resumes an asynchronous computation.
   *
   * @param value The value produced by the asynchronous computation.
   */
  private[this] def resumeAsync(epoch: Long): IO[E, Any] => Unit = { zio =>
    if (exitAsync(epoch)) evaluateLater(zio)
  }

  def interruptAs(fiberId: Fiber.Id): UIO[Exit[E, A]] = kill0(fiberId)

  @silent("JavaConverters")
  def children: UIO[Iterable[Fiber[Any, Any]]] = UIO(_children.asScala.toSet)

  def await: UIO[Exit[E, A]] = ZIO.effectAsyncMaybe[Any, Nothing, Exit[E, A]] { k =>
    observe0(x => k(ZIO.done(x)))
  }

  def getRef[A](ref: FiberRef[A]): UIO[A] = UIO {
    val oldValue = Option(fiberRefLocals.get(ref))

    oldValue.asInstanceOf[Option[A]].getOrElse(ref.initial)
  }

  def poll: UIO[Option[Exit[E, A]]] = ZIO.effectTotal(poll0)

  def id: UIO[Option[Fiber.Id]] = UIO(Some(fiberId))

  def inheritRefs: UIO[Unit] = UIO.effectSuspendTotal {
    val locals = fiberRefLocals.asScala: @silent("JavaConverters")
    if (locals.isEmpty) UIO.unit
    else
      UIO.foreach_(locals) {
        case (fiberRef, value) =>
          val ref = fiberRef.asInstanceOf[FiberRef[Any]]
          ref.update(old => ref.combine(old, value))
      }
  }

  def status: UIO[Fiber.Status] = UIO(state.get.status)

  def trace: UIO[Option[ZTrace]] = UIO(Some(captureTrace(null)))

  @tailrec
  private[this] def enterAsync(epoch: Long, register: AnyRef, blockingOn: List[Fiber.Id]): IO[E, Any] = {
    val oldState = state.get

    oldState match {
      case Executing(_, observers, interrupt) =>
        val asyncTrace = if (traceStack && inTracingRegion) traceLocation(register) :: Nil else Nil

        val newState =
          Executing(Fiber.Status.Suspended(isInterruptible(), epoch, blockingOn, asyncTrace), observers, interrupt)

        if (!state.compareAndSet(oldState, newState)) enterAsync(epoch, register, blockingOn)
        else if (shouldInterrupt()) {
          // Fiber interrupted, so go back into running state:
          exitAsync(epoch)
          ZIO.halt(state.get.interrupted)
        } else null

      case _ => throw new RuntimeException(s"Unexpected fiber completion ${fiberId}")
    }
  }

  @tailrec
  private[this] def exitAsync(epoch: Long): Boolean = {
    val oldState = state.get

    oldState match {
      case Executing(Fiber.Status.Suspended(_, oldEpoch, _, _), observers, interrupt) if epoch == oldEpoch =>
        if (!state.compareAndSet(oldState, Executing(Fiber.Status.Running, observers, interrupt))) exitAsync(epoch)
        else true

      case _ => false
    }
  }

  private[this] def daemonEnter(flag: DaemonStatus): UIO[Unit] = ZIO.effectTotal {
    daemonStatus.push(flag.toBoolean)
  }

  private[this] def daemonExit: UIO[Unit] = ZIO.effectTotal {
    val _ = daemonStatus.popOrElse(false)
  }

  private[this] def propagateAncestorInterruption(): Unit = {
    var fiber = self: FiberContext[_, _]

    while (fiber ne null) {
      addInterruptor(fiber.state.get.interrupted)

      fiber = fiber.parentFiber
    }
  }

  @inline
  private def isInterrupted(): Boolean = !state.get.interrupted.isEmpty

  @inline
  private[this] def isInterruptible(): Boolean = interruptStatus.peekOrElse(true)

  @inline
  private[this] def shouldInterrupt(): Boolean =
    isInterrupted() && isInterruptible()

  @tailrec
  private[this] def addInterruptor(cause: Cause[Nothing]): Unit =
    if (!cause.isEmpty) {
      val oldState = state.get

      oldState match {
        case Executing(status, observers, interrupted) =>
          val newInterrupted = if (!interrupted.contains(cause)) interrupted ++ cause else interrupted

          val newState = Executing(status, observers, newInterrupted)

          if (!state.compareAndSet(oldState, newState)) addInterruptor(cause)

        case _ =>
      }
    }

  @inline
  private[this] def nextInstr(value: Any): IO[E, Any] =
    if (!stack.isEmpty) {
      val k = stack.pop()

      if (inTracingRegion) {
        if (traceExec) addTrace(k)
        // do not remove InterruptExit and TracingRegionExit from stack trace as they were not added
        if (traceStack && (k ne InterruptExit) && (k ne TracingRegionExit)) popStackTrace()
      }

      k(value).asInstanceOf[IO[E, Any]]
    } else done(Exit.succeed(value.asInstanceOf[A]))

  @tailrec
  private[this] def done(v: Exit[E, A]): IO[E, Any] = {
    val oldState = state.get

    oldState match {
      case Executing(_, observers: List[Callback[Nothing, Exit[E, A]]], _) => // TODO: Dotty doesn't infer this properly
        if (!state.compareAndSet(oldState, Done(v))) done(v)
        else {
          reportUnhandled(v)
          notifyObservers(v, observers)
          // Disconnect this node from the tree for GC reasons:
          val iterator = _children.iterator()
          while (iterator.hasNext()) {
            val child = iterator.next()

            if (self.parentFiber ne null) child.parentFiber = self.parentFiber
            else Fiber.track(child)
          }
          self.parentFiber = null
          null
        }

      case Done(_) => null // Already done
    }
  }

  private[this] def reportUnhandled(v: Exit[E, A]): Unit = v match {
    case Exit.Failure(cause) => platform.reportFailure(cause)
    case _                   =>
  }

  private[this] def kill0(fiberId: Fiber.Id): UIO[Exit[E, A]] = {
    @tailrec
    def setInterruptedLoop(): Unit = {
      val oldState = state.get

      oldState match {
        case Executing(Fiber.Status.Suspended(true, _, _, _), observers, interrupted) =>
          if (!state.compareAndSet(
                oldState,
                Executing(Fiber.Status.Running, observers, interrupted ++ Cause.interrupt(fiberId))
              )) setInterruptedLoop()
          else evaluateLater(ZIO.interruptAs(fiberId))

        case Executing(status, observers, interrupted) =>
          if (!state.compareAndSet(oldState, Executing(status, observers, interrupted ++ Cause.interrupt(fiberId))))
            setInterruptedLoop()

        case _ =>
      }
    }

    val setInterrupt = UIO(setInterruptedLoop())

    @silent("JavaConverters")
    val interruptChildren =
      UIO.effectSuspendTotal(_children.asScala.foldLeft[UIO[Any]](UIO.unit) {
        case (acc, child) => acc.flatMap(_ => child.interruptAs(fiberId))
      })

    setInterrupt *> await <* interruptChildren
  }

  @tailrec
  private[zio] def onDone(k: Callback[Nothing, Exit[E, A]]): Unit = {
    val oldState = state.get

    oldState match {
      case Executing(status, observers0, interrupt) =>
        val observers = k :: observers0

        if (!state.compareAndSet(oldState, Executing(status, observers, interrupt))) onDone(k)

      case Done(v) => k(Exit.succeed(v))
    }
  }

  private[this] def observe0(
    k: Callback[Nothing, Exit[E, A]]
  ): Option[IO[Nothing, Exit[E, A]]] =
    register0(k) match {
      case null => None
      case x    => Some(ZIO.succeed(x))
    }

  @tailrec
  private def register0(k: Callback[Nothing, Exit[E, A]]): Exit[E, A] = {
    val oldState = state.get

    oldState match {
      case Executing(status, observers0, interrupt) =>
        val observers = k :: observers0

        if (!state.compareAndSet(oldState, Executing(status, observers, interrupt))) register0(k)
        else null

      case Done(v) => v
    }
  }

  private[this] def poll0: Option[Exit[E, A]] =
    state.get match {
      case Done(r) => Some(r)
      case _       => None
    }

  private[this] def notifyObservers(
    v: Exit[E, A],
    observers: List[Callback[Nothing, Exit[E, A]]]
  ): Unit = {
    val result = Exit.succeed(v)

    // For improved fairness, we resume in order of submission:
    observers.reverse.foreach(k => k(result))
  }

}
private[zio] object FiberContext {
  sealed abstract class FiberState[+E, +A] extends Serializable with Product {
    def interrupted: Cause[Nothing]
    def status: Fiber.Status
  }
  object FiberState extends Serializable {
    final case class Executing[E, A](
      status: Fiber.Status,
      observers: List[Callback[Nothing, Exit[E, A]]],
      interrupted: Cause[Nothing]
    ) extends FiberState[E, A]
    final case class Done[E, A](value: Exit[E, A]) extends FiberState[E, A] {
      def interrupted          = Cause.empty
      def status: Fiber.Status = Fiber.Status.Done
    }

    def initial[E, A] = Executing[E, A](Fiber.Status.Running, Nil, Cause.empty)
  }

  type FiberRefLocals = java.util.Map[FiberRef[Any], Any]
}
