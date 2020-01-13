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

package zio.stream

import java.io.{ IOException, InputStream }
import java.{ util => ju }

import com.github.ghik.silencer.silent

import zio._
import zio.clock.Clock
import zio.duration.Duration
import zio.internal.UniqueKey
import zio.stream.ZStream.Pull

/**
 * A `Stream[E, A]` represents an effectful stream that can produce values of
 * type `A`, or potentially fail with a value of type `E`.
 *
 * Streams have a very similar API to Scala collections, making them immediately
 * familiar to most developers. Unlike Scala collections, streams can be used
 * on effectful streams of data, such as HTTP connections, files, and so forth.
 *
 * Streams do not leak resources. This guarantee holds in the presence of early
 * termination (not all of a stream is consumed), failure, or even interruption.
 *
 * Thanks to only first-order types, appropriate variance annotations, and
 * specialized effect type (ZIO), streams feature extremely good type inference
 * and should almost never require specification of any type parameters.
 */
class ZStream[-R, +E, +A] private[stream] (private[stream] val structure: ZStream.Structure[R, E, A])
    extends Serializable { self =>
  import ZStream.GroupBy

  /**
   * `process` is a low-level consumption method, usually used for creating combinators
   * and constructors. For most usage, [[ZStream#fold]], [[ZStream#foreach]] or [[ZStream#run]]
   * should be preferred.
   *
   * The contract for the returned `Pull` is as follows:
   * - It must not be evaluated concurrently from multiple fibers - it is (usually)
   *   not thread-safe;
   * - If an evaluation of the `Pull` is interrupted, it is not safe to
   *   evaluate it again - it is (usually) not interruption-safe;
   * - Once the `Pull` has failed with a `None`, it is not safe
   *   to evaluate it again.
   *
   * The managed `Pull` can be used to read from the stream until it is empty
   * (or possibly forever, if the stream is infinite). The provided `Pull`
   * is valid only inside the scope of the managed resource.
   */
  val process: ZManaged[R, Nothing, Pull[R, E, A]] = structure.process

  /**
   * Concatenates with another stream in strict order
   */
  final def ++[R1 <: R, E1 >: E, A1 >: A](other: => ZStream[R1, E1, A1]): ZStream[R1, E1, A1] =
    concat(other)

  /**
   * Returns a stream that submerges the error case of an `Either` into the `ZStream`. Note that
   * this combinator and [[either]] cancel each other, i.e. `xs.either.absolve == xs` and vice versa.
   */
  final def absolve[R1 <: R, E1, B](
    implicit ev: ZStream[R, E, A] <:< ZStream[R1, E1, Either[E1, B]]
  ): ZStream[R1, E1, B] =
    ZStream.absolve(ev(self))

  /**
   * Applies an aggregator to the stream, which converts one or more elements
   * of type `A` into elements of type `B`.
   */
  def aggregate[R1 <: R, E1 >: E, A1 >: A, B](sink: ZSink[R1, E1, A1, A1, B]): ZStream[R1, E1, B] =
    aggregateManaged(ZManaged.succeed(sink))

  /**
   * Aggregates elements of this stream using the provided sink for as long
   * as the downstream operators on the stream are busy.
   *
   * This operator divides the stream into two asynchronous "islands". Operators upstream
   * of this operator run on one fiber, while downstream operators run on another. Whenever
   * the downstream fiber is busy processing elements, the upstream fiber will feed elements
   * into the sink until it signals completion.
   *
   * Any sink can be used here, but see [[Sink.foldWeightedM]] and [[Sink.foldUntilM]] for
   * sinks that cover the common usecases.
   */
  final def aggregateAsync[R1 <: R, E1 >: E, A1 >: A, B](sink: ZSink[R1, E1, A1, A1, B]): ZStream[R1, E1, B] = {
    /*
     * How this works:
     *
     * One fiber reads from the `self` stream, and is responsible for aggregating the elements
     * using the sink. Another fiber reads the aggregated elements as fast as possible.
     *
     * The two fibers share the sink's state in a Ref protected by a semaphore. The state machine
     * is defined by the `State` type. See the comments on `producer` and `consumer` for more details
     * on the transitions.
     */

    sealed abstract class State
    object State {
      final case class Empty(state: sink.State, notifyConsumer: Promise[Nothing, Unit]) extends State
      final case class Leftovers(state: sink.State, leftovers: Chunk[A1], notifyConsumer: Promise[Nothing, Unit])
          extends State
      final case class BatchMiddle(state: sink.State, notifyProducer: Promise[Nothing, Unit]) extends State
      final case class BatchEnd(state: sink.State, notifyProducer: Promise[Nothing, Unit])    extends State
      final case class Error(e: Cause[E1])                                                    extends State
      case object End                                                                         extends State
    }

    def withStateVar[R, E, A](ref: Ref[State], permits: Semaphore)(f: State => ZIO[R, E, (A, State)]): ZIO[R, E, A] =
      permits.withPermit {
        for {
          s <- ref.get
          a <- f(s).flatMap {
                case (a, s2) => ref.set(s2).as(a)
              }
        } yield a
      }

    def produce(stateVar: Ref[State], permits: Semaphore, a: A1): ZIO[R1, E1, Boolean] =
      withStateVar(stateVar, permits) {
        case State.Empty(state, notifyConsumer) =>
          for {
            notifyProducer <- Promise.make[Nothing, Unit]
            step           <- sink.step(state, a)
            result <- if (sink.cont(step))
                       UIO.succeed(
                         // Notify the consumer so they won't busy wait
                         (notifyConsumer.succeed(()).as(true), State.BatchMiddle(step, notifyProducer))
                       )
                     else
                       UIO.succeed(
                         (
                           // Notify the consumer, wait for them to take the aggregate so we know
                           // it's time to progress, and process the leftovers
                           notifyConsumer.succeed(()) *> notifyProducer.await.as(true),
                           State.BatchEnd(step, notifyProducer)
                         )
                       )
          } yield result

        case State.Leftovers(state, leftovers, notifyConsumer) =>
          UIO.succeed(
            (
              (leftovers ++ Chunk.single(a)).foldWhileM(true)(identity)((_, a) => produce(stateVar, permits, a)),
              State.Empty(state, notifyConsumer)
            )
          )

        case State.BatchMiddle(state, notifyProducer) =>
          // The logic here is the same as the Empty state, except we don't need
          // to notify the consumer on the transition
          for {
            step <- sink.step(state, a)
            result <- if (sink.cont(step))
                       UIO.succeed((UIO.succeed(true), State.BatchMiddle(step, notifyProducer)))
                     else
                       UIO.succeed((notifyProducer.await.as(true), State.BatchEnd(step, notifyProducer)))
          } yield result

        // The producer shouldn't actually see these states, but we still use sane
        // transitions here anyway.
        case s @ State.BatchEnd(_, batchTaken) => UIO.succeed((batchTaken.await.as(true), s))
        case State.Error(e)                    => ZIO.halt(e)
        case State.End                         => UIO.succeed((UIO.succeed(true), State.End))
      }.flatten

    // This function is used in an unfold, so `None` means stop consuming
    def consume(stateVar: Ref[State], permits: Semaphore): ZIO[R1, Option[E1], Chunk[B]] =
      withStateVar(stateVar, permits) {
        // If the state is empty, wait for a notification from the producer
        case s @ State.Empty(_, notify) => UIO.succeed((notify.await.as(Chunk.empty), s))

        case s @ State.Leftovers(_, _, notify) => UIO.succeed((notify.await.as(Chunk.empty), s))

        case State.BatchMiddle(state, notifyProducer) =>
          (for {
            initial        <- sink.initial
            notifyConsumer <- Promise.make[Nothing, Unit]
            extractResult  <- sink.extract(state)
            (b, leftovers) = extractResult
            nextState = if (leftovers.isEmpty) State.Empty(initial, notifyConsumer)
            else State.Leftovers(initial, leftovers, notifyConsumer)
            // Inform the producer that we took the batch, extract the sink and emit the data
          } yield (notifyProducer.succeed(()).as(Chunk.single(b)), nextState)).mapError(Some(_))

        case State.BatchEnd(state, notifyProducer) =>
          (for {
            initial        <- sink.initial
            notifyConsumer <- Promise.make[Nothing, Unit]
            extractResult  <- sink.extract(state)
            (b, leftovers) = extractResult
            nextState = if (leftovers.isEmpty) State.Empty(initial, notifyConsumer)
            else State.Leftovers(initial, leftovers, notifyConsumer)
          } yield (notifyProducer.succeed(()).as(Chunk.single(b)), nextState)).mapError(Some(_))

        case e @ State.Error(cause) => ZIO.succeed((ZIO.halt(cause.map(Some(_))), e))
        case State.End              => ZIO.succeed((ZIO.fail(None), State.End))
      }.flatten

    def drainAndSet(stateVar: Ref[State], permits: Semaphore, s: State): ZIO[R1, E1, Unit] =
      withStateVar(stateVar, permits) {
        // If the state is empty, it's ok to overwrite it. We just need to notify the consumer.
        case State.Empty(_, notifyNext) => UIO.succeed((notifyNext.succeed(()).unit, s))

        // If there are leftovers, we need to process them and re-run.
        case State.Leftovers(state, leftovers, notifyNext) =>
          UIO.succeed(
            (
              leftovers.foldWhileM(true)(identity)((_, a) => produce(stateVar, permits, a)) *>
                drainAndSet(stateVar, permits, s),
              State.Empty(state, notifyNext)
            )
          )

        // For these states (middle/end), we need to wait until the consumer notified us
        // that they took the data. Then rerun.
        case existing @ State.BatchMiddle(_, notifyProducer) =>
          UIO.succeed((notifyProducer.await *> drainAndSet(stateVar, permits, s), existing))
        case existing @ State.BatchEnd(_, notifyProducer) =>
          UIO.succeed((notifyProducer.await *> drainAndSet(stateVar, permits, s), existing))

        // For all other states, we just overwrite.
        case _ => UIO.succeed((UIO.unit, s))
      }.flatten

    ZStream.managed {
      for {
        initSink  <- sink.initial.toManaged_
        initAwait <- Promise.make[Nothing, Unit].toManaged_
        stateVar  <- Ref.make[State](State.Empty(initSink, initAwait)).toManaged_
        permits   <- Semaphore.make(1).toManaged_
        producer <- self
                     .foreachWhileManaged(produce(stateVar, permits, _))
                     .foldCauseM(
                       // At this point, we're done working but we can't just overwrite the
                       // state because the consumer might not have taken the last batch. So
                       // we need to wait for the state to be drained.
                       c => drainAndSet(stateVar, permits, State.Error(c)).toManaged_,
                       _ => drainAndSet(stateVar, permits, State.End).toManaged_
                     )
                     .fork
        bs <- ZStream
               .repeatEffectOption(consume(stateVar, permits))
               .mapConcatChunk(identity)
               .process
               .ensuringFirst(producer.interrupt.fork)
      } yield bs
    }.flatMap(ZStream.repeatEffectOption)
  }

  /**
   * Aggregates elements using the provided sink until it signals completion, or the
   * delay signalled by the schedule has passed.
   *
   * This operator divides the stream into two asynchronous islands. Operators upstream
   * of this operator run on one fiber, while downstream operators run on another. Elements
   * will be aggregated by the sink until the downstream fiber pulls the aggregated value,
   * or until the schedule's delay has passed.
   *
   * Aggregated elements will be fed into the schedule to determine the delays between
   * pulls.
   *
   * @param sink used for the aggregation
   * @param schedule signalling for when to stop the aggregation
   * @tparam R1 environment type
   * @tparam E1 error type
   * @tparam A1 type of the values consumed by the given sink
   * @tparam B type of the value produced by the given sink and consumed by the given schedule
   * @tparam C type of the value produced by the given schedule
   * @return `ZStream[R1 with Clock, E1, Either[C, B]]`
   */
  final def aggregateAsyncWithinEither[R1 <: R, E1 >: E, A1 >: A, B, C](
    sink: ZSink[R1, E1, A1, A1, B],
    schedule: Schedule[R1, Option[B], C]
  ): ZStream[R1, E1, Either[C, B]] = {
    /*
     * How this works:
     *
     * One fiber reads from the `self` stream, and is responsible for aggregating the elements
     * using the sink. Another fiber reads the aggregated elements when the sink has signalled
     * completion or the delay from the schedule has expired. The delay for each iteration of
     * the consumer is derived from the the last aggregate pulled. When the schedule signals
     * completion, its result is also emitted into the stream. Note that the schedule will only
     * advance if the collection was triggered by the schedule and not by the sink.
     *
     * The two fibers share the sink's state in a Ref protected by a semaphore. The state machine
     * is defined by the `State` type. See the comments on `producer` and `consumer` for more details
     * on the transitions.
     */

    sealed abstract class State
    object State {
      final case class Empty(state: sink.State, notifyConsumer: Promise[Nothing, Unit]) extends State
      final case class Leftovers(state: sink.State, leftovers: Chunk[A1], notifyConsumer: Promise[Nothing, Unit])
          extends State
      final case class BatchMiddle(
        state: sink.State,
        notifyProducer: Promise[Nothing, Unit],
        notifyConsumer: Promise[Nothing, Unit]
      ) extends State
      final case class BatchEnd(state: sink.State, notifyProducer: Promise[Nothing, Unit]) extends State
      final case class Error(e: Cause[E1])                                                 extends State
      case object End                                                                      extends State
    }

    def withStateVar[R, E, A](ref: Ref[State], permits: Semaphore)(f: State => ZIO[R, E, (A, State)]): ZIO[R, E, A] =
      permits.withPermit {
        for {
          s <- ref.get
          a <- f(s).flatMap {
                case (a, s2) => ref.set(s2).as(a)
              }
        } yield a
      }

    def produce(out: Ref[State], permits: Semaphore, a: A1): ZIO[R1, E1, Boolean] =
      withStateVar(out, permits) {
        case State.Empty(state, notifyConsumer) =>
          for {
            step           <- sink.step(state, a)
            notifyProducer <- Promise.make[Nothing, Unit]
            result <- if (sink.cont(step))
                       // If the sink signals to continue, we move to BatchMiddle. The existing notifyConsumer
                       // promise is copied along because the consumer is racing it against the schedule's timeout.
                       UIO.succeed(
                         (UIO.succeed(true), State.BatchMiddle(step, notifyProducer, notifyConsumer))
                       )
                     else
                       // If the sink signals to stop, we notify the consumer that we're done and wait for it
                       // to take the data. Then we process the leftovers.
                       UIO.succeed(
                         (
                           notifyConsumer.succeed(()) *> notifyProducer.await.as(true),
                           State.BatchEnd(step, notifyProducer)
                         )
                       )
          } yield result

        case State.Leftovers(state, leftovers, notifyConsumer) =>
          UIO.succeed(
            (
              (leftovers ++ Chunk.single(a)).foldWhileM(true)(identity)((_, a) => produce(out, permits, a)).as(true),
              State.Empty(state, notifyConsumer)
            )
          )

        case State.BatchMiddle(currentState, notifyProducer, notifyConsumer) =>
          for {
            step <- sink.step(currentState, a)
            // Same logic here as in BatchEmpty: when the sink continues, we stay in this state;
            // when the sink stops, we signal the consumer, wait for the data to be taken and
            // process leftovers.
            result <- if (sink.cont(step))
                       UIO.succeed(
                         (UIO.succeed(true), State.BatchMiddle(step, notifyProducer, notifyConsumer))
                       )
                     else
                       UIO.succeed(
                         (
                           notifyConsumer.succeed(()) *> notifyProducer.await.as(true),
                           State.BatchEnd(step, notifyProducer)
                         )
                       )
          } yield result

        // The producer shouldn't actually see these states, but we do whatever is sensible anyway
        case s @ State.BatchEnd(_, notifyProducer) =>
          UIO.succeed(notifyProducer.await.as(true) -> s)

        case s @ State.Error(c) =>
          UIO.succeed(ZIO.halt(c) -> s)

        case State.End =>
          UIO.succeed(UIO.succeed(false) -> State.End)
      }.flatten

    case class UnfoldState(
      lastBatch: Option[B],
      scheduleState: schedule.State,
      nextBatchCompleted: Promise[Nothing, Unit]
    )

    def consume(
      unfoldState: UnfoldState,
      stateVar: Ref[State],
      permits: Semaphore
    ): ZIO[R1, E1, Option[(Chunk[Either[C, B]], UnfoldState)]] = {
      def extract(
        nextState: schedule.State
      ): ZIO[R1, E1, Option[(Chunk[Either[C, B]], UnfoldState)]] =
        withStateVar(stateVar, permits) {
          case s @ State.Empty(_, notifyDone) =>
            // Empty state means the producer hasn't done anything yet, so nothing to do other
            // than restart with the provided promise
            UIO.succeed(
              UIO.succeed(Some(Chunk.empty -> UnfoldState(None, nextState, notifyDone))) -> s
            )

          // Leftovers state has the same meaning as the empty state for us
          case s @ State.Leftovers(_, _, notifyDone) =>
            UIO.succeed(
              UIO.succeed(Some(Chunk.empty -> UnfoldState(None, nextState, notifyDone))) -> s
            )

          case State.BatchMiddle(sinkState, notifyProducer, _) =>
            // The schedule's delay expired before the sink signalled completion. So we extract
            // the sink anyway and empty the state.
            for {
              extractResult      <- sink.extract(sinkState)
              (batch, leftovers) = extractResult
              sinkInitial        <- sink.initial
              notifyConsumer     <- Promise.make[Nothing, Unit]
              s = if (leftovers.isEmpty) State.Empty(sinkInitial, notifyConsumer)
              else State.Leftovers(sinkInitial, leftovers, notifyConsumer)
              action = notifyProducer
                .succeed(())
                .as(
                  Some(
                    Chunk
                      .single(Right(batch)) -> UnfoldState(
                      Some(batch),
                      nextState,
                      notifyConsumer
                    )
                  )
                )
            } yield action -> s

          case State.BatchEnd(sinkState, notifyProducer) =>
            // The sink signalled completion, so we extract it and empty the state.
            for {
              extractResult      <- sink.extract(sinkState)
              (batch, leftovers) = extractResult
              sinkInitial        <- sink.initial
              notifyConsumer     <- Promise.make[Nothing, Unit]
              s = if (leftovers.isEmpty) State.Empty(sinkInitial, notifyConsumer)
              else State.Leftovers(sinkInitial, leftovers, notifyConsumer)
              action = notifyProducer
                .succeed(())
                .as(
                  Some(
                    Chunk
                      .single(Right(batch)) -> UnfoldState(
                      Some(batch),
                      nextState,
                      notifyConsumer
                    )
                  )
                )
            } yield action -> s

          case s @ State.Error(cause) =>
            UIO.succeed(ZIO.halt(cause) -> s)

          case State.End =>
            UIO.succeed(UIO.succeed(None) -> State.End)
        }.flatten

      schedule
        .update(unfoldState.lastBatch, unfoldState.scheduleState)
        .option
        .raceEither(unfoldState.nextBatchCompleted.await)
        .flatMap {
          // When the schedule signals completion, we emit its result into the
          // stream and restart with the schedule's initial state
          case Left(None) =>
            schedule.initial.map(
              init =>
                Some(
                  Chunk.single(Left(schedule.extract(unfoldState.lastBatch, unfoldState.scheduleState))) -> unfoldState
                    .copy(scheduleState = init)
                )
            )
          // When the schedule has completed its wait before the
          // next bath we resume with the next schedule state.
          case Left(Some(nextState)) => extract(nextState)
          // When the next batch has completed we extract and keep the
          // schedule's state.
          case Right(_) => extract(unfoldState.scheduleState)
        }
    }

    def consumerStream[E2 >: E1](out: Ref[State], permits: Semaphore) =
      ZStream.unwrap {
        for {
          scheduleInit <- schedule.initial
          notify <- out.get.flatMap {
                     case State.Empty(_, notifyConsumer)          => UIO.succeed(notifyConsumer)
                     case State.Leftovers(_, _, notifyConsumer)   => UIO.succeed(notifyConsumer)
                     case State.BatchMiddle(_, _, notifyConsumer) => UIO.succeed(notifyConsumer)
                     // If we're at the end of the batch or the end of the stream, we start off with
                     // an already completed promise to skip the schedule's delay.
                     case State.BatchEnd(_, _) | State.End => Promise.make[Nothing, Unit].tap(_.succeed(()))
                     // If we see an error, we don't even start the consumer stream.
                     case State.Error(c) => ZIO.halt(c)
                   }
          stream = ZStream
            .unfoldM(UnfoldState(None, scheduleInit, notify))(consume(_, out, permits))
            .mapConcatChunk(identity)
        } yield stream
      }

    def drainAndSet(stateVar: Ref[State], permits: Semaphore, s: State): ZIO[R1, E1, Unit] =
      withStateVar(stateVar, permits) {
        // It's ok to overwrite an empty state - we just need to notify the consumer
        // so it'll take the data
        case State.Empty(_, notifyNext) => UIO.succeed((notifyNext.succeed(()).unit, s))

        // If there are leftovers, we need to process them and retry
        case State.Leftovers(state, leftovers, notifyNext) =>
          UIO.succeed(
            (
              leftovers.foldWhileM(true)(identity)((_, a) => produce(stateVar, permits, a)) *> drainAndSet(
                stateVar,
                permits,
                s
              ),
              State.Empty(state, notifyNext)
            )
          )

        // For these states, we wait for the consumer to take the data and retry
        case existing @ State.BatchMiddle(_, notifyProducer, notifyConsumer) =>
          UIO.succeed(
            (notifyConsumer.succeed(()) *> notifyProducer.await *> drainAndSet(stateVar, permits, s), existing)
          )
        case existing @ State.BatchEnd(_, notifyProducer) =>
          UIO.succeed((notifyProducer.await *> drainAndSet(stateVar, permits, s), existing))

        // On all other states, we can just overwrite the state
        case _ => UIO.succeed((UIO.unit, s))
      }.flatten

    ZStream.managed {
      for {
        fiberId   <- ZManaged.fiberId
        initSink  <- sink.initial.toManaged_
        initAwait <- Promise.make[Nothing, Unit].toManaged_
        permits   <- Semaphore.make(1).toManaged_
        stateVar  <- Ref.make[State](State.Empty(initSink, initAwait)).toManaged_
        producer <- self
                     .foreachWhileManaged(produce(stateVar, permits, _))
                     .foldCauseM(
                       cause => drainAndSet(stateVar, permits, State.Error(cause)).toManaged_,
                       _ => drainAndSet(stateVar, permits, State.End).toManaged_
                     )
                     .fork
        bs <- consumerStream(stateVar, permits).process
               .ensuringFirst(producer.interruptAs(fiberId).fork)
      } yield bs
    }.flatMap(ZStream.repeatEffectOption)
  }

  /**
   * Uses `aggregateAsyncWithinEither` but only returns the `Right` results.
   *
   * @param sink used for the aggregation
   * @param schedule signalling for when to stop the aggregation
   * @tparam R1 environment type
   * @tparam E1 error type
   * @tparam A1 type of the values consumed by the given sink
   * @tparam B type of the value produced by the given sink and consumed by the given schedule
   * @tparam C type of the value produced by the given schedule
   * @return `ZStream[R1, E1, B]`
   */
  final def aggregateAsyncWithin[R1 <: R, E1 >: E, A1 >: A, B, C](
    sink: ZSink[R1, E1, A1, A1, B],
    schedule: Schedule[R1, Option[B], C]
  ): ZStream[R1, E1, B] = aggregateAsyncWithinEither(sink, schedule).collect {
    case Right(v) => v
  }

  /**
   * Applies a managed aggregator to the stream, converting elements of type `A`
   * into elements of type `B`.
   */
  final def aggregateManaged[R1 <: R, E1 >: E, A1 >: A, B](
    managedSink: ZManaged[R1, E1, ZSink[R1, E1, A1, A1, B]]
  ): ZStream[R1, E1, B] =
    ZStream
      .managed(managedSink)
      .flatMap { sink =>
        import ZStream.internal.AggregateState

        ZStream.managed {
          for {
            as       <- self.process
            stateRef <- Ref.make[AggregateState[sink.State, A1]](AggregateState.Initial(Chunk.empty)).toManaged_
            pull = {
              def go: Pull[R1, E1, B] = stateRef.get.flatMap {
                case AggregateState.Initial(leftovers) =>
                  sink.initial.mapError(Some(_)).flatMap { s =>
                    stateRef.set(AggregateState.Drain(s, leftovers, 0)) *> go
                  }

                case AggregateState.Pull(s, dirty) =>
                  as.foldCauseM(
                    Cause
                      .sequenceCauseOption(_)
                      .fold(stateRef.set(if (dirty) AggregateState.DirtyDone(s) else AggregateState.Done) *> go)(
                        Pull.halt
                      ),
                    sink
                      .step(s, _)
                      .foldCauseM(
                        Pull.halt,
                        s => {
                          val next =
                            if (sink.cont(s)) AggregateState.Pull(s, true) else AggregateState.Extract(s, Chunk.empty)
                          stateRef.set(next) *> go
                        }
                      )
                  )

                case AggregateState.Extract(s, chunk) =>
                  sink
                    .extract(s)
                    .foldCauseM(
                      c => stateRef.set(AggregateState.Initial(chunk)) *> Pull.halt(c), {
                        case (b, leftovers) => stateRef.set(AggregateState.Initial(chunk ++ leftovers)) *> Pull.emit(b)
                      }
                    )

                case AggregateState.Drain(s, leftovers, index) =>
                  if (index < leftovers.length) {
                    sink
                      .step(s, leftovers(index))
                      .foldCauseM(
                        c => stateRef.set(AggregateState.Drain(s, leftovers, index + 1)) *> Pull.halt(c),
                        s => {
                          val next =
                            if (sink.cont(s)) AggregateState.Drain(s, leftovers, index + 1)
                            else AggregateState.Extract(s, leftovers.drop(index + 1))
                          stateRef.set(next) *> go
                        }
                      )
                  } else {
                    val next =
                      if (sink.cont(s)) AggregateState.Pull(s, index != 0) else AggregateState.Extract(s, Chunk.empty)
                    stateRef.set(next) *> go
                  }

                case AggregateState.DirtyDone(s) =>
                  sink
                    .extract(s)
                    .foldCauseM(Pull.halt, {
                      case (b, _) =>
                        stateRef.set(AggregateState.Done) *> Pull.emit(b)
                    })

                case AggregateState.Done =>
                  Pull.end
              }

              go
            }
          } yield pull
        }
      }
      .flatMap(ZStream.repeatEffectOption)

  /**
   * Maps the success values of this stream to the specified constant value.
   */
  final def as[B](b: => B): ZStream[R, E, B] = map(new ZIO.ConstFn(() => b))

  /**
   * Returns a stream whose failure and success channels have been mapped by
   * the specified pair of functions, `f` and `g`.
   */
  final def bimap[E1, A1](f: E => E1, g: A => A1)(implicit ev: CanFail[E]): ZStream[R, E1, A1] =
    mapError(f).map(g)

  /**
   * Fan out the stream, producing a list of streams that have the same elements as this stream.
   * The driver streamer will only ever advance of the maximumLag values before the
   * slowest downstream stream.
   */
  final def broadcast(n: Int, maximumLag: Int): ZManaged[R, Nothing, List[Stream[E, A]]] =
    self
      .broadcastedQueues(n, maximumLag)
      .map(_.map(ZStream.fromQueueWithShutdown(_).unTake))

  /**
   * Fan out the stream, producing a dynamic number of streams that have the same elements as this stream.
   * The driver streamer will only ever advance of the maximumLag values before the
   * slowest downstream stream.
   */
  final def broadcastDynamic(
    maximumLag: Int
  ): ZManaged[R, Nothing, UIO[Stream[E, A]]] =
    distributedWithDynamic[E, A](maximumLag, _ => ZIO.succeed(_ => true), _ => ZIO.unit)
      .map(_.map(_._2))
      .map(_.map(ZStream.fromQueueWithShutdown(_).unTake))

  /**
   * Converts the stream to a managed list of queues. Every value will be replicated to every queue with the
   * slowest queue being allowed to buffer maximumLag elements before the driver is backpressured.
   * The downstream queues will be provided with elements in the same order they are returned, so
   * the fastest queue might have seen up to (maximumLag + 1) elements more than the slowest queue if it
   * has a lower index than the slowest queue.
   *
   * Queues can unsubscribe from upstream by shutting down.
   */
  final def broadcastedQueues[E1 >: E, A1 >: A](
    n: Int,
    maximumLag: Int
  ): ZManaged[R, Nothing, List[Queue[Take[E1, A1]]]] = {
    val decider = ZIO.succeed((_: Int) => true)
    distributedWith(n, maximumLag, _ => decider)
  }

  /**
   * Converts the stream to a managed dynamic amount of queues. Every value will be replicated to every queue with the
   * slowest queue being allowed to buffer maximumLag elements before the driver is backpressured.
   * The downstream queues will be provided with elements in the same order they are returned, so
   * the fastest queue might have seen up to (maximumLag + 1) elements more than the slowest queue if it
   * has a lower index than the slowest queue.
   *
   * Queues can unsubscribe from upstream by shutting down.
   */
  final def broadcastedQueuesDynamic[E1 >: E, A1 >: A](
    maximumLag: Int
  ): ZManaged[R, Nothing, UIO[Queue[Take[E1, A1]]]] = {
    val decider = ZIO.succeed((_: UniqueKey) => true)
    distributedWithDynamic[E1, A1](maximumLag, _ => decider, _ => ZIO.unit).map(_.map(_._2))
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by buffering
   * up to `capacity` elements in a queue.
   *
   * @note Prefer capacities that are powers of 2 for better performance.
   */
  final def buffer(capacity: Int): ZStream[R, E, A] =
    ZStream.managed(self.toQueue(capacity)).flatMap { queue =>
      ZStream.fromQueue(queue).unTake
    }

  /**
   * Creates a stream that passes its elements through a queue that drops elements
   * (dropping or sliding). It implements a promise that signals abrupt completion
   * of the stream due to an error or the end of the stream. After the promise has
   * been completed, it continues to offer the current (at the time) contents of the
   * queue, until it has been exhausted, at which point it finishes with the signal
   * from the promise.
   */
  private final def bufferSignal[A1 >: A](queue: Queue[A1]): ZStream[R, E, A1] =
    ZStream.managed {
      for {
        // Signal stream error or end through the result type of the promise
        // for better composability with other effects.
        signal <- Promise.make[Option[E], Nothing].toManaged_
        _ <- self
              .foreachManaged(a => queue.offer(a).unit)
              .foldM(
                e => signal.fail(Some(e)).unit.toManaged_,
                _ => signal.fail(None).unit.toManaged_
              )
              .fork
      } yield (queue, signal)
    }.flatMap {
      case (queue, signal) =>
        def takeFirst(exit: Exit[Nothing, A1], fiber: Fiber[Option[E], Nothing]): Pull[Any, Nothing, A1] =
          fiber.interrupt *> exit.fold(Pull.halt, Pull.emit)

        def finishStream(exit: Exit[Option[E], Nothing], fiber: Fiber[Nothing, A1]): Pull[Any, E, Nothing] =
          fiber.interrupt *> exit.fold(
            c => Cause.sequenceCauseOption(c).fold[Pull[Any, E, Nothing]](Pull.end)(Pull.halt),
            identity
          )

        def signalFirst(exit: Exit[Option[E], Nothing], fiber: Fiber[Nothing, A1]): Pull[Any, E, A1] =
          queue.size.flatMap { size =>
            if (size > 0) fiber.join
            else fiber.poll.flatMap(_.fold[Pull[Any, E, A1]](finishStream(exit, fiber))(_.fold(Pull.halt, Pull.emit)))
          }

        val pull: Pull[Any, E, A1] =
          queue.take.raceWith(signal.await)(takeFirst, signalFirst)

        ZStream.repeatEffectOption(pull)
    }

  /**
   * Allows a faster producer to progress independently of a slower consumer by buffering
   * up to `capacity` elements in a dropping queue.
   *
   * @note Prefer capacities that are powers of 2 for better performance.
   */
  final def bufferDropping(capacity: Int): ZStream[R, E, A] =
    ZStream.managed(ZManaged.make(Queue.dropping[A](capacity))(_.shutdown)).flatMap(bufferSignal)

  /**
   * Allows a faster producer to progress independently of a slower consumer by buffering
   * up to `capacity` elements in a sliding queue.
   *
   * @note Prefer capacities that are powers of 2 for better performance.
   */
  final def bufferSliding(capacity: Int): ZStream[R, E, A] =
    ZStream.managed(ZManaged.make(Queue.sliding[A](capacity))(_.shutdown)).flatMap(bufferSignal)

  /**
   * Allows a faster producer to progress independently of a slower consumer by buffering
   * elements into an unbounded queue.
   */
  final def bufferUnbounded: ZStream[R, E, A] =
    ZStream.managed(self.toQueueUnbounded).flatMap { queue =>
      ZStream.fromQueue(queue).unTake
    }

  /**
   * Switches over to the stream produced by the provided function in case this one
   * fails with a typed error.
   */
  final def catchAll[R1 <: R, E2, A1 >: A](f: E => ZStream[R1, E2, A1])(implicit ev: CanFail[E]): ZStream[R1, E2, A1] =
    self.catchAllCause(_.failureOrCause.fold(f, ZStream.halt))

  /**
   * Switches over to the stream produced by the provided function in case this one
   * fails. Allows recovery from all causes of failure, including interruption of the
   * stream is uninterruptible.
   */
  final def catchAllCause[R1 <: R, E2, A1 >: A](f: Cause[E] => ZStream[R1, E2, A1]): ZStream[R1, E2, A1] = {
    sealed abstract class State
    object State {
      case object NotStarted extends State
      case object Self       extends State
      case object Other      extends State
    }

    ZStream {
      for {
        finalizer <- ZManaged.finalizerRef[R1](_ => UIO.unit)
        selfPull  <- Ref.make[Pull[R, E, A]](Pull.end).toManaged_
        otherPull <- Ref.make[Pull[R1, E2, A1]](Pull.end).toManaged_
        stateRef  <- Ref.make[State](State.NotStarted).toManaged_
        pull = {
          def switch(e: Cause[Option[E]]): Pull[R1, E2, A1] = {
            def next(e: Cause[E]) = ZIO.uninterruptibleMask { restore =>
              for {
                _  <- finalizer.get.flatMap(_.apply(Exit.fail(e)))
                r  <- f(e).process.reserve
                _  <- finalizer.set(r.release)
                as <- restore(r.acquire)
                _  <- otherPull.set(as)
                _  <- stateRef.set(State.Other)
                a  <- as
              } yield a
            }

            Cause.sequenceCauseOption(e) match {
              case None    => Pull.end
              case Some(c) => next(c)
            }
          }

          stateRef.get.flatMap {
            case State.NotStarted =>
              ZIO.uninterruptibleMask { restore =>
                for {
                  r  <- self.process.reserve
                  _  <- finalizer.set(r.release)
                  as <- restore(r.acquire)
                  _  <- selfPull.set(as)
                  _  <- stateRef.set(State.Self)
                  a  <- as
                } yield a
              }.catchAllCause(switch)

            case State.Self =>
              selfPull.get.flatten.catchAllCause(switch)

            case State.Other =>
              otherPull.get.flatten
          }
        }
      } yield pull
    }
  }

  /**
   * Chunks the stream with specified chunkSize
   * @param chunkSize size of the chunk
   */
  def chunkN(chunkSize: Int): ZStreamChunk[R, E, A] =
    ZStreamChunk {
      ZStream {
        self.process.mapM { as =>
          Ref.make[Option[Option[E]]](None).flatMap { stateRef =>
            def loop(acc: Array[A], i: Int): Pull[R, E, Chunk[A]] =
              as.foldM(
                success = { a =>
                  acc(i) = a
                  val i1 = i + 1
                  if (i1 >= chunkSize) Pull.emit(Chunk.fromArray(acc)) else loop(acc, i1)
                },
                failure = { e =>
                  stateRef.set(Some(e)) *> Pull.emit(Chunk.fromArray(acc).take(i))
                }
              )
            def first: Pull[R, E, Chunk[A]] =
              as.foldM(
                success = { a =>
                  if (chunkSize <= 1) {
                    Pull.emit(Chunk.single(a))
                  } else {
                    val acc = Array.ofDim(chunkSize)(Chunk.Tags.fromValue(a))
                    acc(0) = a
                    loop(acc, 1)
                  }
                },
                failure = { e =>
                  stateRef.set(Some(e)) *> ZIO.fail(e)
                }
              )
            IO.succeed {
              stateRef.get.flatMap {
                case None    => first
                case Some(e) => ZIO.fail(e)
              }
            }
          }
        }
      }
    }

  /**
   * Performs a filter and map in a single step.
   */
  def collect[B](pf: PartialFunction[A, B]): ZStream[R, E, B] =
    collectM(pf.andThen(ZIO.succeed(_)))

  final def collectM[R1 <: R, E1 >: E, B](pf: PartialFunction[A, ZIO[R1, E1, B]]): ZStream[R1, E1, B] =
    ZStream[R1, E1, B] {
      self.process.map { as =>
        val pfIO: PartialFunction[A, Pull[R1, E1, B]] = pf.andThen(Pull.fromEffect(_))

        def pull: Pull[R1, E1, B] =
          as.flatMap { a =>
            pfIO.applyOrElse(a, (_: A) => pull)
          }

        pull

      }
    }

  /**
   * Transforms all elements of the stream for as long as the specified partial function is defined.
   */
  def collectWhile[B](pf: PartialFunction[A, B]): ZStream[R, E, B] =
    collectWhileM(pf.andThen(ZIO.succeed(_)))

  final def collectWhileM[R1 <: R, E1 >: E, B](pf: PartialFunction[A, ZIO[R1, E1, B]]): ZStream[R1, E1, B] =
    ZStream[R1, E1, B] {
      for {
        as   <- self.process
        done <- Ref.make(false).toManaged_
        pfIO = pf.andThen(Pull.fromEffect(_))
        pull = for {
          alreadyDone <- done.get
          result <- if (alreadyDone) Pull.end
                   else
                     as.flatMap { a =>
                       pfIO.applyOrElse(a, (_: A) => done.set(true) *> Pull.end)
                     }
        } yield result
      } yield pull
    }

  /**
   * Combines this stream and the specified stream by repeatedly applying the
   * function `f0` to extract an element from the queues and conceptually "offer"
   * it to the destination stream. `f0` can maintain some internal state to control
   * the combining process, with the initial state being specified by `s1`.
   */
  final def combine[R1 <: R, E1 >: E, S1, B, C](that: ZStream[R1, E1, B])(s1: S1)(
    f0: (S1, Pull[R, E, A], Pull[R1, E1, B]) => ZIO[R1, E1, (S1, Take[E1, C])]
  ): ZStream[R1, E1, C] =
    ZStream[R1, E1, C] {
      for {
        left  <- self.process
        right <- that.process
        pull <- ZStream
                 .unfoldM((s1, left, right)) {
                   case (s1, left, right) =>
                     f0(s1, left, right).flatMap {
                       case (s1, take) =>
                         Take.option(UIO.succeed(take)).map(_.map((_, (s1, left, right))))
                     }
                 }
                 .process
      } yield pull
    }

  /**
   * Appends another stream to this stream. The concatenated stream will first emit the
   * elements of this stream, and then emit the elements of the `other` stream.
   */
  final def concat[R1 <: R, E1 >: E, A1 >: A](other: => ZStream[R1, E1, A1]): ZStream[R1, E1, A1] =
    new ZStream(ZStream.Structure.Concat(structure, () => other.structure))

  /**
   * More powerful version of `ZStream#broadcast`. Allows to provide a function that determines what
   * queues should receive which elements. The decide function will receive the indices of the queues
   * in the resulting list.
   */
  final def distributedWith[E1 >: E, A1 >: A](
    n: Int,
    maximumLag: Int,
    decide: A => UIO[Int => Boolean]
  ): ZManaged[R, Nothing, List[Queue[Take[E1, A1]]]] =
    Promise.make[Nothing, A => UIO[UniqueKey => Boolean]].toManaged_.flatMap { prom =>
      distributedWithDynamic[E1, A1](maximumLag, (a: A) => prom.await.flatMap(_(a)), _ => ZIO.unit).flatMap { next =>
        ZIO.collectAll {
          Range(0, n).map(id => next.map { case (key, queue) => ((key -> id), queue) })
        }.flatMap { entries =>
          val (mappings, queues) = entries.foldRight((Map.empty[UniqueKey, Int], List.empty[Queue[Take[E1, A1]]])) {
            case ((mapping, queue), (mappings, queues)) =>
              (mappings + mapping, queue :: queues)
          }
          prom.succeed((a: A) => decide(a).map(f => (key: UniqueKey) => f(mappings(key)))).as(queues)
        }.toManaged_
      }
    }

  /**
   * More powerful version of `ZStream#distributedWith`. This returns a function that will produce
   * new queues and corresponding indices.
   * You can also provide a function that will be executed after the final events are enqueued in all queues.
   * Shutdown of the queues is handled by the driver.
   * Downstream users can also shutdown queues manually. In this case the driver will
   * continue but no longer backpressure on them.
   */
  final def distributedWithDynamic[E1 >: E, A1 >: A](
    maximumLag: Int,
    decide: A => UIO[UniqueKey => Boolean],
    done: Take[E, Nothing] => UIO[Any] = (_: Any) => UIO.unit
  ): ZManaged[R, Nothing, UIO[(UniqueKey, Queue[Take[E1, A1]])]] =
    for {
      queuesRef <- Ref
                    .make[Map[UniqueKey, Queue[Take[E1, A1]]]](Map())
                    .toManaged(_.get.flatMap(qs => ZIO.foreach(qs.values)(_.shutdown)))
      add <- {
        val offer = (a: A) =>
          for {
            shouldProcess <- decide(a)
            queues        <- queuesRef.get
            _ <- ZIO
                  .foldLeft(queues)(List[UniqueKey]()) {
                    case (acc, (id, queue)) =>
                      if (shouldProcess(id)) {
                        queue
                          .offer(Take.Value(a))
                          .foldCauseM(
                            {
                              // we ignore all downstream queues that were shut down and remove them later
                              case c if c.interrupted => ZIO.succeed(id :: acc)
                              case c                  => ZIO.halt(c)
                            },
                            _ => ZIO.succeed(acc)
                          )
                      } else ZIO.succeed(acc)
                  }
                  .flatMap(ids => if (ids.nonEmpty) queuesRef.update(_ -- ids) else ZIO.unit)
          } yield ()

        for {
          queuesLock <- Semaphore.make(1).toManaged_
          newQueue <- Ref
                       .make[UIO[(UniqueKey, Queue[Take[E1, A1]])]] {
                         for {
                           queue <- Queue.bounded[Take[E1, A1]](maximumLag)
                           id    = UniqueKey()
                           _     <- queuesRef.update(_ + (id -> queue))
                         } yield (id, queue)
                       }
                       .toManaged_
          finalize = (endTake: Take[E, Nothing]) =>
            // we need to make sure that no queues are currently being added
            queuesLock.withPermit {
              for {
                // all newly created queues should end immediately
                _ <- newQueue.set {
                      for {
                        queue <- Queue.bounded[Take[E1, A1]](1)
                        _     <- queue.offer(endTake)
                        id    = UniqueKey()
                        _     <- queuesRef.update(_ + (id -> queue))
                      } yield (id, queue)
                    }
                queues <- queuesRef.get.map(_.values)
                _ <- ZIO.foreach(queues) { queue =>
                      queue.offer(endTake).catchSomeCause {
                        case c if c.interrupted => ZIO.unit
                      }
                    }
                _ <- done(endTake)
              } yield ()
            }
          _ <- self
                .foreachManaged(offer)
                .foldCauseM(
                  cause => finalize(Take.Fail(cause)).toManaged_,
                  _ => finalize(Take.End).toManaged_
                )
                .fork
        } yield queuesLock.withPermit(newQueue.get.flatten)
      }
    } yield add

  /**
   * Converts this stream to a stream that executes its effects but emits no
   * elements. Useful for sequencing effects using streams:
   *
   * {{{
   * (Stream(1, 2, 3).tap(i => ZIO(println(i))) ++
   *   Stream.lift(ZIO(println("Done!"))).drain ++
   *   Stream(4, 5, 6).tap(i => ZIO(println(i)))).run(Sink.drain)
   * }}}
   */
  final def drain: ZStream[R, E, Nothing] =
    ZStream(self.process.map(_.forever))

  /**
   * Drains the provided stream in the background for as long as this stream is running.
   * If this stream ends before `other`, `other` will be interrupted. If `other` fails,
   * this stream will fail with that error.
   */
  final def drainFork[R1 <: R, E1 >: E](other: ZStream[R1, E1, Any]): ZStream[R1, E1, A] =
    ZStream.fromEffect(Promise.make[E1, Nothing]).flatMap { bgDied =>
      ZStream
        .managed(other.foreachManaged(_ => ZIO.unit).catchAllCause(bgDied.halt(_).toManaged_).fork) *>
        self.interruptWhen(bgDied)
    }

  /**
   * Drops the specified number of elements from this stream.
   */
  final def drop(n: Long): ZStream[R, E, A] =
    self.zipWithIndex.filter(_._2 > n - 1).map(_._1)

  /**
   * Drops all elements of the stream until the specified predicate evaluates
   * to `true`.
   */
  final def dropUntil(pred: A => Boolean): ZStream[R, E, A] =
    dropWhile(!pred(_)).drop(1)

  /**
   * Drops all elements of the stream for as long as the specified predicate
   * evaluates to `true`.
   */
  def dropWhile(pred: A => Boolean): ZStream[R, E, A] =
    ZStream {
      for {
        as              <- self.process
        keepDroppingRef <- Ref.make(true).toManaged_
        pull = {
          def go: Pull[R, E, A] =
            as.flatMap { a =>
              keepDroppingRef.get.flatMap { keepDropping =>
                if (!keepDropping) Pull.emit(a)
                else if (!pred(a)) keepDroppingRef.set(false) *> Pull.emit(a)
                else go
              }
            }

          go
        }
      } yield pull
    }

  /**
   * Returns a stream whose failures and successes have been lifted into an
   * `Either`. The resulting stream cannot fail, because the failures have
   * been exposed as part of the `Either` success case.
   *
   * @note the stream will end as soon as the first error occurs.
   */
  final def either(implicit ev: CanFail[E]): ZStream[R, Nothing, Either[E, A]] =
    self.map(Right(_)).catchAll(e => ZStream(Left(e)))

  /**
   * Executes the provided finalizer after this stream's finalizers run.
   */
  final def ensuring[R1 <: R](fin: ZIO[R1, Nothing, Any]): ZStream[R1, E, A] =
    ZStream(self.process.ensuring(fin))

  /**
   * Executes the provided finalizer before this stream's finalizers run.
   */
  final def ensuringFirst[R1 <: R](fin: ZIO[R1, Nothing, Any]): ZStream[R1, E, A] =
    ZStream(self.process.ensuringFirst(fin))

  /**
   * Filters this stream by the specified predicate, retaining all elements for
   * which the predicate evaluates to true.
   */
  def filter(pred: A => Boolean): ZStream[R, E, A] =
    ZStream {
      self.process.map { as =>
        def pull: Pull[R, E, A] = as.flatMap { a =>
          if (pred(a)) Pull.emit(a)
          else pull
        }

        pull
      }
    }

  /**
   * Filters this stream by the specified effectful predicate, retaining all elements for
   * which the predicate evaluates to true.
   */
  final def filterM[R1 <: R, E1 >: E](pred: A => ZIO[R1, E1, Boolean]): ZStream[R1, E1, A] =
    ZStream {
      self.process.map { as =>
        def pull: Pull[R1, E1, A] =
          as.flatMap { a =>
            pred(a).mapError(Some(_)).flatMap {
              if (_) Pull.emit(a)
              else pull
            }
          }

        pull
      }
    }

  /**
   * Filters this stream by the specified predicate, removing all elements for
   * which the predicate evaluates to true.
   */
  final def filterNot(pred: A => Boolean): ZStream[R, E, A] = filter(a => !pred(a))

  /**
   * Emits elements of this stream with a fixed delay in between, regardless of how long it
   * takes to produce a value.
   */
  final def fixed[R1 <: R, E1 >: E, A1 >: A](duration: Duration): ZStream[R1 with Clock, E1, A1] =
    scheduleElementsEither(Schedule.spaced(duration) >>> Schedule.stop).collect {
      case Right(x) => x
    }

  /**
   * Returns a stream made of the concatenation in strict order of all the streams
   * produced by passing each element of this stream to `f0`
   */
  final def flatMap[R1 <: R, E1 >: E, B](f0: A => ZStream[R1, E1, B]): ZStream[R1, E1, B] = {
    def go(
      as: Pull[R1, E1, A],
      finalizer: Ref[Exit[_, _] => URIO[R1, _]],
      currPull: Ref[Pull[R1, E1, B]]
    ): ZIO[R1, Option[E1], B] = {
      val pullOuter = ZIO.uninterruptibleMask { restore =>
        restore(as).flatMap { a =>
          (for {
            reservation <- f0(a).process.reserve
            bs          <- restore(reservation.acquire)
            _           <- finalizer.set(reservation.release)
            _           <- currPull.set(bs)
          } yield ())
        }
      }

      currPull.get.flatten.catchAll {
        case e @ Some(e1) =>
          (finalizer.get.flatMap(_(Exit.fail(e1))) *> finalizer.set(_ => UIO.unit)).uninterruptible *> ZIO.fail(
            e
          )
        case None =>
          (finalizer.get.flatMap(_(Exit.succeed(()))) *>
            finalizer.set(_ => UIO.unit)).uninterruptible *>
            pullOuter *>
            go(as, finalizer, currPull)
      }
    }

    ZStream[R1, E1, B] {
      for {
        currPull  <- Ref.make[Pull[R1, E1, B]](Pull.end).toManaged_
        as        <- self.process
        finalizer <- ZManaged.finalizerRef[R1](_ => UIO.unit)
      } yield go(as, finalizer, currPull)
    }
  }

  /**
   * Maps each element of this stream to another stream and returns the
   * non-deterministic merge of those streams, executing up to `n` inner streams
   * concurrently. Up to `outputBuffer` elements of the produced streams may be
   * buffered in memory by this operator.
   */
  final def flatMapPar[R1 <: R, E1 >: E, B](n: Int, outputBuffer: Int = 16)(
    f: A => ZStream[R1, E1, B]
  ): ZStream[R1, E1, B] =
    ZStream[R1, E1, B] {
      for {
        out          <- Queue.bounded[Pull[R1, E1, B]](outputBuffer).toManaged(_.shutdown)
        permits      <- Semaphore.make(n.toLong).toManaged_
        innerFailure <- Promise.make[Cause[E1], Nothing].toManaged_

        // - The driver stream forks an inner fiber for each stream created
        //   by f, with an upper bound of n concurrent fibers, enforced by the semaphore.
        //   - On completion, the driver stream tries to acquire all permits to verify
        //     that all inner fibers have finished.
        //     - If one of them failed (signalled by a promise), all other fibers are interrupted
        //     - If they all succeeded, Take.End is enqueued
        //   - On error, the driver stream interrupts all inner fibers and emits a
        //     Take.Fail value
        //   - Interruption is handled by running the finalizers which take care of cleanup
        // - Inner fibers enqueue Take values from their streams to the output queue
        //   - On error, an inner fiber enqueues a Take.Fail value and signals its failure
        //     with a promise. The driver will pick that up and interrupt all other fibers.
        //   - On interruption, an inner fiber does nothing
        //   - On completion, an inner fiber does nothing
        _ <- self.foreachManaged { a =>
              for {
                latch <- Promise.make[Nothing, Unit]
                innerStream = Stream
                  .managed(permits.withPermitManaged)
                  .tap(_ => latch.succeed(()))
                  .flatMap(_ => f(a))
                  .foreach(b => out.offer(Pull.emit(b)).unit)
                  .foldCauseM(
                    cause => out.offer(Pull.halt(cause)) *> innerFailure.fail(cause).unit,
                    _ => ZIO.unit
                  )
                _ <- innerStream.fork
                // Make sure that the current inner stream has actually succeeded in acquiring
                // a permit before continuing. Otherwise we could reach the end of the stream and
                // acquire the permits ourselves before the inners had a chance to start.
                _ <- latch.await
              } yield ()
            }.foldCauseM(
                cause => (ZIO.children.flatMap(Fiber.interruptAll) *> out.offer(Pull.halt(cause))).unit.toManaged_,
                _ =>
                  ZIO.children.flatMap { pendingFibers =>
                    innerFailure.await
                    // Important to use `withPermits` here because the ZManaged#fork below may interrupt
                    // the driver, and we want the permits to be released in that case
                      .raceWith(permits.withPermits(n.toLong)(ZIO.unit))(
                        // One of the inner fibers failed. It already enqueued its failure, so we
                        // interrupt the inner fibers. The finalizer below will make sure
                        // that they actually end.
                        leftDone = (_, permitAcquisition) =>
                          Fiber.interruptAll(pendingFibers) *> permitAcquisition.interrupt.unit,
                        // All fibers completed successfully, so we signal that we're done.
                        rightDone = (_, failureAwait) => out.offer(Pull.end) *> failureAwait.interrupt.unit
                      )

                  }.toManaged_
              )
              .fork
      } yield out.take.flatten
    }

  /**
   * Maps each element of this stream to another stream and returns the non-deterministic merge
   * of those streams, executing up to `n` inner streams concurrently. When a new stream is created
   * from an element of the source stream, the oldest executing stream is cancelled. Up to `bufferSize`
   * elements of the produced streams may be buffered in memory by this operator.
   */
  final def flatMapParSwitch[R1 <: R, E1 >: E, B](n: Int, bufferSize: Int = 16)(
    f: A => ZStream[R1, E1, B]
  ): ZStream[R1, E1, B] =
    ZStream[R1, E1, B] {
      for {
        // Modeled after flatMapPar.
        out          <- Queue.bounded[Pull[R1, E1, B]](bufferSize).toManaged(_.shutdown)
        permits      <- Semaphore.make(n.toLong).toManaged_
        innerFailure <- Promise.make[Cause[E1], Nothing].toManaged_
        cancelers    <- Queue.bounded[Promise[Nothing, Unit]](n).toManaged(_.shutdown)
        _ <- self.foreachManaged { a =>
              for {
                canceler <- Promise.make[Nothing, Unit]
                latch    <- Promise.make[Nothing, Unit]
                size     <- cancelers.size
                _ <- if (size < n) UIO.unit
                    else cancelers.take.flatMap(_.succeed(())).unit
                _ <- cancelers.offer(canceler)
                innerStream = Stream
                  .managed(permits.withPermitManaged)
                  .tap(_ => latch.succeed(()))
                  .flatMap(_ => f(a))
                  .foreach(b => out.offer(Pull.emit(b)).unit)
                  .foldCauseM(
                    cause => out.offer(Pull.halt(cause)) *> innerFailure.fail(cause).unit,
                    _ => UIO.unit
                  )
                _ <- (innerStream race canceler.await).fork
                _ <- latch.await
              } yield ()
            }.foldCauseM(
                cause => (ZIO.children.flatMap(Fiber.interruptAll) *> out.offer(Pull.halt(cause))).unit.toManaged_,
                _ =>
                  ZIO.children.flatMap { pendingFibers =>
                    innerFailure.await
                      .raceWith(permits.withPermits(n.toLong)(UIO.unit))(
                        leftDone = (_, permitAcquisition) =>
                          Fiber.interruptAll(pendingFibers) *> permitAcquisition.interrupt.unit,
                        rightDone = (_, failureAwait) => out.offer(Pull.end) *> failureAwait.interrupt.unit
                      )

                  }.toManaged_
              )
              .fork
      } yield out.take.flatten
    }

  /**
   * Executes a pure fold over the stream of values - reduces all elements in the stream to a value of type `S`.
   */
  final def fold[A1 >: A, S](s: S)(f: (S, A1) => S): ZIO[R, E, S] =
    foldWhileManagedM[R, E, A1, S](s)(_ => true)((s, a) => ZIO.succeed(f(s, a))).use(ZIO.succeed)

  /**
   * Executes an effectful fold over the stream of values.
   */
  final def foldM[R1 <: R, E1 >: E, A1 >: A, S](s: S)(f: (S, A1) => ZIO[R1, E1, S]): ZIO[R1, E1, S] =
    foldWhileManagedM[R1, E1, A1, S](s)(_ => true)(f).use(ZIO.succeed)

  /**
   * Executes a pure fold over the stream of values.
   * Returns a Managed value that represents the scope of the stream.
   */
  final def foldManaged[A1 >: A, S](s: S)(f: (S, A1) => S): ZManaged[R, E, S] =
    foldWhileManagedM[R, E, A1, S](s)(_ => true)((s, a) => ZIO.succeed(f(s, a)))

  /**
   * Executes an effectful fold over the stream of values.
   * Returns a Managed value that represents the scope of the stream.
   */
  final def foldManagedM[R1 <: R, E1 >: E, A1 >: A, S](s: S)(f: (S, A1) => ZIO[R1, E1, S]): ZManaged[R1, E1, S] =
    foldWhileManagedM[R1, E1, A1, S](s)(_ => true)(f)

  /**
   * Reduces the elements in the stream to a value of type `S`.
   * Stops the fold early when the condition is not fulfilled.
   * Example:
   * {{{
   *  Stream(1).forever.foldWhile(0)(_ <= 4)(_ + _) // UIO[Int] == 5
   * }}}
   */
  final def foldWhile[A1 >: A, S](s: S)(cont: S => Boolean)(f: (S, A1) => S): ZIO[R, E, S] =
    foldWhileManagedM[R, E, A1, S](s)(cont)((s, a) => ZIO.succeed(f(s, a))).use(ZIO.succeed)

  /**
   * Executes an effectful fold over the stream of values.
   * Stops the fold early when the condition is not fulfilled.
   * Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // UIO[Int] == 5
   * }}}
   *
   * @param cont function which defines the early termination condition
   */
  final def foldWhileM[R1 <: R, E1 >: E, A1 >: A, S](
    s: S
  )(cont: S => Boolean)(f: (S, A1) => ZIO[R1, E1, S]): ZIO[R1, E1, S] =
    foldWhileManagedM[R1, E1, A1, S](s)(cont)(f).use(ZIO.succeed)

  /**
   * Executes a pure fold over the stream of values.
   * Returns a Managed value that represents the scope of the stream.
   * Stops the fold early when the condition is not fulfilled.
   */
  def foldWhileManaged[A1 >: A, S](s: S)(cont: S => Boolean)(f: (S, A1) => S): ZManaged[R, E, S] =
    foldWhileManagedM[R, E, A1, S](s)(cont)((s, a) => ZIO.succeed(f(s, a)))

  /**
   * Executes an effectful fold over the stream of values.
   * Returns a Managed value that represents the scope of the stream.
   * Stops the fold early when the condition is not fulfilled.
   * Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // Managed[Nothing, Int]
   *     .use(ZIO.succeed)                       // UIO[Int] == 5
   * }}}
   *
   * @param cont function which defines the early termination condition
   */
  final def foldWhileManagedM[R1 <: R, E1 >: E, A1 >: A, S](
    s: S
  )(cont: S => Boolean)(f: (S, A1) => ZIO[R1, E1, S]): ZManaged[R1, E1, S] =
    process.flatMap { is =>
      def loop(s1: S): ZIO[R1, E1, S] =
        if (!cont(s1)) UIO.succeed(s1)
        else
          is.foldM({
            case Some(e) => IO.fail(e)
            case None    => IO.succeed(s1)
          }, a => f(s1, a).flatMap(loop))

      ZManaged.fromEffect(loop(s))
    }

  /**
   * Consumes all elements of the stream, passing them to the specified callback.
   */
  final def foreach[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any]): ZIO[R1, E1, Unit] =
    foreachWhile(f.andThen(_.as(true)))

  /**
   * Like [[ZStream#foreach]], but returns a `ZManaged` so the finalization order
   * can be controlled.
   */
  final def foreachManaged[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any]): ZManaged[R1, E1, Unit] =
    foreachWhileManaged(f.andThen(_.as(true)))

  /**
   * Consumes elements of the stream, passing them to the specified callback,
   * and terminating consumption when the callback returns `false`.
   */
  final def foreachWhile[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean]): ZIO[R1, E1, Unit] =
    foreachWhileManaged(f).use_(ZIO.unit)

  /**
   * Like [[ZStream#foreachWhile]], but returns a `ZManaged` so the finalization order
   * can be controlled.
   */
  final def foreachWhileManaged[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean]): ZManaged[R1, E1, Unit] =
    for {
      as <- self.process
      step = as.flatMap(a => f(a).mapError(Some(_))).flatMap {
        if (_) UIO.unit else IO.fail(None)
      }
      _ <- step.forever.catchAll {
            case Some(e) => IO.fail(e)
            case None    => UIO.unit
          }.toManaged_
    } yield ()

  /**
   * Repeats this stream forever.
   */
  final def forever: ZStream[R, E, A] =
    self ++ forever

  /**
   * More powerful version of [[ZStream.groupByKey]]
   */
  final def groupBy[R1 <: R, E1 >: E, K, V](
    f: A => ZIO[R1, E1, (K, V)],
    buffer: Int = 16
  ): GroupBy[R1, E1, K, V] = {
    val qstream = ZStream.unwrapManaged {
      for {
        decider <- Promise.make[Nothing, (K, V) => UIO[UniqueKey => Boolean]].toManaged_
        out <- Queue
                .bounded[Take[E1, (K, GroupBy.DequeueOnly[Take[E1, V]])]](buffer)
                .toManaged(_.shutdown)
        ref <- Ref.make[Map[K, UniqueKey]](Map()).toManaged_
        add <- self
                .mapM(f)
                .distributedWithDynamic(
                  buffer,
                  (kv: (K, V)) => decider.await.flatMap(_.tupled(kv)),
                  out.offer
                )
        _ <- decider.succeed {
              case (k, _) =>
                ref.get.map(_.get(k)).flatMap {
                  case Some(idx) => ZIO.succeed(_ == idx)
                  case None =>
                    add.flatMap {
                      case (idx, q) =>
                        (ref.update(_ + (k -> idx)) *>
                          out.offer(Take.Value(k -> q.map(_.map(_._2))))).as(_ == idx)
                    }
                }
            }.toManaged_
      } yield ZStream.fromQueueWithShutdown(out).unTake
    }
    new ZStream.GroupBy(qstream, buffer)
  }

  /**
   * Partition a stream using a function and process each stream individually.
   * This returns a data structure that can be used
   * to further filter down which groups shall be processed.
   *
   * After calling apply on the GroupBy object, the remaining groups will be processed
   * in parallel and the resulting streams merged in a nondeterministic fashion.
   *
   * Up to `buffer` elements may be buffered in any group stream before the producer
   * is backpressured. Take care to consume from all streams in order
   * to prevent deadlocks.
   *
   * Example:
   * Collect the first 2 words for every starting letter
   * from a stream of words.
   * {{{
   * ZStream.fromIterable(List("hello", "world", "hi", "holla"))
   *  .groupByKey(_.head) { case (k, s) => s.take(2).map((k, _)) }
   *  .runCollect
   *  .map(_ == List(('h', "hello"), ('h', "hi"), ('w', "world"))
   * }}}
   */
  final def groupByKey[K](
    f: A => K,
    buffer: Int = 16
  ): GroupBy[R, E, K, A] =
    self.groupBy(a => ZIO.succeed((f(a), a)), buffer)

  /**
   * Partitions the stream with specified chunkSize
   * @param chunkSize size of the chunk
   */
  def grouped(chunkSize: Long): ZStream[R, E, List[A]] =
    aggregate(ZSink.collectAllN[A](chunkSize))

  /**
   * Halts the evaluation of this stream when the provided promise resolves.
   *
   * If the promise completes with a failure, the stream will emit that failure.
   */
  final def haltWhen[E1 >: E](p: Promise[E1, _]): ZStream[R, E1, A] =
    ZStream {
      for {
        as   <- self.process
        done <- Ref.make(false).toManaged_
        pull = done.get flatMap {
          if (_) Pull.end
          else
            p.poll.flatMap {
              case None    => as
              case Some(v) => done.set(true) *> v.mapError(Some(_)) *> Pull.end
            }
        }
      } yield pull
    }

  /**
   * Interleaves this stream and the specified stream deterministically by
   * alternating pulling values from this stream and the specified stream.
   * When one stream is exhausted all remaining values in the other stream
   * will be pulled.
   */
  final def interleave[R1 <: R, E1 >: E, A1 >: A](that: ZStream[R1, E1, A1]): ZStream[R1, E1, A1] =
    self.interleaveWith(that)(Stream(true, false).forever)

  /**
   * Combines this stream and the specified stream deterministically using the
   * stream of boolean values `b` to control which stream to pull from next.
   * `true` indicates to pull from this stream and `false` indicates to pull
   * from the specified stream. Only consumes as many elements as requested by
   * `b`. If either this stream or the specified stream are exhausted further
   * requests for values from that stream will be ignored.
   */
  final def interleaveWith[R1 <: R, E1 >: E, A1 >: A](
    that: ZStream[R1, E1, A1]
  )(b: ZStream[R1, E1, Boolean]): ZStream[R1, E1, A1] = {

    def loop(
      leftDone: Boolean,
      rightDone: Boolean,
      s: Pull[R1, E1, Boolean],
      left: Pull[R, E, A],
      right: Pull[R1, E1, A1]
    ): ZIO[R1, Nothing, ((Boolean, Boolean, Pull[R1, E1, Boolean]), Take[E1, A1])] =
      Take.fromPull(s).flatMap {
        case Take.Fail(e) => ZIO.succeed(((leftDone, rightDone, s), Take.Fail(e)))
        case Take.Value(b) =>
          if (b && !leftDone) {
            Take.fromPull(left).flatMap {
              case Take.Fail(e)  => ZIO.succeed(((leftDone, rightDone, s), Take.Fail(e)))
              case Take.Value(a) => ZIO.succeed(((leftDone, rightDone, s), Take.Value(a)))
              case Take.End =>
                if (rightDone) ZIO.succeed(((leftDone, rightDone, s), Take.End))
                else loop(true, rightDone, s, left, right)
            }
          } else if (!b && !rightDone)
            Take.fromPull(right).flatMap {
              case Take.Fail(e)  => ZIO.succeed(((leftDone, rightDone, s), Take.Fail(e)))
              case Take.Value(a) => ZIO.succeed(((leftDone, rightDone, s), Take.Value(a)))
              case Take.End =>
                if (leftDone) ZIO.succeed(((leftDone, rightDone, s), Take.End))
                else loop(leftDone, true, s, left, right)
            } else loop(leftDone, rightDone, s, left, right)
        case Take.End => ZIO.succeed(((leftDone, rightDone, s), Take.End))
      }

    ZStream {
      for {
        sides <- b.process
        result <- self
                   .combine(that)((false, false, sides)) {
                     case ((leftDone, rightDone, sides), left, right) =>
                       loop(leftDone, rightDone, sides, left, right)
                   }
                   .process
      } yield result
    }
  }

  /**
   * Interrupts the evaluation of this stream when the provided promise resolves. This
   * combinator will also interrupt any in-progress element being pulled from upstream.
   *
   * If the promise completes with a failure, the stream will emit that failure.
   */
  final def interruptWhen[E1 >: E](p: Promise[E1, _]): ZStream[R, E1, A] =
    ZStream {
      for {
        as   <- self.process
        done <- Ref.make(false).toManaged_
        pull = done.get flatMap {
          if (_) Pull.end
          else
            as.raceAttempt(
              p.await
                .mapError(Some(_))
                .foldCauseM(
                  c => done.set(true) *> ZIO.halt(c),
                  _ => done.set(true) *> Pull.end
                )
            )
        }
      } yield pull
    }

  /**
   * Enqueues elements of this stream into a queue. Stream failure and ending will also be
   * signalled.
   */
  final def into[R1 <: R, E1 >: E, A1 >: A](queue: ZQueue[R1, E1, Nothing, Any, Take[E1, A1], Any]): ZIO[R1, E1, Unit] =
    intoManaged(queue).use_(UIO.unit)

  /**
   * Like [[ZStream#into]], but provides the result as a [[ZManaged]] to allow for scope
   * composition.
   */
  final def intoManaged[R1 <: R, E1 >: E, A1 >: A](
    queue: ZQueue[R1, E1, Nothing, Any, Take[E1, A1], Any]
  ): ZManaged[R1, E1, Unit] =
    self
      .foreachManaged(a => queue.offer(Take.Value(a)).unit)
      .foldCauseM(
        cause => queue.offer(Take.Fail(cause)).unit.toManaged_,
        _ => queue.offer(Take.End).unit.toManaged_
      )

  /**
   * Returns a stream made of the elements of this stream transformed with `f0`
   */
  def map[B](f0: A => B): ZStream[R, E, B] =
    ZStream[R, E, B](self.process.map(_.map(f0)))

  /**
   * Statefully maps over the elements of this stream to produce new elements.
   */
  def mapAccum[S1, B](s1: S1)(f1: (S1, A) => (S1, B)): ZStream[R, E, B] =
    mapAccumM(s1)((s, a) => UIO.succeed(f1(s, a)))

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * new elements.
   */
  final def mapAccumM[R1 <: R, E1 >: E, S1, B](s1: S1)(f1: (S1, A) => ZIO[R1, E1, (S1, B)]): ZStream[R1, E1, B] =
    ZStream {
      for {
        state <- Ref.make(s1).toManaged_
        as    <- self.process
      } yield as.flatMap { a =>
        (for {
          s <- state.get
          t <- f1(s, a)
          _ <- state.set(t._1)
        } yield t._2).mapError(Some(_))
      }
    }

  /**
   * Maps each element to an iterable, and flattens the iterables into the
   * output of this stream.
   */
  final def mapConcat[B](f: A => Iterable[B]): ZStream[R, E, B] =
    mapConcatChunk(f andThen Chunk.fromIterable)

  /**
   * Maps each element to a chunk, and flattens the chunks into the output of
   * this stream.
   */
  def mapConcatChunk[B](f: A => Chunk[B]): ZStream[R, E, B] =
    flatMap(a => ZStream.fromChunk(f(a)))

  /**
   * Effectfully maps each element to a chunk, and flattens the chunks into
   * the output of this stream.
   */
  final def mapConcatChunkM[R1 <: R, E1 >: E, B](f: A => ZIO[R1, E1, Chunk[B]]): ZStream[R1, E1, B] =
    mapM(f).mapConcatChunk(identity)

  /**
   * Effectfully maps each element to an iterable, and flattens the iterables into
   * the output of this stream.
   */
  final def mapConcatM[R1 <: R, E1 >: E, B](f: A => ZIO[R1, E1, Iterable[B]]): ZStream[R1, E1, B] =
    mapM(a => f(a).map(Chunk.fromIterable(_))).mapConcatChunk(identity)

  /**
   * Transforms the errors that possibly result from this stream.
   */
  final def mapError[E1](f: E => E1)(implicit ev: CanFail[E]): ZStream[R, E1, A] =
    ZStream(self.process.map(_.mapError(_.map(f))))

  /**
   * Transforms the errors that possibly result from this stream.
   */
  final def mapErrorCause[E1](f: Cause[E] => Cause[E1]): ZStream[R, E1, A] =
    ZStream {
      self.process
        .map(_.mapErrorCause(Cause.sequenceCauseOption(_) match {
          case None    => Cause.fail(None)
          case Some(c) => f(c).map(Some(_))
        }))
    }

  /**
   * Maps over elements of the stream with the specified effectful function.
   */
  final def mapM[R1 <: R, E1 >: E, B](f: A => ZIO[R1, E1, B]): ZStream[R1, E1, B] =
    ZStream[R1, E1, B](self.process.map(_.flatMap(f(_).mapError(Some(_)))))

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. Transformed elements
   * will be emitted in the original order.
   */
  final def mapMPar[R1 <: R, E1 >: E, B](n: Int)(f: A => ZIO[R1, E1, B]): ZStream[R1, E1, B] =
    ZStream[R1, E1, B] {
      for {
        out     <- Queue.bounded[Pull[R1, E1, B]](n).toManaged(_.shutdown)
        permits <- Semaphore.make(n.toLong).toManaged_
        _ <- self.foreachManaged { a =>
              for {
                latch <- Promise.make[Nothing, Unit]
                p     <- Promise.make[E1, B]
                _     <- out.offer(Pull.fromPromise(p))
                _     <- (permits.withPermit(latch.succeed(()) *> f(a).to(p))).fork
                _     <- latch.await
              } yield ()

            }.foldCauseM(
                c => (ZIO.children.flatMap(Fiber.interruptAll) *> out.offer(Pull.halt(c))).unit.toManaged_,
                _ => out.offer(Pull.end).unit.toManaged_
              )
              .fork
      } yield out.take.flatten
    }

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order
   * is not enforced by this combinator, and elements may be reordered.
   */
  final def mapMParUnordered[R1 <: R, E1 >: E, B](n: Int)(f: A => ZIO[R1, E1, B]): ZStream[R1, E1, B] =
    self.flatMapPar[R1, E1, B](n)(a => ZStream.fromEffect(f(a)))

  /**
   * Maps over elements of the stream with the specified effectful function,
   * partitioned by `p` executing invocations of `f` concurrently. The number
   * of concurrent invocations of `f` is determined by the number of different
   * outputs of type `K`. Up to `buffer` elements may be buffered per partition.
   * Transformed elements may be reordered but the order within a partition is maintained.
   */
  final def mapMPartitioned[R1 <: R, E1 >: E, B, K](
    keyBy: A => K,
    buffer: Int = 16
  )(f: A => ZIO[R1, E1, B]): ZStream[R1, E1, B] =
    groupByKey(keyBy, buffer).apply { case (_, s) => s.mapM(f) }

  /**
   * Merges this stream and the specified stream together.
   */
  final def merge[R1 <: R, E1 >: E, A1 >: A](that: ZStream[R1, E1, A1]): ZStream[R1, E1, A1] =
    self.mergeWith[R1, E1, A1, A1](that)(identity, identity) // TODO: Dotty doesn't infer this properly

  /**
   * Merges this stream and the specified stream together to produce a stream of
   * eithers.
   */
  final def mergeEither[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, Either[A, B]] =
    self.mergeWith(that)(Left(_), Right(_))

  /**
   * Merges this stream and the specified stream together to a common element
   * type with the specified mapping functions.
   */
  final def mergeWith[R1 <: R, E1 >: E, B, C](that: ZStream[R1, E1, B])(l: A => C, r: B => C): ZStream[R1, E1, C] = {
    type Loser = Either[Fiber[Nothing, Take[E, A]], Fiber[Nothing, Take[E1, B]]]

    def race(
      left: ZIO[R, Nothing, Take[E, A]],
      right: ZIO[R1, Nothing, Take[E1, B]]
    ): ZIO[R1, Nothing, (Take[E1, C], Loser)] =
      left.raceWith[R1, Nothing, Nothing, Take[E1, B], (Take[E1, C], Loser)](right)(
        (exit, right) => ZIO.done(exit).map(a => (a.map(l), Right(right))),
        (exit, left) => ZIO.done(exit).map(b => (b.map(r), Left(left)))
      )

    self.combine(that)((false, false, Option.empty[Loser])) {
      case ((leftDone, rightDone, loser), left, right) =>
        if (leftDone) {
          Take.fromPull(right).map(_.map(r)).map(take => ((leftDone, rightDone, None), take))
        } else if (rightDone) {
          Take.fromPull(left).map(_.map(l)).map(take => ((leftDone, rightDone, None), take))
        } else {
          val result = loser match {
            case None               => race(Take.fromPull(left), Take.fromPull(right))
            case Some(Left(loser))  => race(loser.join, Take.fromPull(right))
            case Some(Right(loser)) => race(Take.fromPull(left), loser.join)
          }
          result.flatMap {
            case (Take.End, Left(loser)) =>
              loser.join.map(_.map(l)).map(take => ((leftDone, true, None), take))
            case (Take.End, Right(loser)) =>
              loser.join.map(_.map(r)).map(take => ((true, rightDone, None), take))
            case (Take.Value(c), loser) =>
              ZIO.succeed(((leftDone, rightDone, Some(loser)), Take.Value(c)))
            case (Take.Fail(e), loser) =>
              loser.merge.interrupt *> ZIO.succeed(((leftDone, rightDone, None), Take.Fail(e)))
          }
        }
    }
  }

  /**
   * Switches to the provided stream in case this one fails with a typed error.
   *
   * See also [[ZStream#catchAll]].
   */
  final def orElse[R1 <: R, E2, A1 >: A](that: => ZStream[R1, E2, A1])(implicit ev: CanFail[E]): ZStream[R1, E2, A1] =
    self.catchAll(_ => that)

  /**
   * Partition a stream using a predicate. The first stream will contain all element evaluated to true
   * and the second one will contain all element evaluated to false.
   * The faster stream may advance by up to buffer elements further than the slower one.
   */
  def partition(p: A => Boolean, buffer: Int = 16): ZManaged[R, E, (ZStream[R, E, A], ZStream[Any, E, A])] =
    self.partitionEither(a => if (p(a)) ZIO.succeed(Left(a)) else ZIO.succeed(Right(a)), buffer)

  /**
   * Split a stream by a predicate. The faster stream may advance by up to buffer elements further than the slower one.
   */
  final def partitionEither[B, C, R1 <: R, E1 >: E](
    p: A => ZIO[R1, E1, Either[B, C]],
    buffer: Int = 16
  ): ZManaged[R1, E1, (ZStream[Any, E1, B], ZStream[Any, E1, C])] =
    self
      .mapM(p)
      .distributedWith(2, buffer, {
        case Left(_)  => ZIO.succeed(_ == 0)
        case Right(_) => ZIO.succeed(_ == 1)
      })
      .flatMap {
        case q1 :: q2 :: Nil =>
          ZManaged.succeed {
            (
              ZStream.fromQueueWithShutdown(q1).unTake.collect { case Left(x)  => x },
              ZStream.fromQueueWithShutdown(q2).unTake.collect { case Right(x) => x }
            )
          }
        case _ => ZManaged.dieMessage("Internal error.")
      }

  /**
   * Peels off enough material from the stream to construct an `R` using the
   * provided [[ZSink]] and then returns both the `R` and the remainder of the
   * [[ZStream]] in a managed resource. Like all [[ZManaged]] values, the provided
   * remainder is valid only within the scope of [[ZManaged]].
   */
  final def peel[R1 <: R, E1 >: E, A1 >: A, B](
    sink: ZSink[R1, E1, A1, A1, B]
  ): ZManaged[R1, E1, (B, ZStream[R1, E1, A1])] =
    for {
      as <- self.process
      bAndLeftover <- ZManaged.fromEffect {
                       def runSink(state: sink.State): ZIO[R1, E1, sink.State] =
                         if (!sink.cont(state)) UIO.succeed(state)
                         else
                           as.foldM(
                             {
                               case Some(e) => IO.fail(e)
                               case None    => UIO.succeed(state)
                             },
                             sink.step(state, _).flatMap(runSink(_))
                           )

                       for {
                         initial <- sink.initial
                         last    <- runSink(initial)
                         result  <- sink.extract(last)
                       } yield result
                     }
      (b, leftover) = bAndLeftover
    } yield b -> (ZStream.fromChunk(leftover) ++ ZStream.repeatEffectOption(as))

  /**
   * Provides the stream with its required environment, which eliminates
   * its dependency on `R`.
   */
  final def provide(r: R)(implicit ev: NeedsEnv[R]): Stream[E, A] =
    ZStream(self.process.provide(r).map(_.provide(r)))

  /**
   * An effectual version of `provide`, useful when the act of provision
   * requires an effect.
   */
  final def provideM[E1 >: E](r: ZIO[Any, E1, R])(implicit ev: NeedsEnv[R]): Stream[E1, A] =
    provideSomeM(r)

  /**
   * Uses the given [[Managed]] to provide the environment required to run this stream,
   * leaving no outstanding environments.
   */
  final def provideManaged[E1 >: E](m: Managed[E1, R])(implicit ev: NeedsEnv[R]): Stream[E1, A] =
    ZStream.managed {
      for {
        r  <- m
        as <- self.process.provide(r)
      } yield as.provide(r)
    }.flatMap(ZStream.repeatEffectOption)

  /**
   * Provides some of the environment required to run this effect,
   * leaving the remainder `R0`.
   */
  final def provideSome[R0](env: R0 => R)(implicit ev: NeedsEnv[R]): ZStream[R0, E, A] =
    ZStream {
      for {
        r0 <- ZManaged.environment[R0]
        as <- self.process.provide(env(r0))
      } yield as.provide(env(r0))
    }

  /**
   * Provides some of the environment required to run this effect,
   * leaving the remainder `R0`.
   */
  final def provideSomeM[R0, E1 >: E](env: ZIO[R0, E1, R])(implicit ev: NeedsEnv[R]): ZStream[R0, E1, A] =
    provideSomeManaged(env.toManaged_)

  /**
   * Uses the given [[ZManaged]] to provide some of the environment required to run
   * this stream, leaving the remainder `R0`.
   */
  final def provideSomeManaged[R0, E1 >: E](env: ZManaged[R0, E1, R])(implicit ev: NeedsEnv[R]): ZStream[R0, E1, A] =
    ZStream.managed {
      for {
        r  <- env
        as <- self.process.provide(r)
      } yield as.provide(r)
    }.flatMap(ZStream.repeatEffectOption)

  /**
   * Repeats the entire stream using the specified schedule. The stream will execute normally,
   * and then repeat again according to the provided schedule.
   */
  final def repeat[R1 <: R, B, C](schedule: Schedule[R1, Unit, B]): ZStream[R1, E, A] =
    repeatEither(schedule) collect { case Right(a) => a }

  /**
   * Repeats the entire stream using the specified schedule. The stream will execute normally,
   * and then repeat again according to the provided schedule. The schedule output will be emitted at
   * the end of each repetition.
   */
  final def repeatEither[R1 <: R, B](schedule: Schedule[R1, Unit, B]): ZStream[R1, E, Either[B, A]] =
    repeatWith(schedule)(Right(_), Left(_))

  /**
   * Repeats the entire stream using the specified schedule. The stream will execute normally,
   * and then repeat again according to the provided schedule. The schedule output will be emitted at
   * the end of each repetition and can be unified with the stream elements using the provided functions.
   */
  final def repeatWith[R1 <: R, B, C](
    schedule: Schedule[R1, Unit, B]
  )(f: A => C, g: B => C): ZStream[R1, E, C] =
    ZStream[R1, E, C] {
      for {
        scheduleInit  <- schedule.initial.toManaged_
        schedStateRef <- Ref.make(scheduleInit).toManaged_
        switchPull    <- ZManaged.switchable[R1, Nothing, Pull[R1, E, C]]
        currPull      <- switchPull(self.map(f).process).flatMap(as => Ref.make(as)).toManaged_
        doneRef       <- Ref.make(false).toManaged_
        pull = {
          def go: ZIO[R1, Option[E], C] =
            doneRef.get.flatMap { done =>
              if (done) Pull.end
              else
                currPull.get.flatten.foldM(
                  {
                    case e @ Some(_) => ZIO.fail(e)
                    case None =>
                      schedStateRef.get
                        .flatMap(schedule.update((), _))
                        .foldM(
                          _ => doneRef.set(true) *> Pull.end,
                          state =>
                            switchPull((self.map(f) ++ Stream.succeed(g(schedule.extract((), state)))).process)
                              .tap(currPull.set(_)) *> schedStateRef.set(state) *> go
                        )
                  },
                  ZIO.succeed
                )
            }
          go
        }
      } yield pull
    }

  /**
   * Runs the sink on the stream to produce either the sink's result or an error.
   */
  def run[R1 <: R, E1 >: E, A1 >: A, B](sink: ZSink[R1, E1, Any, A1, B]): ZIO[R1, E1, B] =
    sink.initial.flatMap { initial =>
      self.process.use { as =>
        def pull(state: sink.State): ZIO[R1, E1, B] =
          as.foldM(
            {
              case Some(e) => ZIO.fail(e)
              case None    => sink.extract(state).map(_._1)
            },
            sink.step(state, _).flatMap { step =>
              if (sink.cont(step)) pull(step)
              else sink.extract(step).map(_._1)
            }
          )

        pull(initial)
      }
    }

  /**
   * Runs the stream returning the first element of the stream. Later elements will not be consumed / have effects run
   */
  def runHead: ZIO[R, E, Option[A]] = run(ZSink.head[A])

  /**
   * Runs the stream returning the last element and discarding all earlier elements.
   */
  def runLast: ZIO[R, E, Option[A]] = run(ZSink.last[A])

  /**
   * Runs the stream and collects all of its elements in a list.
   *
   * Equivalent to `run(Sink.collectAll[A])`.
   */
  final def runCollect: ZIO[R, E, List[A]] = run(Sink.collectAll[A])

  /**
   * Runs the stream purely for its effects. Any elements emitted by
   * the stream are discarded.
   *
   * Equivalent to `run(Sink.drain)`.
   */
  final def runDrain: ZIO[R, E, Unit] = run(Sink.drain)

  /**
   * Schedules the output of the stream using the provided `schedule` and emits its output at
   * the end (if `schedule` is finite).
   */
  final def schedule[R1 <: R, A1 >: A](schedule: Schedule[R1, A, Any]): ZStream[R1, E, A1] =
    scheduleEither(schedule).collect { case Right(a) => a }

  /**
   * Schedules the output of the stream using the provided `schedule` and emits its output at
   * the end (if `schedule` is finite).
   */
  final def scheduleEither[R1 <: R, E1 >: E, B](
    schedule: Schedule[R1, A, B]
  ): ZStream[R1, E1, Either[B, A]] =
    scheduleWith(schedule)(Right.apply, Left.apply)

  /**
   * Repeats each element of the stream using the provided `schedule`, additionally emitting the schedule's output
   * each time a schedule is completed.
   * Repeats are done in addition to the first execution, so that `scheduleElements(Schedule.once)` means "emit element
   * and if not short circuited, repeat element once".
   */
  final def scheduleElements[R1 <: R, A1 >: A](schedule: Schedule[R1, A, Any]): ZStream[R1, E, A1] =
    scheduleElementsEither(schedule).collect { case Right(a) => a }

  /**
   * Repeats each element of the stream using the provided `schedule`, additionally emitting the schedule's output
   * each time a schedule is completed.
   * Repeats are done in addition to the first execution, so that `scheduleElements(Schedule.once)` means "emit element
   * and if not short circuited, repeat element once".
   */
  final def scheduleElementsEither[R1 <: R, E1 >: E, B](
    schedule: Schedule[R1, A, B]
  ): ZStream[R1, E1, Either[B, A]] =
    scheduleElementsWith(schedule)(Right.apply, Left.apply)

  /**
   * Repeats each element of the stream using the provided schedule, additionally emitting the schedule's output
   * each time a schedule is completed.
   * Repeats are done in addition to the first execution, so that `scheduleElements(Schedule.once)` means "emit element
   * and if not short circuited, repeat element once".
   * Uses the provided functions to align the stream and schedule outputs on a common type.
   */
  final def scheduleElementsWith[R1 <: R, E1 >: E, B, C](
    schedule: Schedule[R1, A, B]
  )(f: A => C, g: B => C): ZStream[R1, E1, C] =
    ZStream[R1, E1, C] {
      for {
        as    <- self.process
        state <- Ref.make[Option[(A, schedule.State)]](None).toManaged_
        pull = {
          def go: Pull[R1, E1, C] = state.get.flatMap {
            case None =>
              for {
                a    <- as
                init <- schedule.initial
                _    <- state.set(Some(a -> init))
              } yield f(a)

            case Some((a, scheduleState)) =>
              schedule
                .update(a, scheduleState)
                .foldM(
                  _ => state.set(None).as(g(schedule.extract(a, scheduleState))),
                  s => state.set(Some(a -> s)).as(f(a))
                )
          }

          go
        }
      } yield pull
    }

  /**
   * Schedules the output of the stream using the provided `schedule` and emits its output at
   * the end (if `schedule` is finite).
   * Uses the provided function to align the stream and schedule outputs on the same type.
   */
  final def scheduleWith[R1 <: R, E1 >: E, B, C](
    schedule: Schedule[R1, A, B]
  )(f: A => C, g: B => C): ZStream[R1, E1, C] =
    ZStream[R1, E1, C] {
      for {
        as    <- self.process
        init  <- schedule.initial.toManaged_
        state <- Ref.make[(schedule.State, Option[() => B])]((init, None)).toManaged_
        pull = state.get.flatMap {
          case (sched0, finish0) =>
            // Before pulling from the stream, we need to check whether the previous
            // action ended the schedule, in which case we must emit its final output
            finish0 match {
              case None =>
                for {
                  a <- as.optional.mapError(Some(_))
                  c <- a match {
                        // There's a value emitted by the underlying stream, we emit it
                        // and check whether the schedule ends; in that case, we record
                        // its final state, to be emitted during the next pull
                        case Some(a) =>
                          schedule
                            .update(a, sched0)
                            .foldM(
                              _ =>
                                schedule.initial
                                  .flatMap(s1 => state.set((s1, Some(() => schedule.extract(a, sched0))))),
                              s => state.set((s, None))
                            )
                            .as(f(a))

                        // The stream ends when both the underlying stream ends and the final
                        // schedule value has been emitted
                        case None => Pull.end
                      }
                } yield c
              case Some(b) => state.set((sched0, None)) *> Pull.emit(g(b()))
            }
        }
      } yield pull
    }

  /**
   * Takes the specified number of elements from this stream.
   */
  def take(n: Long): ZStream[R, E, A] =
    ZStream {
      for {
        as      <- self.process
        counter <- Ref.make(0L).toManaged_
        pull = counter.get.flatMap { c =>
          if (c >= n) Pull.end
          else as <* counter.set(c + 1)
        }
      } yield pull
    }

  /**
   * Takes all elements of the stream until the specified predicate evaluates
   * to `true`.
   */
  def takeUntil(pred: A => Boolean): ZStream[R, E, A] =
    ZStream {
      for {
        as            <- self.process
        keepTakingRef <- Ref.make(true).toManaged_
        pull = keepTakingRef.get.flatMap { p =>
          if (!p) Pull.end
          else
            as.flatMap { a =>
              if (pred(a)) keepTakingRef.set(false).as(a)
              else Pull.emit(a)
            }
        }
      } yield pull
    }

  /**
   * Takes all elements of the stream for as long as the specified predicate
   * evaluates to `true`.
   */
  def takeWhile(pred: A => Boolean): ZStream[R, E, A] =
    ZStream {
      self.process.map { as =>
        for {
          a <- as
          result <- if (pred(a)) Pull.emit(a)
                   else Pull.end
        } yield result
      }
    }

  /**
   * Adds an effect to consumption of every element of the stream.
   */
  final def tap[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any]): ZStream[R1, E1, A] =
    ZStream[R1, E1, A](self.process.map(_.tap(f(_).mapError(Some(_)))))

  /**
   * Converts this stream of bytes into a `java.io.InputStream` wrapped in a [[ZManaged]].
   * The returned input stream will only be valid within the scope of the ZManaged.
   */
  @silent("never used")
  def toInputStream(implicit ev0: E <:< Throwable, ev1: A <:< Byte): ZManaged[R, E, java.io.InputStream] =
    for {
      runtime <- ZIO.runtime[R].toManaged_
      pull    <- process
      javaStream = new java.io.InputStream {
        override def read(): Int = {
          val exit = runtime.unsafeRunSync[Option[Throwable], Byte](pull.asInstanceOf[Pull[R, Throwable, Byte]])
          ZStream.exitToInputStreamRead(exit)
        }
      }
    } yield javaStream

  /**
   * Throttles elements of type A according to the given bandwidth parameters using the token bucket
   * algorithm. Allows for burst in the processing of elements by allowing the token bucket to accumulate
   * tokens up to a `units + burst` threshold. Elements that do not meet the bandwidth constraints are dropped.
   * The weight of each element is determined by the `costFn` function.
   */
  final def throttleEnforce(units: Long, duration: Duration, burst: Long = 0)(
    costFn: A => Long
  ): ZStream[R with Clock, E, A] =
    throttleEnforceM(units, duration, burst)(a => UIO.succeed(costFn(a)))

  /**
   * Throttles elements of type A according to the given bandwidth parameters using the token bucket
   * algorithm. Allows for burst in the processing of elements by allowing the token bucket to accumulate
   * tokens up to a `units + burst` threshold. Elements that do not meet the bandwidth constraints are dropped.
   * The weight of each element is determined by the `costFn` effectful function.
   */
  final def throttleEnforceM[R1 <: R, E1 >: E](units: Long, duration: Duration, burst: Long = 0)(
    costFn: A => ZIO[R1, E1, Long]
  ): ZStream[R1 with Clock, E1, A] =
    aggregateManaged(ZSink.throttleEnforceM(units, duration, burst)(costFn)).collect { case Some(a) => a }

  /**
   * Delays elements of type A according to the given bandwidth parameters using the token bucket
   * algorithm. Allows for burst in the processing of elements by allowing the token bucket to accumulate
   * tokens up to a `units + burst` threshold. The weight of each element is determined by the `costFn` function.
   */
  final def throttleShape(units: Long, duration: Duration, burst: Long = 0)(
    costFn: A => Long
  ): ZStream[R with Clock, E, A] =
    throttleShapeM(units, duration, burst)(a => UIO.succeed(costFn(a)))

  /**
   * Delays elements of type A according to the given bandwidth parameters using the token bucket
   * algorithm. Allows for burst in the processing of elements by allowing the token bucket to accumulate
   * tokens up to a `units + burst` threshold. The weight of each element is determined by the `costFn`
   * effectful function.
   */
  final def throttleShapeM[R1 <: R, E1 >: E](units: Long, duration: Duration, burst: Long = 0)(
    costFn: A => ZIO[R1, E1, Long]
  ): ZStream[R1 with Clock, E1, A] =
    aggregateManaged(ZSink.throttleShapeM(units, duration, burst)(costFn))

  /**
   * Interrupts the stream if it does not produce a value after d duration.
   */
  final def timeout(d: Duration): ZStream[R with Clock, E, A] =
    ZStream[R with Clock, E, A] {
      self.process.map { next =>
        next.timeout(d).flatMap {
          case Some(a) => ZIO.succeed(a)
          case None    => ZIO.interrupt
        }
      }
    }

  /**
   * Converts the stream to a managed queue. After the managed queue is used,
   * the queue will never again produce values and should be discarded.
   */
  final def toQueue[E1 >: E, A1 >: A](capacity: Int = 2): ZManaged[R, Nothing, Queue[Take[E1, A1]]] =
    for {
      queue <- ZManaged.make(Queue.bounded[Take[E1, A1]](capacity))(_.shutdown)
      _     <- self.intoManaged(queue).fork
    } yield queue

  /**
   * Converts the stream into an unbounded managed queue. After the managed queue
   * is used, the queue will never again produce values and should be discarded.
   */
  final def toQueueUnbounded[E1 >: E, A1 >: A]: ZManaged[R, Nothing, Queue[Take[E1, A1]]] =
    for {
      queue <- ZManaged.make(Queue.unbounded[Take[E1, A1]])(_.shutdown)
      _     <- self.intoManaged(queue).fork
    } yield queue

  /**
   * Alias for `aggregateManaged`
   */
  final def transduceManaged[R1 <: R, E1 >: E, A1 >: A, B](
    managedSink: ZManaged[R1, E1, ZSink[R1, E1, A1, A1, B]]
  ): ZStream[R1, E1, B] = aggregateManaged(managedSink)

  /**
   * Alias for `aggregate`.
   */
  def transduce[R1 <: R, E1 >: E, A1 >: A, C](sink: ZSink[R1, E1, A1, A1, C]): ZStream[R1, E1, C] =
    aggregate[R1, E1, A1, C](sink)

  /**
   * Filters any 'None'.
   */
  final def unNone[A1](implicit ev: A <:< Option[A1]): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Option[A1]]].collect { case Some(a) => a }
  }

  /**
   * Filters any 'Take'.
   */
  final def unTake[E1 >: E, A1](implicit ev: A <:< Take[E1, A1]): ZStream[R, E1, A1] = {
    val _ = ev
    ZStream(self.process.map(_.flatMap(Pull.fromTake(_))))
  }

  /**
   * Threads the stream through the transformation function `f`.
   */
  final def via[R2, E2, B](f: ZStream[R, E, A] => ZStream[R2, E2, B]): ZStream[R2, E2, B] = f(self)

  /**
   * Zips this stream together with the specified stream.
   */
  final def zip[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, (A, B)] =
    self.zipWith(that)((left, right) => left.flatMap(a => right.map(a -> _)))

  /**
   * Zips two streams together with a specified function.
   */
  final def zipWith[R1 <: R, E1 >: E, B, C](
    that: ZStream[R1, E1, B]
  )(f0: (Option[A], Option[B]) => Option[C]): ZStream[R1, E1, C] = {
    def loop(
      leftDone: Boolean,
      rightDone: Boolean,
      left: Pull[R, E, A],
      right: Pull[R1, E1, B]
    ): ZIO[R1, E1, ((Boolean, Boolean), Take[E1, C])] = {
      val takeLeft: ZIO[R, E, Option[A]]    = if (leftDone) IO.succeed(None) else left.optional
      val takeRight: ZIO[R1, E1, Option[B]] = if (rightDone) IO.succeed(None) else right.optional

      def handleSuccess(left: Option[A], right: Option[B]): ZIO[Any, Nothing, ((Boolean, Boolean), Take[E1, C])] =
        f0(left, right) match {
          case None    => ZIO.succeed(((leftDone, rightDone), Take.End))
          case Some(c) => ZIO.succeed(((left.isEmpty, right.isEmpty), Take.Value(c)))
        }

      takeLeft.raceWith(takeRight)(
        (leftResult, rightFiber) =>
          leftResult.fold(
            e => rightFiber.interrupt *> ZIO.succeed(((leftDone, rightDone), Take.Fail(e))),
            l => rightFiber.join.flatMap(r => handleSuccess(l, r))
          ),
        (rightResult, leftFiber) =>
          rightResult.fold(
            e => leftFiber.interrupt *> ZIO.succeed(((leftDone, rightDone), Take.Fail(e))),
            r => leftFiber.join.flatMap(l => handleSuccess(l, r))
          )
      )
    }

    self.combine(that)((false, false)) {
      case ((leftDone, rightDone), left, right) =>
        loop(leftDone, rightDone, left, right)
    }
  }

  /**
   * Zips this stream together with the index of elements of the stream.
   */
  final def zipWithIndex: ZStream[R, E, (A, Long)] =
    mapAccum(0L)((index, a) => (index + 1, (a, index)))

  /**
   * Zips the two streams so that when a value is emitted by either of the two streams,
   * it is combined with the latest value from the other stream to produce a result.
   */
  final def zipWithLatest[R1 <: R, E1 >: E, B, C](that: ZStream[R1, E1, B])(f0: (A, B) => C): ZStream[R1, E1, C] =
    ZStream[R1, E1, C] {
      for {
        is    <- self.mergeEither(that).process
        state <- Ref.make[(Option[A], Option[B])]((None, None)).toManaged_
        pull: Pull[R1, E1, C] = {
          def go: Pull[R1, E1, C] = is.flatMap { i =>
            state
              .modify[Pull[R1, E1, C]] {
                case (previousLeft, previousRight) =>
                  i match {
                    case Left(a) =>
                      previousRight.fold(go)(b => Pull.emit(f0(a, b))) -> (Some(a) -> previousRight)
                    case Right(b) =>
                      previousLeft.fold(go)(a => Pull.emit(f0(a, b))) -> (previousLeft -> Some(b))
                  }
              }
              .flatten
          }

          go
        }
      } yield pull
    }

  /**
   * Composes this stream with the specified stream to create a cartesian product of elements
   * with a specified function.
   * The `that` stream would be run multiple times, for every element in the `this` stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise variant.
   */
  final def crossWith[R1 <: R, E1 >: E, B, C](that: ZStream[R1, E1, B])(f: (A, B) => C): ZStream[R1, E1, C] =
    self.flatMap(l => that.map(r => f(l, r)))

  /**
   * Composes this stream with the specified stream to create a cartesian product of elements.
   * The `that` stream would be run multiple times, for every element in the `this` stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise variant.
   */
  final def cross[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, (A, B)] =
    (self crossWith that)((_, _))

  /**
   * Composes this stream with the specified stream to create a cartesian product of elements.
   * The `that` stream would be run multiple times, for every element in the `this` stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise variant.
   */
  final def <*>[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, (A, B)] =
    (self crossWith that)((_, _))

  /**
   * Composes this stream with the specified stream to create a cartesian product of elements
   * and keeps only the elements from the `this` stream. The `that` stream would be run multiple
   * times, for every element in the `this` stream.
   *
   * See also [[ZStream#zipWith]] and [[ZStream#<&>]] for the more common point-wise variant.
   */
  final def <*[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, A] =
    (self <*> that).map(_._1)

  /**
   * Composes this stream with the specified stream to create a cartesian product of elements
   * and keeps only the elements from the `that` stream. The `that` stream would be run multiple
   * times, for every element in the `this` stream.
   *
   * See also [[ZStream#zipWith]] and [[ZStream#<&>]] for the more common point-wise variant.
   */
  final def *>[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, B] =
    (self <*> that).map(_._2)

  /**
   * Operator alias for `zip`
   */
  final def <&>[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, (A, B)] =
    self zip that

  /**
   * Runs this stream with the specified stream parallelly and keeps only values of this stream.
   */
  final def zipLeft[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, A] =
    (self <&> that).map(_._1)

  /**
   * Runs this stream with the specified stream parallelly and keeps only values of specified stream.
   */
  final def zipRight[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, B] =
    (self <&> that).map(_._2)

  /**
   * Operator alias for `zipLeft`
   */
  final def <&[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, A] =
    self zipLeft that

  /**
   * Operator alias for `zipRight`
   */
  final def &>[R1 <: R, E1 >: E, B](that: ZStream[R1, E1, B]): ZStream[R1, E1, B] =
    self zipRight that
}

object ZStream extends ZStreamPlatformSpecificConstructors with Serializable {

  /**
   * Describes an effectful pull from a stream. The optionality of the error channel denotes
   * normal termination of the stream when `None` and an error when `Some(e: E)`.
   */
  type Pull[-R, +E, +A] = ZIO[R, Option[E], A]

  object Pull {
    val end: Pull[Any, Nothing, Nothing]                         = IO.fail(None)
    def emit[A](a: A): Pull[Any, Nothing, A]                     = UIO.succeed(a)
    def fail[E](e: E): Pull[Any, E, Nothing]                     = IO.fail(Some(e))
    def halt[E](c: Cause[E]): Pull[Any, E, Nothing]              = IO.halt(c.map(Some(_)))
    def die(t: Throwable): Pull[Any, Nothing, Nothing]           = UIO.die(t)
    def dieMessage(m: String): Pull[Any, Nothing, Nothing]       = UIO.dieMessage(m)
    def done[E, A](e: Exit[E, A]): Pull[Any, E, A]               = IO.done(e.mapError(Some(_)))
    def fromPromise[E, A](p: Promise[E, A]): Pull[Any, E, A]     = p.await.mapError(Some(_))
    def fromEffect[R, E, A](effect: ZIO[R, E, A]): Pull[R, E, A] = effect.mapError(Some(_))

    def fromTake[E, A](take: Take[E, A]): Pull[Any, E, A] =
      take match {
        case Take.Value(a) => emit(a)
        case Take.Fail(e)  => halt(e)
        case Take.End      => end
      }
  }

  private[stream] object internal {
    sealed abstract class AggregateState[+S, +A]
    object AggregateState {
      final case class Initial[A](leftovers: Chunk[A])                    extends AggregateState[Nothing, A]
      final case class Pull[S](s: S, dirty: Boolean)                      extends AggregateState[S, Nothing]
      final case class Extract[S, A](s: S, leftovers: Chunk[A])           extends AggregateState[S, A]
      final case class Drain[S, A](s: S, leftovers: Chunk[A], index: Int) extends AggregateState[S, A]
      final case class DirtyDone[S](s: S)                                 extends AggregateState[S, Nothing]
      case object Done                                                    extends AggregateState[Nothing, Nothing]
    }
  }

  private[stream] sealed abstract class Structure[-R, +E, +A] {
    def process: ZManaged[R, Nothing, Pull[R, E, A]]
  }

  private[stream] object Structure {
    final case class Iterator[-R, +E, +A](process: ZManaged[R, Nothing, Pull[R, E, A]]) extends Structure[R, E, A]
    final case class Concat[-R, +E, +A](hd: Structure[R, E, A], tl: () => Structure[R, E, A])
        extends Structure[R, E, A] {
      val process: ZManaged[R, Nothing, Pull[R, E, A]] = {
        def go(
          doneRef: Ref[Boolean],
          currPull: Ref[Pull[R, E, A]],
          nextPull: Ref[Option[() => Structure[R, E, A]]],
          switchPull: ZManaged[R, Nothing, Pull[R, E, A]] => ZIO[R, Nothing, Pull[R, E, A]]
        ): Pull[R, E, A] =
          doneRef.get.flatMap { done =>
            if (done) Pull.end
            else
              currPull.get.flatten.catchAll {
                case e @ Some(_) => ZIO.fail(e)
                case None =>
                  nextPull.get.flatMap {
                    case None => doneRef.set(true) *> Pull.end
                    case Some(tl) =>
                      tl() match {
                        case Iterator(iter) =>
                          switchPull(iter).tap(currPull.set) *>
                            nextPull.set(None) *> go(doneRef, currPull, nextPull, switchPull)
                        case Concat(hd, tl) =>
                          // It is extremely important in this case to *NOT* recurse using
                          // tl.process, as that would introduce a new ZManaged scope; this
                          // will cause space leaks when used with streams that concatenate infinitely.
                          // Instead, we re-use the same ZManaged scope introduced by the ZManaged.switchable
                          // below to swap the current stream being pulled with tl.
                          switchPull(hd.process).tap(currPull.set) *>
                            nextPull.set(Some(tl)) *> go(doneRef, currPull, nextPull, switchPull)
                      }
                  }
              }
          }

        for {
          switchPull <- ZManaged.switchable[R, Nothing, Pull[R, E, A]]
          as         <- switchPull(hd.process).toManaged_
          currPull   <- Ref.make[Pull[R, E, A]](as).toManaged_
          nextPull   <- Ref.make[Option[() => Structure[R, E, A]]](Some(tl)).toManaged_
          doneRef    <- Ref.make(false).toManaged_
        } yield go(doneRef, currPull, nextPull, switchPull)
      }
    }
  }

  /**
   * Representation of a grouped stream.
   * This allows to filter which groups will be processed.
   * Once this is applied all groups will be processed in parallel and the results will
   * be merged in arbitrary order.
   */
  final class GroupBy[-R, +E, +K, +V](
    private val grouped: ZStream[R, E, (K, GroupBy.DequeueOnly[Take[E, V]])],
    private val buffer: Int
  ) {

    /**
     * Only consider the first n groups found in the stream.
     */
    def first(n: Int): GroupBy[R, E, K, V] = {
      val g1 = grouped.zipWithIndex.filterM {
        case elem @ ((_, q), i) =>
          if (i < n) ZIO.succeed(elem).as(true)
          else q.shutdown.as(false)
      }.map(_._1)
      new GroupBy(g1, buffer)
    }

    /**
     * Filter the groups to be processed.
     */
    def filter(f: K => Boolean): GroupBy[R, E, K, V] = {
      val g1 = grouped.filterM {
        case elem @ (k, q) =>
          if (f(k)) ZIO.succeed(elem).as(true)
          else q.shutdown.as(false)
      }
      new GroupBy(g1, buffer)
    }

    /**
     * Run the function across all groups, collecting the results in an arbitrary order.
     */
    def apply[R1 <: R, E1 >: E, A](f: (K, Stream[E, V]) => ZStream[R1, E1, A]): ZStream[R1, E1, A] =
      grouped.flatMapPar[R1, E1, A](Int.MaxValue, buffer) {
        case (k, q) =>
          f(k, ZStream.fromQueueWithShutdown(q).unTake)
      }
  }

  object GroupBy {
    // Queue that only allow taking
    type DequeueOnly[+A] = ZQueue[Any, Nothing, Any, Nothing, Nothing, A]
  }

  /**
   * The empty stream
   */
  val empty: Stream[Nothing, Nothing] =
    StreamEffect.empty

  /**
   * The stream that never produces any value or fails with any error.
   */
  val never: Stream[Nothing, Nothing] =
    ZStream(ZManaged.succeed(UIO.never))

  /**
   * The stream of units
   */
  val unit: Stream[Nothing, Unit] =
    ZStream(()).forever

  /**
   * Submerges the error case of an `Either` into the `ZStream`.
   */
  def absolve[R, E, A](xs: ZStream[R, E, Either[E, A]]): ZStream[R, E, A] =
    xs.flatMap(_.fold(fail, succeed))

  /**
   * Creates a pure stream from a variable list of values
   */
  def apply[A](as: A*): Stream[Nothing, A] = fromIterable(as)

  /**
   * Creates a stream from a scoped [[Pull]].
   */
  def apply[R, E, A](pull: ZManaged[R, Nothing, Pull[R, E, A]]): ZStream[R, E, A] =
    new ZStream[R, E, A](ZStream.Structure.Iterator(pull))

  /**
   * Accesses the environment of the stream.
   */
  def access[R]: AccessPartiallyApplied[R] =
    new AccessPartiallyApplied[R]

  /**
   * Accesses the environment of the stream in the context of an effect.
   */
  def accessM[R]: AccessMPartiallyApplied[R] =
    new AccessMPartiallyApplied[R]

  /**
   * Accesses the environment of the stream in the context of a stream.
   */
  def accessStream[R]: AccessStreamPartiallyApplied[R] =
    new AccessStreamPartiallyApplied[R]

  /**
   * Creates a stream from a single value that will get cleaned up after the
   * stream is consumed
   */
  def bracket[R, E, A](acquire: ZIO[R, E, A])(release: A => ZIO[R, Nothing, Any]): ZStream[R, E, A] =
    managed(ZManaged.make(acquire)(release))

  /**
   * Creates a stream from a single value that will get cleaned up after the
   * stream is consumed
   */
  def bracketExit[R, E, A](
    acquire: ZIO[R, E, A]
  )(release: (A, Exit[Any, Any]) => ZIO[R, Nothing, Any]): ZStream[R, E, A] =
    managed(ZManaged.makeExit(acquire)(release))

  /**
   * Composes the specified streams to create a cartesian product of elements
   * with a specified function. Subsequent streams would be run multiple times,
   * for every combination of elements in the prior streams.
   *
   * See also [[ZStream#zipN[R,E,A,B,C]*]] for the more common point-wise variant.
   */
  def crossN[R, E, A, B, C](zStream1: ZStream[R, E, A], zStream2: ZStream[R, E, B])(
    f: (A, B) => C
  ): ZStream[R, E, C] =
    zStream1.crossWith(zStream2)(f)

  /**
   * Composes the specified streams to create a cartesian product of elements
   * with a specified function. Subsequent stream would be run multiple times,
   * for every combination of elements in the prior streams.
   *
   * See also [[ZStream#zipN[R,E,A,B,C,D]*]] for the more common point-wise variant.
   */
  def crossN[R, E, A, B, C, D](
    zStream1: ZStream[R, E, A],
    zStream2: ZStream[R, E, B],
    zStream3: ZStream[R, E, C]
  )(
    f: (A, B, C) => D
  ): ZStream[R, E, D] =
    for {
      a <- zStream1
      b <- zStream2
      c <- zStream3
    } yield f(a, b, c)

  /**
   * Composes the specified streams to create a cartesian product of elements
   * with a specified function. Subsequent stream would be run multiple times,
   * for every combination of elements in the prior streams.
   *
   * See also [[ZStream#zipN[R,E,A,B,C,D,F]*]] for the more common point-wise variant.
   */
  def crossN[R, E, A, B, C, D, F](
    zStream1: ZStream[R, E, A],
    zStream2: ZStream[R, E, B],
    zStream3: ZStream[R, E, C],
    zStream4: ZStream[R, E, D]
  )(
    f: (A, B, C, D) => F
  ): ZStream[R, E, F] =
    for {
      a <- zStream1
      b <- zStream2
      c <- zStream3
      d <- zStream4
    } yield f(a, b, c, d)

  /**
   * The stream that always dies with `ex`.
   */
  def die(ex: Throwable): Stream[Nothing, Nothing] =
    halt(Cause.die(ex))

  /**
   * The stream that always dies with an exception described by `msg`.
   */
  def dieMessage(msg: String): Stream[Nothing, Nothing] =
    halt(Cause.die(new RuntimeException(msg)))

  /**
   * Creates a stream from an asynchronous callback that can be called multiple times.
   * The optionality of the error type `E` can be used to signal the end of the stream,
   * by setting it to `None`.
   */
  def effectAsync[R, E, A](
    register: (ZIO[R, Option[E], A] => Unit) => Unit,
    outputBuffer: Int = 16
  ): ZStream[R, E, A] =
    effectAsyncMaybe(callback => {
      register(callback)
      None
    }, outputBuffer)

  /**
   * Creates a stream from an asynchronous callback that can be called multiple times.
   * The registration of the callback can possibly return the stream synchronously.
   * The optionality of the error type `E` can be used to signal the end of the stream,
   * by setting it to `None`.
   */
  def effectAsyncMaybe[R, E, A](
    register: (ZIO[R, Option[E], A] => Unit) => Option[ZStream[R, E, A]],
    outputBuffer: Int = 16
  ): ZStream[R, E, A] =
    ZStream {
      for {
        output  <- Queue.bounded[Pull[R, E, A]](outputBuffer).toManaged(_.shutdown)
        runtime <- ZIO.runtime[R].toManaged_
        maybeStream <- ZManaged.effectTotal {
                        register { k =>
                          try {
                            runtime.unsafeRun {
                              k.foldCauseM(
                                  Cause
                                    .sequenceCauseOption(_)
                                    .fold(output.offer(Pull.end))(c => output.offer(Pull.halt(c))),
                                  a => output.offer(Pull.emit(a))
                                )
                                .unit
                            }
                          } catch {
                            case FiberFailure(Cause.Interrupt(_)) =>
                          }
                        }
                      }
        pull <- maybeStream match {
                 case Some(stream) => output.shutdown.toManaged_ *> stream.process
                 case None =>
                   for {
                     done <- Ref.make(false).toManaged_
                   } yield done.get.flatMap {
                     if (_) Pull.end
                     else
                       output.take.flatten.foldCauseM(
                         Cause
                           .sequenceCauseOption(_)
                           .fold[Pull[R, E, Nothing]](done.set(true) *> output.shutdown *> Pull.end)(Pull.halt),
                         Pull.emit
                       )
                   }
               }
      } yield pull
    }

  /**
   * Creates a stream from an asynchronous callback that can be called multiple times
   * The registration of the callback itself returns an effect. The optionality of the
   * error type `E` can be used to signal the end of the stream, by setting it to `None`.
   */
  def effectAsyncM[R, E, A](
    register: (ZIO[R, Option[E], A] => Unit) => ZIO[R, E, Any],
    outputBuffer: Int = 16
  ): ZStream[R, E, A] =
    managed {
      for {
        output  <- Queue.bounded[Pull[R, E, A]](outputBuffer).toManaged(_.shutdown)
        runtime <- ZIO.runtime[R].toManaged_
        _ <- register(
              k =>
                try {
                  runtime.unsafeRun {
                    k.foldCauseM(
                        Cause
                          .sequenceCauseOption(_)
                          .fold(output.offer(Pull.end))(c => output.offer(Pull.halt(c))),
                        a => output.offer(Pull.emit(a))
                      )
                      .unit
                  }
                } catch {
                  case FiberFailure(Cause.Interrupt(_)) =>
                }
            ).toManaged_
        done <- Ref.make(false).toManaged_
      } yield done.get.flatMap {
        if (_) Pull.end
        else
          output.take.flatten.foldCauseM(
            Cause
              .sequenceCauseOption(_)
              .fold[Pull[R, E, Nothing]](done.set(true) *> output.shutdown *> Pull.end)(Pull.halt),
            Pull.emit
          )
      }
    }.flatMap(repeatEffectOption)

  /**
   * Creates a stream from an asynchronous callback that can be called multiple times.
   * The registration of the callback returns either a canceler or synchronously returns a stream.
   * The optionality of the error type `E` can be used to signal the end of the stream, by
   * setting it to `None`.
   */
  def effectAsyncInterrupt[R, E, A](
    register: (ZIO[R, Option[E], A] => Unit) => Either[Canceler[R], ZStream[R, E, A]],
    outputBuffer: Int = 16
  ): ZStream[R, E, A] =
    ZStream {
      for {
        output  <- Queue.bounded[Pull[R, E, A]](outputBuffer).toManaged(_.shutdown)
        runtime <- ZIO.runtime[R].toManaged_
        eitherStream <- ZManaged.effectTotal {
                         register(
                           k =>
                             try {
                               runtime.unsafeRun {
                                 k.foldCauseM(
                                     Cause
                                       .sequenceCauseOption(_)
                                       .fold(output.offer(Pull.end))(c => output.offer(Pull.halt(c))),
                                     a => output.offer(Pull.emit(a))
                                   )
                                   .unit
                               }
                             } catch {
                               case FiberFailure(Cause.Interrupt(_)) =>
                             }
                         )
                       }
        pull <- eitherStream match {
                 case Left(canceler) =>
                   (for {
                     done <- Ref.make(false).toManaged_
                   } yield done.get.flatMap {
                     if (_) Pull.end
                     else
                       output.take.flatten.foldCauseM(
                         Cause
                           .sequenceCauseOption(_)
                           .fold[Pull[R, E, Nothing]](done.set(true) *> output.shutdown *> Pull.end)(Pull.halt),
                         Pull.emit
                       )
                   }).ensuring(canceler)
                 case Right(stream) => output.shutdown.toManaged_ *> stream.process
               }
      } yield pull
    }

  /**
   * Accesses the whole environment of the stream.
   */
  def environment[R]: ZStream[R, Nothing, R] =
    ZStream.fromEffect(ZIO.environment[R])

  /**
   * The stream that always fails with `error`
   */
  def fail[E](error: E): Stream[E, Nothing] =
    StreamEffect.fail[E](error)

  /**
   * Creates an empty stream that never fails and executes the finalizer when it ends.
   */
  def finalizer[R](finalizer: ZIO[R, Nothing, Any]): ZStream[R, Nothing, Nothing] =
    ZStream {
      for {
        finalizerRef <- ZManaged.finalizerRef[R](_ => UIO.unit)
        pull         = (finalizerRef.set(_ => finalizer) *> Pull.end).uninterruptible
      } yield pull
    }

  /**
   * Flattens nested streams.
   */
  def flatten[R, E, A](fa: ZStream[R, E, ZStream[R, E, A]]): ZStream[R, E, A] =
    fa.flatMap(identity)

  /**
   * Flattens a stream of streams into a stream by executing a non-deterministic
   * concurrent merge. Up to `n` streams may be consumed in parallel and up to
   * `outputBuffer` elements may be buffered by this operator.
   */
  def flattenPar[R, E, A](n: Int, outputBuffer: Int = 16)(
    fa: ZStream[R, E, ZStream[R, E, A]]
  ): ZStream[R, E, A] =
    fa.flatMapPar(n, outputBuffer)(identity)

  /**
   * Like [[flattenPar]], but executes all streams concurrently.
   */
  def flattenParUnbounded[R, E, A](outputBuffer: Int = 16)(
    fa: ZStream[R, E, ZStream[R, E, A]]
  ): ZStream[R, E, A] =
    flattenPar(Int.MaxValue, outputBuffer)(fa)

  /**
   * Creates a stream from a [[java.io.InputStream]]
   */
  def fromInputStream(
    is: InputStream,
    chunkSize: Int = ZStreamChunk.DefaultChunkSize
  ): StreamEffectChunk[Any, IOException, Byte] =
    StreamEffect.fromInputStream(is, chunkSize)

  /**
   * Creates a stream from a [[zio.Chunk]] of values
   */
  def fromChunk[A](c: Chunk[A]): Stream[Nothing, A] =
    StreamEffect.fromChunk(c)

  /**
   * Creates a stream from an effect producing a value of type `A`
   */
  def fromEffect[R, E, A](fa: ZIO[R, E, A]): ZStream[R, E, A] =
    managed(fa.toManaged_)

  /**
   * Creates a stream from an effect producing a value of type `A` or an empty Stream
   */
  def fromEffectOption[R, E, A](fa: ZIO[R, Option[E], A]): ZStream[R, E, A] =
    ZStream.unwrap {
      fa.fold(_.fold[ZStream[Any, E, Nothing]](ZStream.empty)(ZStream.fail(_)), ZStream.succeed(_))
    }

  /**
   * Creates a stream from an iterable collection of values
   */
  def fromIterable[A](as: Iterable[A]): Stream[Nothing, A] =
    StreamEffect.fromIterable(as)

  /**
   * Creates a stream from an iterator
   */
  def fromIterator[R, E, A](iterator: ZIO[R, E, Iterator[A]]): ZStream[R, E, A] =
    fromEffect(iterator).flatMap(StreamEffect.fromIterator)

  /**
   * Creates a stream from a Java iterator
   */
  def fromJavaIterator[R, E, A](iterator: ZIO[R, E, ju.Iterator[A]]): ZStream[R, E, A] =
    fromEffect(iterator).flatMap(StreamEffect.fromJavaIterator)

  /**
   * Creates a stream from a managed iterator
   */
  def fromIteratorManaged[R, E, A](iterator: ZManaged[R, E, Iterator[A]]): ZStream[R, E, A] =
    managed(iterator).flatMap(StreamEffect.fromIterator)

  /**
   * Creates a stream from a managed iterator
   */
  def fromJavaIteratorManaged[R, E, A](iterator: ZManaged[R, E, ju.Iterator[A]]): ZStream[R, E, A] =
    managed(iterator).flatMap(StreamEffect.fromJavaIterator)

  /**
   * Creates a stream from a [[zio.ZQueue]] of values
   */
  def fromQueue[R, E, A](queue: ZQueue[Nothing, Any, R, E, Nothing, A]): ZStream[R, E, A] =
    ZStream {
      ZManaged.succeed {
        queue.take.catchAllCause(
          c =>
            queue.isShutdown.flatMap { down =>
              if (down && c.interrupted) Pull.end
              else Pull.halt(c)
            }
        )
      }
    }

  /**
   * Creates a stream from a [[zio.ZQueue]] of values. The queue will be shutdown once the stream is closed.
   */
  def fromQueueWithShutdown[R, E, A](queue: ZQueue[Nothing, Any, R, E, Nothing, A]): ZStream[R, E, A] =
    fromQueue(queue).ensuringFirst(queue.shutdown)

  /**
   * The stream that always halts with `cause`.
   */
  def halt[E](cause: Cause[E]): ZStream[Any, E, Nothing] = fromEffect(ZIO.halt(cause))

  /**
   * The infinite stream of iterative function application: a, f(a), f(f(a)), f(f(f(a))), ...
   */
  def iterate[A](a: A)(f: A => A): ZStream[Any, Nothing, A] =
    StreamEffect.iterate(a)(f)

  /**
   * Creates a single-valued stream from a managed resource
   */
  def managed[R, E, A](managed: ZManaged[R, E, A]): ZStream[R, E, A] =
    ZStream {
      for {
        doneRef   <- Ref.make(false).toManaged_
        finalizer <- ZManaged.finalizerRef[R](_ => UIO.unit)
        pull = ZIO.uninterruptibleMask { restore =>
          doneRef.get.flatMap { done =>
            if (done) Pull.end
            else
              (for {
                _           <- doneRef.set(true)
                reservation <- managed.reserve
                _           <- finalizer.set(reservation.release)
                a           <- restore(reservation.acquire)
              } yield a).mapError(Some(_))
          }
        }
      } yield pull
    }

  /**
   * Merges a variable list of streams in a non-deterministic fashion.
   * Up to `n` streams may be consumed in parallel and up to
   * `outputBuffer` elements may be buffered by this operator.
   */
  def mergeAll[R, E, A](n: Int, outputBuffer: Int = 16)(
    streams: ZStream[R, E, A]*
  ): ZStream[R, E, A] =
    flattenPar(n, outputBuffer)(fromIterable(streams))

  /**
   * Like [[mergeAll]], but runs all streams concurrently.
   */
  def mergeAllUnbounded[R, E, A](outputBuffer: Int = 16)(
    streams: ZStream[R, E, A]*
  ): ZStream[R, E, A] = mergeAll(Int.MaxValue, outputBuffer)(streams: _*)

  /**
   * Like [[unfold]], but allows the emission of values to end one step further
   * the unfolding of the state. This is useful for embedding paginated APIs,
   * hence the name.
   */
  def paginate[A, S](s: S)(f: S => (A, Option[S])): Stream[Nothing, A] =
    StreamEffect.paginate(s)(f)

  /**
   * Like [[unfoldM]], but allows the emission of values to end one step further than
   * the unfolding of the state. This is useful for embedding paginated APIs,
   * hence the name.
   */
  def paginateM[R, E, A, S](s: S)(f: S => ZIO[R, E, (A, Option[S])]): ZStream[R, E, A] =
    ZStream {
      for {
        ref <- Ref.make[Option[S]](Some(s)).toManaged_
      } yield ref.get.flatMap {
        case Some(s) => f(s).foldM(Pull.fail, { case (a, s) => ref.set(s) *> Pull.emit(a) })
        case None    => Pull.end
      }
    }

  /**
   * Constructs a stream from a range of integers (lower bound included, upper bound not included)
   */
  def range(min: Int, max: Int): Stream[Nothing, Int] =
    iterate(min)(_ + 1).takeWhile(_ < max)

  /**
   * Creates a stream from an effect producing a value of type `A` which repeats forever
   */
  def repeatEffect[R, E, A](fa: ZIO[R, E, A]): ZStream[R, E, A] =
    fromEffect(fa).forever

  /**
   * Creates a stream from an effect producing values of type `A` until it fails with None.
   */
  def repeatEffectOption[R, E, A](fa: ZIO[R, Option[E], A]): ZStream[R, E, A] =
    ZStream(ZManaged.succeed(fa))

  /**
   * Creates a stream from an effect producing a value of type `A` which repeats using the specified schedule
   */
  def repeatEffectWith[R, E, A](
    fa: ZIO[R, E, A],
    schedule: Schedule[R, Unit, _]
  ): ZStream[R, E, A] =
    fromEffect(fa).repeat(schedule)

  /**
   * Creates a single-valued pure stream
   */
  def succeed[A](a: A): Stream[Nothing, A] =
    StreamEffect.succeed(a)

  /**
   * Creates a stream by peeling off the "layers" of a value of type `S`
   */
  def unfold[S, A](s: S)(f0: S => Option[(A, S)]): Stream[Nothing, A] =
    StreamEffect.unfold(s)(f0)

  /**
   * Creates a stream by effectfully peeling off the "layers" of a value of type `S`
   */
  def unfoldM[R, E, A, S](s: S)(f0: S => ZIO[R, E, Option[(A, S)]]): ZStream[R, E, A] =
    ZStream {
      for {
        done <- Ref.make(false).toManaged_
        ref  <- Ref.make(s).toManaged_
      } yield done.get.flatMap {
        if (_) Pull.end
        else {
          ref.get
            .flatMap(f0)
            .foldM(
              Pull.fail,
              opt =>
                opt match {
                  case Some((a, s)) => ref.set(s) *> Pull.emit(a)
                  case None         => done.set(true) *> Pull.end
                }
            )
        }
      }
    }

  /**
   * Creates a stream produced from an effect
   */
  def unwrap[R, E, A](fa: ZIO[R, E, ZStream[R, E, A]]): ZStream[R, E, A] =
    flatten(fromEffect(fa))

  /**
   * Creates a stream produced from a [[ZManaged]]
   */
  def unwrapManaged[R, E, A](fa: ZManaged[R, E, ZStream[R, E, A]]): ZStream[R, E, A] =
    flatten(managed(fa))

  /**
   * Zips the specified streams together with the specified function.
   */
  def zipN[R, E, A, B, C](zStream1: ZStream[R, E, A], zStream2: ZStream[R, E, B])(
    f: (A, B) => C
  ): ZStream[R, E, C] =
    zStream1.zipWith(zStream2)((l, r) => l.flatMap(a => r.map(b => f(a, b))))

  /**
   * Zips with specified streams together with the specified function.
   */
  def zipN[R, E, A, B, C, D](zStream1: ZStream[R, E, A], zStream2: ZStream[R, E, B], zStream3: ZStream[R, E, C])(
    f: (A, B, C) => D
  ): ZStream[R, E, D] =
    (zStream1 <&> zStream2 <&> zStream3).map {
      case ((a, b), c) => f(a, b, c)
    }

  /**
   * Returns an effect that executes the specified effects in parallel,
   * combining their results with the specified `f` function. If any effect
   * fails, then the other effects will be interrupted.
   */
  def zipN[R, E, A, B, C, D, F](
    zStream1: ZStream[R, E, A],
    zStream2: ZStream[R, E, B],
    zStream3: ZStream[R, E, C],
    zStream4: ZStream[R, E, D]
  )(f: (A, B, C, D) => F): ZStream[R, E, F] =
    (zStream1 <&> zStream2 <&> zStream3 <&> zStream4).map {
      case (((a, b), c), d) => f(a, b, c, d)
    }

  final class AccessPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: R => A): ZStream[R, Nothing, A] =
      ZStream.environment[R].map(f)
  }

  final class AccessMPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, A](f: R => ZIO[R, E, A]): ZStream[R, E, A] =
      ZStream.environment[R].mapM(f)
  }

  final class AccessStreamPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, A](f: R => ZStream[R, E, A]): ZStream[R, E, A] =
      ZStream.environment[R].flatMap(f)
  }

  private[stream] def exitToInputStreamRead(exit: Exit[Option[Throwable], Byte]): Int = exit match {
    case Exit.Success(value) => value.toInt
    case Exit.Failure(cause) =>
      cause.failureOrCause match {
        case Left(value) =>
          value match {
            case Some(value) => throw value
            case None        => -1
          }
        case Right(value) => throw FiberFailure(value)
      }
  }
}
