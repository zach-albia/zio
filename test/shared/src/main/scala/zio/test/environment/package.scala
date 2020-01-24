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

package zio.test

import java.io.{ EOFException, IOException }
import java.time.{ Instant, OffsetDateTime, ZoneId }
import java.util.concurrent.TimeUnit

import scala.collection.immutable.Queue
import scala.math.{ log, sqrt }

import zio.clock.Clock
import zio.console.Console
import zio.duration._
import zio.random.Random
import zio.scheduler.Scheduler
import zio.system.System
import zio.{ PlatformSpecific => _, _ }

/**
 * The `environment` package contains testable versions of all the standard ZIO
 * environment types through the [[TestClock]], [[TestConsole]],
 * [[TestSystem]], and [[TestRandom]] modules. See the documentation on the
 * individual modules for more detail about using each of them.
 *
 * If you are using ZIO Test and extending `RunnableSpec` a
 * `TestEnvironment` containing all of them will be automatically provided to
 * each of your tests. Otherwise, the easiest way to use the test implementations
 * in ZIO Test is by providing the `TestEnvironment` to your program.
 *
 * {{{
 * import zio.test.environment._
 *
 * myProgram.provideManaged(testEnvironmentManaged)
 * }}}
 *
 * Then all environmental effects, such as printing to the console or
 * generating random numbers, will be implemented by the `TestEnvironment` and
 * will be fully testable. When you do need to access the "live" environment,
 * for example to print debugging information to the console, just use the
 * `live` combinator along with the effect as your normally would.
 *
 * If you are only interested in one of the test implementations for your
 * application, you can also access them a la carte through the `make` method
 * on each module. Each test module requires some data on initialization.
 * Default data is included for each as `DefaultData`.
 *
 * {{{
 * import zio.test.environment._
 *
 * myProgram.provideM(TestConsole.make(TestConsole.DefaultData))
 * }}}
 *
 * Finally, you can create a `Test` object that implements the test interface
 * directly using the `makeTest` method. This can be useful when you want to
 * access some testing functionality without using the environment type.
 *
 * {{{
 * import zio.test.environment._
 *
 * for {
 *   testRandom <- TestRandom.makeTest(TestRandom.DefaultData)
 *   n          <- testRandom.nextInt
 * } yield n
 * }}}
 *
 * This can also be useful when you are creating a more complex environment
 * to provide the implementation for test services that you mix in.
 */
package object environment extends PlatformSpecific {
  type Live        = Has[Live.Service]
  type TestClock   = Has[TestClock.Service]
  type TestConsole = Has[TestConsole.Service]
  type TestRandom  = Has[TestRandom.Service]
  type TestSystem  = Has[TestSystem.Service]

  val liveEnvironment: ZLayer.NoDeps[Nothing, ZEnv] = ZEnv.live

  val testEnvironmentManaged: Managed[Nothing, TestEnvironment] =
    (ZEnv.live >>> TestEnvironment.live).build

  /**
   * Provides an effect with the "real" environment as opposed to the test
   * environment. This is useful for performing effects such as timing out
   * tests, accessing the real time, or printing to the real console.
   */
  def live[E, A](zio: ZIO[ZEnv, E, A]): ZIO[Live, E, A] =
    Live.live(zio)

  /**
   * Transforms this effect with the specified function. The test environment
   * will be provided to this effect, but the live environment will be provided
   * to the transformation function. This can be useful for applying
   * transformations to an effect that require access to the "real" environment
   * while ensuring that the effect itself uses the test environment.
   *
   * {{{
   *  withLive(test)(_.timeout(duration))
   * }}}
   */
  def withLive[R, E, E1, A, B](
    zio: ZIO[R, E, A]
  )(f: IO[E, A] => ZIO[ZEnv, E1, B]): ZIO[R with Live, E1, B] =
    Live.withLive(zio)(f)

  /**
   * The `Live` trait provides access to the "live" environment from within the
   * test environment for effects such as printing test results to the console or
   * timing out tests where it is necessary to access the real environment.
   *
   * The easiest way to access the "live" environment is to use the `live` method
   * with an effect that would otherwise access the test environment.
   *
   * {{{
   * import zio.clock
   * import zio.test.environment._
   *
   * val realTime = live(clock.nanoTime)
   * }}}
   *
   * The `withLive` method can be used to apply a transformation to an effect
   * with the live environment while ensuring that the effect itself still runs
   * with the test environment, for example to time out a test. Both of these
   * methods are re-exported in the `environment` package for easy availability.
   */
  object Live {

    trait Service {
      def provide[E, A](zio: ZIO[ZEnv, E, A]): IO[E, A]
    }

    /**
     * Constructs a new `Live` service that implements the `Live` interface.
     * This typically should not be necessary as `TestEnvironment` provides
     * access to live versions of all the standard ZIO environment types but
     * could be useful if you are mixing in interfaces to create your own
     * environment type.
     */
    def default: ZLayer[ZEnv, Nothing, Live] =
      ZLayer(ZManaged.access[ZEnv] { zenv =>
        Has(new Live.Service {
          def provide[E, A](zio: ZIO[ZEnv, E, A]): IO[E, A] =
            zio.provide(zenv)
        })
      })

    /**
     * Provides an effect with the "live" environment.
     */
    def live[E, A](zio: ZIO[ZEnv, E, A]): ZIO[Live, E, A] =
      ZIO.accessM[Live](_.get.provide(zio))

    /**
     * Provides a transformation function with access to the live environment
     * while ensuring that the effect itself is provided with the test
     * environment.
     */
    def withLive[R <: Live, E, E1, A, B](
      zio: ZIO[R, E, A]
    )(f: IO[E, A] => ZIO[ZEnv, E1, B]): ZIO[R, E1, B] =
      ZIO.environment[R].flatMap(r => live(f(zio.provide(r))))
  }

  /**
   * `TestClock` makes it easy to deterministically and efficiently test effects
   * involving the passage of time.
   *
   * Instead of waiting for actual time to pass, `sleep` and methods implemented
   * in terms of it schedule effects to take place at a given wall clock time.
   * Users can adjust the wall clock time using the `adjust` and `setTime`
   * methods, and all effects scheduled to take place on or before that wall
   * clock time will automically be run.
   *
   * For example, here is how we can test [[ZIO.timeout]] using `TestClock:
   *
   * {{{
   *  import zio.ZIO
   *  import zio.duration._
   *  import zio.test.environment.TestClock
   *
   *  for {
   *    fiber  <- ZIO.sleep(5.minutes).timeout(1.minute).fork
   *    _      <- TestClock.adjust(1.minute)
   *    result <- fiber.join
   *  } yield result == None
   * }}}
   *
   * Note how we forked the fiber that `sleep` was invoked on. Calls to `sleep`
   * and methods derived from it will semantically block until the wall clock
   * time is set to on or after the time they are scheduled to run. If we
   * didn't fork the fiber on which we called sleep we would never get to set
   * the the wall clock time on the line below. Thus, a useful pattern when
   * using `TestClock` is to fork the effect being tested, then adjust the wall
   * clock time, and finally verify that the expected effects have been
   * performed.
   *
   * Sleep and related combinators schedule events to occur at a specified
   * duration in the future relative to the current fiber time (e.g. 10 seconds
   * from the current fiber time). The fiber time is backed by a `FiberRef` and
   * is incremented for the duration each fiber is sleeping. Child fibers inherit
   * the fiber time of their parent so methods that rely on repeated `sleep`
   * calls work as you would expect.
   *
   * For example, here is how we can test an effect that recurs with a fixed
   * delay:
   *
   * {{{
   *  import zio.Queue
   *  import zio.duration._
   *  import zio.test.environment.TestClock
   *
   *  for {
   *    q <- Queue.unbounded[Unit]
   *    _ <- (q.offer(()).delay(60.minutes)).forever.fork
   *    a <- q.poll.map(_.isEmpty)
   *    _ <- TestClock.adjust(60.minutes)
   *    b <- q.take.as(true)
   *    c <- q.poll.map(_.isEmpty)
   *    _ <- TestClock.adjust(60.minutes)
   *    d <- q.take.as(true)
   *    e <- q.poll.map(_.isEmpty)
   *  } yield a && b && c && d && e
   * }}}
   *
   * Here we verify that no effect is performed before the recurrence period,
   * that an effect is performed after the recurrence period, and that the effect
   * is performed exactly once. The key thing to note here is that after each
   * recurrence the next recurrence is scheduled to occur at the appropriate time
   * in the future, so when we adjust the wall clock time by 60 minutes exactly
   * one value is placed in the queue, and when we adjust the wall clock time
   * by another 60 minutes exactly one more value is placed in the queue.
   */
  object TestClock extends Serializable {

    trait Service extends Restorable {
      def adjust(duration: Duration): UIO[Unit]
      def fiberTime: UIO[Duration]
      def setDateTime(dateTime: OffsetDateTime): UIO[Unit]
      def setTime(duration: Duration): UIO[Unit]
      def setTimeZone(zone: ZoneId): UIO[Unit]
      def sleeps: UIO[List[Duration]]
      def timeZone: UIO[ZoneId]
    }

    final case class Test(
      clockState: Ref[TestClock.Data],
      fiberState: FiberRef[TestClock.FiberData],
      live: Live.Service,
      warningState: RefM[TestClock.WarningData]
    ) extends Clock.Service
        with TestClock.Service {

      /**
       * Increments the wall clock time by the specified duration. Any effects
       * that were scheduled to occur on or before the new wall clock time will
       * immediately be run.
       */
      def adjust(duration: Duration): UIO[Unit] =
        warningDone *> clockState.modify { data =>
          val nanoTime        = data.nanoTime + duration.toNanos
          val (wakes, sleeps) = data.sleeps.partition(_._1 <= Duration.fromNanos(nanoTime))
          val updated = data.copy(
            nanoTime = nanoTime,
            sleeps = sleeps
          )
          (wakes, updated)
        }.flatMap(run)

      /**
       * Returns the current fiber time as an `OffsetDateTime`.
       */
      def currentDateTime: UIO[OffsetDateTime] =
        fiberState.get.map(data => toDateTime(data.currentTimeMillis, data.timeZone))

      /**
       * Returns the current fiber time in the specified time unit.
       */
      def currentTime(unit: TimeUnit): UIO[Long] =
        fiberState.get.map(data => unit.convert(data.currentTimeMillis, TimeUnit.MILLISECONDS))

      /**
       * Returns the current fiber time for this fiber. The fiber time is backed
       * by a `FiberRef` and is incremented for the duration each fiber is
       * sleeping. When a fiber is joined the fiber time will be set to the
       * maximum of the fiber time of the parent and child fibers. Thus, the
       * fiber time reflects the duration of sleeping that has occurred for this
       * fiber to reach its current state, properly reflecting forks and joins.
       *
       * {{{
       * for {
       *   _      <- TestClock.set(Duration.Infinity)
       *   _      <- ZIO.sleep(2.millis).zipPar(ZIO.sleep(1.millis))
       *   result <- TestClock.fiberTime
       * } yield result.toNanos == 2000000L
       * }}}
       */
      val fiberTime: UIO[Duration] =
        fiberState.get.map(_.nanoTime.nanos)

      /**
       * Returns the current fiber time in nanoseconds.
       */
      val nanoTime: UIO[Long] =
        fiberState.get.map(_.nanoTime)

      /**
       * Saves the `TestClock`'s current state in an effect which, when run,
       * will restore the `TestClock` state to the saved state
       */
      val save: UIO[UIO[Unit]] =
        for {
          fState <- fiberState.get
          cState <- clockState.get
          wState <- warningState.get
        } yield {
          fiberState.set(fState) *>
            clockState.set(cState) *>
            warningState.set(wState)
        }

      /**
       * Returns an effect that creates a new `Scheduler` backed by this
       * `TestClock`.
       */
      val scheduler: UIO[Scheduler.Service] =
        ZIO.succeed {
          new Scheduler.Service {
            override def schedule[R, E, A](task: ZIO[R, E, A], duration: Duration): ZIO[R, E, A] =
              sleep(duration) *> task
          }
        }

      /**
       * Sets the wall clock time to the specified `OffsetDateTime`. Any
       * effects that were scheduled to occur on or before the new time will
       * immediately be run.
       */
      def setDateTime(dateTime: OffsetDateTime): UIO[Unit] =
        setTime(fromDateTime(dateTime))

      /**
       * Sets the wall clock time to the specified time in terms of duration
       * since the epoch. Any effects that were scheduled to occur on or before
       * the new time will immediately be run.
       */
      def setTime(duration: Duration): UIO[Unit] =
        warningDone *> clockState.modify { data =>
          val (wakes, sleeps) = data.sleeps.partition(_._1 <= duration)
          val updated = data.copy(
            nanoTime = duration.toNanos,
            sleeps = sleeps
          )
          (wakes, updated)
        }.flatMap(run)

      /**
       * Sets the time zone to the specified time zone. The wall clock time in
       * terms of nanoseconds since the epoch will not be adjusted and no
       * scheduled effects will be run as a result of this method.
       */
      def setTimeZone(zone: ZoneId): UIO[Unit] =
        fiberState.update(_.copy(timeZone = zone)).unit

      /**
       * Semantically blocks the current fiber until the wall clock time is
       * equal to or greater than the specified duration. Once the wall clock
       * time is adjusted to on or after the duration, the fiber will
       * automatically be resumed.
       */
      def sleep(duration: Duration): UIO[Unit] =
        for {
          latch <- Promise.make[Nothing, Unit]
          start <- fiberState.modify { data =>
                    val nanoTime          = data.nanoTime + duration.toNanos
                    val currentTimeMillis = data.currentTimeMillis + duration.toMillis
                    (data.nanoTime, FiberData(nanoTime, currentTimeMillis, data.timeZone))
                  }
          await <- clockState.modify { data =>
                    val end = Duration.fromNanos(start) + duration
                    if (end > Duration.fromNanos(data.nanoTime))
                      (true, data.copy(sleeps = (end, latch) :: data.sleeps))
                    else
                      (false, data)
                  }
          _ <- if (await) warningStart *> latch.await else latch.succeed(())
        } yield ()

      /**
       * Returns a list of the wall clock times at which all queued effects are
       * scheduled to resume.
       */
      val sleeps: UIO[List[Duration]] = clockState.get.map(_.sleeps.map(_._1))

      /**
       * Returns the time zone.
       */
      val timeZone: UIO[ZoneId] =
        fiberState.get.map(_.timeZone)

      private def run(wakes: List[(Duration, Promise[Nothing, Unit])]): UIO[Unit] =
        UIO.forkAll_(wakes.sortBy(_._1).map(_._2.succeed(()))).fork.unit

      private[TestClock] val warningDone: UIO[Unit] =
        warningState
          .updateSome[Any, Nothing] {
            case WarningData.Start          => ZIO.succeed(WarningData.done)
            case WarningData.Pending(fiber) => fiber.interrupt.as(WarningData.done)
          }
          .unit

      private val warningStart: UIO[Unit] =
        warningState.updateSome {
          case WarningData.Start =>
            for {
              fiber <- live.provide(console.putStrLn(warning).delay(5.seconds)).interruptible.fork
            } yield WarningData.pending(fiber)
        }.unit

    }

    /**
     * Accesses a `TestClock` instance in the environment and increments the
     * wall clock time by the specified duration, running any actions scheduled
     * for on or before the new time.
     */
    def adjust(duration: Duration): ZIO[TestClock, Nothing, Unit] =
      ZIO.accessM(_.get.adjust(duration))

    /**
     * Accesses a `TestClock` instance in the environment and returns the current
     * fiber time for this fiber.
     */
    val fiberTime: ZIO[TestClock, Nothing, Duration] =
      ZIO.accessM(_.get.fiberTime)

    /**
     * Constructs a new `Test` object that implements the `TestClock` interface.
     * This can be useful for mixing in with implementations of other interfaces.
     */
    def live(data: Data): ZLayer[Live, Nothing, Clock with TestClock with Scheduler] =
      ZLayer.fromServiceManaged { (live: Live.Service) =>
        for {
          ref       <- Ref.make(data).toManaged_
          fiberRef  <- FiberRef.make(FiberData(0, 0, ZoneId.of("UTC")), FiberData.combine).toManaged_
          refM      <- RefM.make(WarningData.start).toManaged_
          test      <- Managed.make(UIO(Test(ref, fiberRef, live, refM)))(_.warningDone)
          scheduler <- test.scheduler.toManaged_
        } yield Has.allOf[Clock.Service, TestClock.Service, Scheduler.Service](test, test, scheduler)
      }

    val any: ZLayer[Clock with TestClock with Scheduler, Nothing, Clock with TestClock with Scheduler] =
      ZLayer.requires[Clock with TestClock with Scheduler]

    val default: ZLayer[Live, Nothing, Clock with TestClock with Scheduler] =
      live(Data(0, Nil))

    /**
     * Accesses a `TestClock` instance in the environment and saves the clock
     * state in an effect which, when run, will restore the `TestClock` to the
     * saved state
     */
    val save: ZIO[TestClock, Nothing, UIO[Unit]] = ZIO.accessM[TestClock](_.get.save)

    /**
     * Accesses a `TestClock` instance in the environment and sets the wall
     * clock time to the specified `OffsetDateTime`, running any actions
     * scheduled for on or before the new time.
     */
    def setDateTime(dateTime: OffsetDateTime): ZIO[TestClock, Nothing, Unit] =
      ZIO.accessM(_.get.setDateTime(dateTime))

    /**
     * Accesses a `TestClock` instance in the environment and sets the wall
     * clock time to the specified time in terms of duration since the epoch,
     * running any actions scheduled for on or before the new time.
     */
    def setTime(duration: Duration): ZIO[TestClock, Nothing, Unit] =
      ZIO.accessM(_.get.setTime(duration))

    /**
     * Accesses a `TestClock` instance in the environment, setting the time zone
     * to the specified time zone. The wall clock time in terms of nanoseconds
     * since the epoch will not be altered and no scheduled actions will be run
     * as a result of this effect.
     */
    def setTimeZone(zone: ZoneId): ZIO[TestClock, Nothing, Unit] =
      ZIO.accessM(_.get.setTimeZone(zone))

    /**
     * Accesses a `TestClock` instance in the environment and returns a list of
     * wall clock times that effects are scheduled to run.
     */
    val sleeps: ZIO[TestClock, Nothing, List[Duration]] =
      ZIO.accessM(_.get.sleeps)

    /**
     * Accesses a `TestClock` instance in the environment and returns the current
     * time zone.
     */
    val timeZone: ZIO[TestClock, Nothing, ZoneId] =
      ZIO.accessM(_.get.timeZone)

    /**
     * The state of the `TestClock`.
     */
    final case class Data(nanoTime: Long, sleeps: List[(Duration, Promise[Nothing, Unit])])

    final case class FiberData(nanoTime: Long, currentTimeMillis: Long, timeZone: ZoneId)

    object FiberData {
      def combine(first: FiberData, last: FiberData): FiberData =
        FiberData(
          first.nanoTime max last.nanoTime,
          first.currentTimeMillis max last.currentTimeMillis,
          last.timeZone
        )
    }

    sealed trait WarningData

    object WarningData {
      case object Start                                     extends WarningData
      final case class Pending(fiber: Fiber[Nothing, Unit]) extends WarningData
      case object Done                                      extends WarningData

      val start: WarningData                                = Start
      def pending(fiber: Fiber[Nothing, Unit]): WarningData = Pending(fiber)
      val done: WarningData                                 = Done
    }

    private def toDateTime(millis: Long, timeZone: ZoneId): OffsetDateTime =
      OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), timeZone)

    private def fromDateTime(dateTime: OffsetDateTime): Duration =
      Duration(dateTime.toInstant.toEpochMilli, TimeUnit.MILLISECONDS)

    private val warning =
      "Warning: A test is using time, but is not advancing the test clock, " +
        "which may result in the test hanging. Use TestClock.adjust to " +
        "manually advance the time."
  }

  /**
   * `TestConsole` provides a testable interface for programs interacting with
   * the console by modeling input and output as reading from and writing to
   * input and output buffers maintained by `TestConsole` and backed by a `Ref`.
   *
   * All calls to `putStr` and `putStrLn` using the `TestConsole` will write the
   * string to the output buffer and all calls to `getStrLn` will take a string
   * from the input buffer. No actual printing or reading from the console will
   * occur. `TestConsole` has several methods to access and manipulate the
   * content of these buffers including `feedLines` to feed strings to the input
   * buffer that will then be returned by calls to `getStrLn`, `output` to get
   * the content of the output buffer from calls to `putStr` and `putStrLn`, and
   * `clearInput` and `clearOutput` to clear the respective buffers.
   *
   * Together, these functions make it easy to test programs interacting with the
   * console.
   *
   * {{{
   * import zio.console._
   * import zio.test.environment.TestConsole
   * import zio.ZIO
   *
   * val sayHello = for {
   *   name <- getStrLn
   *   _    <- putStrLn("Hello, " + name + "!")
   * } yield ()
   *
   * for {
   *   _ <- TestConsole.feedLines("John", "Jane", "Sally")
   *   _ <- ZIO.collectAll(List.fill(3)(sayHello))
   *   result <- TestConsole.output
   * } yield result == Vector("Hello, John!\n", "Hello, Jane!\n", "Hello, Sally!\n")
   * }}}
   */
  object TestConsole extends Serializable {

    trait Service extends Restorable {
      def feedLines(lines: String*): UIO[Unit]
      def output: UIO[Vector[String]]
      def clearInput: UIO[Unit]
      def clearOutput: UIO[Unit]
    }

    case class Test(consoleState: Ref[TestConsole.Data]) extends Console.Service with TestConsole.Service {

      /**
       * Clears the contents of the input buffer.
       */
      val clearInput: UIO[Unit] =
        consoleState.update(data => data.copy(input = List.empty)).unit

      /**
       * Clears the contents of the output buffer.
       */
      val clearOutput: UIO[Unit] =
        consoleState.update(data => data.copy(output = Vector.empty)).unit

      /**
       * Writes the specified sequence of strings to the input buffer. The
       * first string in the sequence will be the first to be taken. These
       * strings will be taken before any strings that were previously in the
       * input buffer.
       */
      def feedLines(lines: String*): UIO[Unit] =
        consoleState.update(data => data.copy(input = lines.toList ::: data.input)).unit

      /**
       * Takes the first value from the input buffer, if one exists, or else
       * fails with an `EOFException`.
       */
      val getStrLn: ZIO[Any, IOException, String] = {
        for {
          input <- consoleState.get.flatMap(
                    d =>
                      IO.fromOption(d.input.headOption)
                        .mapError(_ => new EOFException("There is no more input left to read"))
                  )
          _ <- consoleState.update { data =>
                Data(data.input.tail, data.output)
              }
        } yield input
      }

      /**
       * Returns the contents of the output buffer. The first value written to
       * the output buffer will be the first in the sequence.
       */
      val output: UIO[Vector[String]] =
        consoleState.get.map(_.output)

      /**
       * Writes the specified string to the output buffer.
       */
      override def putStr(line: String): UIO[Unit] =
        consoleState.update { data =>
          Data(data.input, data.output :+ line)
        }.unit

      /**
       * Writes the specified string to the output buffer followed by a newline
       * character.
       */
      override def putStrLn(line: String): ZIO[Any, Nothing, Unit] =
        consoleState.update { data =>
          Data(data.input, data.output :+ s"$line\n")
        }.unit

      /**
       * Saves the `TestConsole`'s current state in an effect which, when run, will restore the `TestConsole`
       * state to the saved state
       */
      val save: UIO[UIO[Unit]] =
        for {
          cState <- consoleState.get
        } yield consoleState.set(cState)
    }

    /**
     * Constructs a new `Test` object that implements the `TestConsole`
     * interface. This can be useful for mixing in with implementations of other
     * interfaces.
     */
    def live(data: Data): ZLayer.NoDeps[Nothing, Console with TestConsole] =
      ZLayer.fromEffect(
        Ref.make(data).map(ref => Has.allOf[Console.Service, TestConsole.Service](Test(ref), Test(ref)))
      )

    val any: ZLayer[Console with TestConsole, Nothing, Console with TestConsole] =
      ZLayer.requires[Console with TestConsole]

    val default: ZLayer.NoDeps[Nothing, Console with TestConsole] =
      live(Data(Nil, Vector()))

    /**
     * Accesses a `TestConsole` instance in the environment and writes the
     * specified sequence of strings to the input buffer.
     */
    def feedLines(lines: String*): ZIO[TestConsole, Nothing, Unit] =
      ZIO.accessM(_.get.feedLines(lines: _*))

    /**
     * Accesses a `TestConsole` instance in the environment and returns the
     * contents of the output buffer.
     */
    val output: ZIO[TestConsole, Nothing, Vector[String]] =
      ZIO.accessM(_.get.output)

    /**
     * Accesses a `TestConsole` instance in the environment and clears the input
     * buffer.
     */
    val clearInput: ZIO[TestConsole, Nothing, Unit] =
      ZIO.accessM(_.get.clearInput)

    /**
     * Accesses a `TestConsole` instance in the environment and clears the output
     * buffer.
     */
    val clearOutput: ZIO[TestConsole, Nothing, Unit] =
      ZIO.accessM(_.get.clearOutput)

    /**
     * Accesses a `TestConsole` instance in the environment and saves the console state in an effect which, when run,
     * will restore the `TestConsole` to the saved state
     */
    val save: ZIO[TestConsole, Nothing, UIO[Unit]] = ZIO.accessM[TestConsole](_.get.save)

    /**
     * The state of the `TestConsole`.
     */
    final case class Data(input: List[String] = List.empty, output: Vector[String] = Vector.empty)
  }

  /**
   * `TestRandom` allows for deterministically testing effects involving
   * randomness.
   *
   * `TestRandom` operates in two modes. In the first mode, `TestRandom` is a
   * purely functional pseudo-random number generator. It will generate
   * pseudo-random values just like `scala.util.Random` except that no internal
   * state is mutated. Instead, methods like `nextInt` describe state transitions
   * from one random state to another that are automatically composed together
   * through methods like `flatMap`. The random seed can be set using `setSeed`
   * and `TestRandom` is guaranteed to return the same sequence of values for any
   * given seed. This is useful for deterministically generating a sequence of
   * pseudo-random values and powers the property based testing functionality in
   * ZIO Test.
   *
   * In the second mode, `TestRandom` maintains an internal buffer of values that
   * can be "fed" with methods such as `feedInts` and then when random values of
   * that type are generated they will first be taken from the buffer. This is
   * useful for verifying that functions produce the expected output for a given
   * sequence of "random" inputs.
   *
   * {{{
   * import zio.random._
   * import zio.test.environment.TestRandom
   *
   * for {
   *   _ <- TestRandom.feedInts(4, 5, 2)
   *   x <- random.nextInt(6)
   *   y <- random.nextInt(6)
   *   z <- random.nextInt(6)
   * } yield x + y + z == 11
   * }}}
   *
   * `TestRandom` will automatically take values from the buffer if a value of
   * the appropriate type is available and otherwise generate a pseudo-random
   * value, so there is nothing you need to do to switch between the two modes.
   * Just generate random values as you normally would to get pseudo-random
   * values, or feed in values of your own to get those values back. You can also
   * use methods like `clearInts` to clear the buffer of values of a given type
   * so you can fill the buffer with new values or go back to pseudo-random
   * number generation.
   */
  object TestRandom extends Serializable {

    trait Service extends Restorable {
      def clearBooleans: UIO[Unit]
      def clearBytes: UIO[Unit]
      def clearChars: UIO[Unit]
      def clearDoubles: UIO[Unit]
      def clearFloats: UIO[Unit]
      def clearInts: UIO[Unit]
      def clearLongs: UIO[Unit]
      def clearStrings: UIO[Unit]
      def feedBooleans(booleans: Boolean*): UIO[Unit]
      def feedBytes(bytes: Chunk[Byte]*): UIO[Unit]
      def feedChars(chars: Char*): UIO[Unit]
      def feedDoubles(doubles: Double*): UIO[Unit]
      def feedFloats(floats: Float*): UIO[Unit]
      def feedInts(ints: Int*): UIO[Unit]
      def feedLongs(longs: Long*): UIO[Unit]
      def feedStrings(strings: String*): UIO[Unit]
      def setSeed(seed: Long): UIO[Unit]
    }

    /**
     * Adapted from @gzmo work in Scala.js (https://github.com/scala-js/scala-js/pull/780)
     */
    final case class Test(randomState: Ref[Data], bufferState: Ref[Buffer])
        extends Random.Service
        with TestRandom.Service {

      /**
       * Clears the buffer of booleans.
       */
      val clearBooleans: UIO[Unit] =
        bufferState.update(_.copy(booleans = List.empty)).unit

      /**
       * Clears the buffer of bytes.
       */
      val clearBytes: UIO[Unit] =
        bufferState.update(_.copy(bytes = List.empty)).unit

      /**
       * Clears the buffer of characters.
       */
      val clearChars: UIO[Unit] =
        bufferState.update(_.copy(chars = List.empty)).unit

      /**
       * Clears the buffer of doubles.
       */
      val clearDoubles: UIO[Unit] =
        bufferState.update(_.copy(doubles = List.empty)).unit

      /**
       * Clears the buffer of floats.
       */
      val clearFloats: UIO[Unit] =
        bufferState.update(_.copy(floats = List.empty)).unit

      /**
       * Clears the buffer of integers.
       */
      val clearInts: UIO[Unit] =
        bufferState.update(_.copy(integers = List.empty)).unit

      /**
       * Clears the buffer of longs.
       */
      val clearLongs: UIO[Unit] =
        bufferState.update(_.copy(longs = List.empty)).unit

      /**
       * Clears the buffer of strings.
       */
      val clearStrings: UIO[Unit] =
        bufferState.update(_.copy(strings = List.empty)).unit

      /**
       * Feeds the buffer with specified sequence of booleans. The first value in
       * the sequence will be the first to be taken. These values will be taken
       * before any values that were previously in the buffer.
       */
      def feedBooleans(booleans: Boolean*): UIO[Unit] =
        bufferState.update(data => data.copy(booleans = booleans.toList ::: data.booleans)).unit

      /**
       * Feeds the buffer with specified sequence of chunks of bytes. The first
       * value in the sequence will be the first to be taken. These values will
       * be taken before any values that were previously in the buffer.
       */
      def feedBytes(bytes: Chunk[Byte]*): UIO[Unit] =
        bufferState.update(data => data.copy(bytes = bytes.toList ::: data.bytes)).unit

      /**
       * Feeds the buffer with specified sequence of characters. The first value
       * in the sequence will be the first to be taken. These values will be
       * taken before any values that were previously in the buffer.
       */
      def feedChars(chars: Char*): UIO[Unit] =
        bufferState.update(data => data.copy(chars = chars.toList ::: data.chars)).unit

      /**
       * Feeds the buffer with specified sequence of doubles. The first value in
       * the sequence will be the first to be taken. These values will be taken
       * before any values that were previously in the buffer.
       */
      def feedDoubles(doubles: Double*): UIO[Unit] =
        bufferState.update(data => data.copy(doubles = doubles.toList ::: data.doubles)).unit

      /**
       * Feeds the buffer with specified sequence of floats. The first value in
       * the sequence will be the first to be taken. These values will be taken
       * before any values that were previously in the buffer.
       */
      def feedFloats(floats: Float*): UIO[Unit] =
        bufferState.update(data => data.copy(floats = floats.toList ::: data.floats)).unit

      /**
       * Feeds the buffer with specified sequence of integers. The first value in
       * the sequence will be the first to be taken. These values will be taken
       * before any values that were previously in the buffer.
       */
      def feedInts(ints: Int*): UIO[Unit] =
        bufferState.update(data => data.copy(integers = ints.toList ::: data.integers)).unit

      /**
       * Feeds the buffer with specified sequence of longs. The first value in
       * the sequence will be the first to be taken. These values will be taken
       * before any values that were previously in the buffer.
       */
      def feedLongs(longs: Long*): UIO[Unit] =
        bufferState.update(data => data.copy(longs = longs.toList ::: data.longs)).unit

      /**
       * Feeds the buffer with specified sequence of strings. The first value in
       * the sequence will be the first to be taken. These values will be taken
       * before any values that were previously in the buffer.
       */
      def feedStrings(strings: String*): UIO[Unit] =
        bufferState.update(data => data.copy(strings = strings.toList ::: data.strings)).unit

      private val randomBoolean: UIO[Boolean] =
        randomBits(1).map(_ != 0)

      private def randomBytes(length: Int): UIO[Chunk[Byte]] = {
        //  Our RNG generates 32 bit integers so to maximize efficiency we want to
        //  pull 8 bit bytes from the current integer until it is exhausted
        //  before generating another random integer
        def loop(i: Int, rnd: UIO[Int], n: Int, acc: UIO[List[Byte]]): UIO[List[Byte]] =
          if (i == length)
            acc.map(_.reverse)
          else if (n > 0)
            rnd.flatMap(rnd => loop(i + 1, UIO.succeed(rnd >> 8), n - 1, acc.map(rnd.toByte :: _)))
          else
            loop(i, nextInt, (length - i) min 4, acc)

        loop(0, randomInt, length min 4, UIO.succeed(List.empty[Byte])).map(Chunk.fromIterable)
      }

      private val randomDouble: UIO[Double] =
        for {
          i1 <- randomBits(26)
          i2 <- randomBits(27)
        } yield ((i1.toDouble * (1L << 27).toDouble) + i2.toDouble) / (1L << 53).toDouble

      private val randomFloat: UIO[Float] =
        randomBits(24).map(i => (i.toDouble / (1 << 24).toDouble).toFloat)

      private val randomGaussian: UIO[Double] =
        //  The Box-Muller transform generates two normally distributed random
        //  doubles, so we store the second double in a queue and check the
        //  queue before computing a new pair of values to avoid wasted work.
        randomState.modify {
          case Data(seed1, seed2, queue) =>
            queue.dequeueOption.fold { (Option.empty[Double], Data(seed1, seed2, queue)) } {
              case (d, queue) => (Some(d), Data(seed1, seed2, queue))
            }
        }.flatMap {
          case Some(nextNextGaussian) => UIO.succeed(nextNextGaussian)
          case None =>
            def loop: UIO[(Double, Double, Double)] =
              randomDouble.zip(randomDouble).flatMap {
                case (d1, d2) =>
                  val x      = 2 * d1 - 1
                  val y      = 2 * d2 - 1
                  val radius = x * x + y * y
                  if (radius >= 1 || radius == 0) loop else UIO.succeed((x, y, radius))
              }
            loop.flatMap {
              case (x, y, radius) =>
                val c = sqrt(-2 * log(radius) / radius)
                randomState.modify {
                  case Data(seed1, seed2, queue) =>
                    (x * c, Data(seed1, seed2, queue.enqueue(y * c)))
                }
            }
        }

      private val randomInt: UIO[Int] =
        randomBits(32)

      private def randomInt(n: Int): UIO[Int] =
        if (n <= 0)
          UIO.die(new IllegalArgumentException("n must be positive"))
        else if ((n & -n) == n)
          randomBits(31).map(_ >> Integer.numberOfLeadingZeros(n))
        else {
          def loop: UIO[Int] =
            randomBits(31).flatMap { i =>
              val value = i % n
              if (i - value + (n - 1) < 0) loop
              else UIO.succeed(value)
            }
          loop
        }

      private val randomLong: UIO[Long] =
        for {
          i1 <- randomBits(32)
          i2 <- randomBits(32)
        } yield ((i1.toLong << 32) + i2)

      private def randomLong(n: Long): UIO[Long] =
        Random.nextLongWith(randomLong, n)

      private val randomPrintableChar: UIO[Char] =
        randomInt(127 - 33).map(i => (i + 33).toChar)

      private def randomString(length: Int): UIO[String] = {
        val safeChar = randomInt(0xD800 - 1).map(i => (i + 1).toChar)
        UIO.collectAll(List.fill(length)(safeChar)).map(_.mkString)
      }

      /**
       * Takes a boolean from the buffer if one exists or else generates a
       * pseudo-random boolean.
       */
      val nextBoolean: UIO[Boolean] =
        getOrElse(bufferedBoolean)(randomBoolean)

      /**
       * Takes a chunk of bytes from the buffer if one exists or else generates a
       * pseudo-random chunk of bytes of the specified length.
       */
      def nextBytes(length: Int): UIO[Chunk[Byte]] =
        getOrElse(bufferedBytes)(randomBytes(length))

      /**
       * Takes a double from the buffer if one exists or else generates a
       * pseudo-random, uniformly distributed double between 0.0 and 1.0.
       */
      val nextDouble: UIO[Double] =
        getOrElse(bufferedDouble)(randomDouble)

      /**
       * Takes a float from the buffer if one exists or else generates a
       * pseudo-random, uniformly distributed float between 0.0 and 1.0.
       */
      val nextFloat: UIO[Float] =
        getOrElse(bufferedFloat)(randomFloat)

      /**
       * Takes a double from the buffer if one exists or else generates a
       * pseudo-random double from a normal distribution with mean 0.0 and
       * standard deviation 1.0.
       */
      val nextGaussian: UIO[Double] =
        getOrElse(bufferedDouble)(randomGaussian)

      /**
       * Takes an integer from the buffer if one exists or else generates a
       * pseudo-random integer.
       */
      val nextInt: UIO[Int] =
        getOrElse(bufferedInt)(randomInt)

      /**
       * Takes an integer from the buffer if one exists or else generates a
       * pseudo-random integer between 0 (inclusive) and the specified value
       * (exclusive).
       */
      def nextInt(n: Int): UIO[Int] =
        getOrElse(bufferedInt)(randomInt(n))

      /**
       * Takes a long from the buffer if one exists or else generates a
       * pseudo-random long.
       */
      val nextLong: UIO[Long] =
        getOrElse(bufferedLong)(randomLong)

      /**
       * Takes a long from the buffer if one exists or else generates a
       * pseudo-random long between 0 (inclusive) and the specified value
       * (exclusive).
       */
      def nextLong(n: Long): UIO[Long] =
        getOrElse(bufferedLong)(randomLong(n))

      /**
       * Takes a character from the buffer if one exists or else generates a
       * pseudo-random character from the ASCII range 33-126.
       */
      val nextPrintableChar: UIO[Char] =
        getOrElse(bufferedChar)(randomPrintableChar)

      /**
       * Takes a string from the buffer if one exists or else generates a
       * pseudo-random string of the specified length.
       */
      def nextString(length: Int): UIO[String] =
        getOrElse(bufferedString)(randomString(length))

      /**
       * Saves the `TestRandom`'s current state in an effect which, when run, will restore the `TestRandom`
       * state to the saved state
       */
      val save: UIO[UIO[Unit]] =
        for {
          rState <- randomState.get
          bState <- bufferState.get
        } yield {
          randomState.set(rState) *>
            bufferState.set(bState)
        }

      /**
       * Sets the seed of this `TestRandom` to the specified value.
       */
      def setSeed(seed: Long): UIO[Unit] =
        randomState.set {
          val newSeed = (seed ^ 0X5DEECE66DL) & ((1L << 48) - 1)
          val seed1   = (newSeed >>> 24).toInt
          val seed2   = newSeed.toInt & ((1 << 24) - 1)
          Data(seed1, seed2, Queue.empty)
        }

      /**
       * Randomly shuffles the specified list.
       */
      def shuffle[A](list: List[A]): UIO[List[A]] =
        Random.shuffleWith(randomInt, list)

      private def bufferedBoolean(buffer: Buffer): (Option[Boolean], Buffer) =
        (
          buffer.booleans.headOption,
          buffer.copy(booleans = buffer.booleans.drop(1))
        )

      private def bufferedBytes(buffer: Buffer): (Option[Chunk[Byte]], Buffer) =
        (
          buffer.bytes.headOption,
          buffer.copy(bytes = buffer.bytes.drop(1))
        )

      private def bufferedChar(buffer: Buffer): (Option[Char], Buffer) =
        (
          buffer.chars.headOption,
          buffer.copy(chars = buffer.chars.drop(1))
        )

      private def bufferedDouble(buffer: Buffer): (Option[Double], Buffer) =
        (
          buffer.doubles.headOption,
          buffer.copy(doubles = buffer.doubles.drop(1))
        )

      private def bufferedFloat(buffer: Buffer): (Option[Float], Buffer) =
        (
          buffer.floats.headOption,
          buffer.copy(floats = buffer.floats.drop(1))
        )

      private def bufferedInt(buffer: Buffer): (Option[Int], Buffer) =
        (
          buffer.integers.headOption,
          buffer.copy(integers = buffer.integers.drop(1))
        )

      private def bufferedLong(buffer: Buffer): (Option[Long], Buffer) =
        (
          buffer.longs.headOption,
          buffer.copy(longs = buffer.longs.drop(1))
        )

      private def bufferedString(buffer: Buffer): (Option[String], Buffer) =
        (
          buffer.strings.headOption,
          buffer.copy(strings = buffer.strings.drop(1))
        )

      private def getOrElse[A](buffer: Buffer => (Option[A], Buffer))(random: UIO[A]): UIO[A] =
        bufferState.modify(buffer).flatMap(_.fold(random)(UIO.succeed))

      @inline
      private def leastSignificantBits(x: Double): Int =
        toInt(x) & ((1 << 24) - 1)

      @inline
      private def mostSignificantBits(x: Double): Int =
        toInt((x / (1 << 24).toDouble))

      private def randomBits(bits: Int): UIO[Int] =
        randomState.modify { data =>
          val multiplier  = 0X5DEECE66DL
          val multiplier1 = (multiplier >>> 24).toInt
          val multiplier2 = multiplier.toInt & ((1 << 24) - 1)
          val product1    = data.seed2.toDouble * multiplier1.toDouble + data.seed1.toDouble * multiplier2.toDouble
          val product2    = data.seed2.toDouble * multiplier2.toDouble + 0xB
          val newSeed1    = (mostSignificantBits(product2) + leastSignificantBits(product1)) & ((1 << 24) - 1)
          val newSeed2    = leastSignificantBits(product2)
          val result      = (newSeed1 << 8) | (newSeed2 >> 16)
          (result >>> (32 - bits), Data(newSeed1, newSeed2, data.nextNextGaussians))
        }

      @inline
      private def toInt(x: Double): Int =
        (x.asInstanceOf[Long] | 0.asInstanceOf[Long]).asInstanceOf[Int]
    }

    /**
     * An arbitrary initial seed for the `TestRandom`.
     */
    val DefaultData: Data = Data(1071905196, 1911589680)

    /**
     * The seed of the `TestRandom`.
     */
    final case class Data(
      seed1: Int,
      seed2: Int,
      private[TestRandom] val nextNextGaussians: Queue[Double] = Queue.empty
    )

    /**
     * Accesses a `TestRandom` instance in the environment and clears the buffer
     * of booleans.
     */
    val clearBooleans: ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.clearBooleans)

    /**
     * Accesses a `TestRandom` instance in the environment and clears the buffer
     * of bytes.
     */
    val clearBytes: ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.clearBytes)

    /**
     * Accesses a `TestRandom` instance in the environment and clears the buffer
     * of characters.
     */
    val clearChars: ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.clearChars)

    /**
     * Accesses a `TestRandom` instance in the environment and clears the buffer
     * of doubles.
     */
    val clearDoubles: ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.clearDoubles)

    /**
     * Accesses a `TestRandom` instance in the environment and clears the buffer
     * of floats.
     */
    val clearFloats: ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.clearFloats)

    /**
     * Accesses a `TestRandom` instance in the environment and clears the buffer
     * of integers.
     */
    val clearInts: ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.clearInts)

    /**
     * Accesses a `TestRandom` instance in the environment and clears the buffer
     * of longs.
     */
    val clearLongs: ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.clearLongs)

    /**
     * Accesses a `TestRandom` instance in the environment and clears the buffer
     * of strings.
     */
    val clearStrings: ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.clearStrings)

    /**
     * Accesses a `TestRandom` instance in the environment and feeds the buffer
     * with the specified sequence of booleans.
     */
    def feedBooleans(booleans: Boolean*): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.feedBooleans(booleans: _*))

    /**
     * Accesses a `TestRandom` instance in the environment and feeds the buffer
     * with the specified sequence of chunks of bytes.
     */
    def feedBytes(bytes: Chunk[Byte]*): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.feedBytes(bytes: _*))

    /**
     * Accesses a `TestRandom` instance in the environment and feeds the buffer
     * with the specified sequence of characters.
     */
    def feedChars(chars: Char*): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.feedChars(chars: _*))

    /**
     * Accesses a `TestRandom` instance in the environment and feeds the buffer
     * with the specified sequence of doubles.
     */
    def feedDoubles(doubles: Double*): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.feedDoubles(doubles: _*))

    /**
     * Accesses a `TestRandom` instance in the environment and feeds the buffer
     * with the specified sequence of floats.
     */
    def feedFloats(floats: Float*): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.feedFloats(floats: _*))

    /**
     * Accesses a `TestRandom` instance in the environment and feeds the buffer
     * with the specified sequence of integers.
     */
    def feedInts(ints: Int*): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.feedInts(ints: _*))

    /**
     * Accesses a `TestRandom` instance in the environment and feeds the buffer
     * with the specified sequence of longs.
     */
    def feedLongs(longs: Long*): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.feedLongs(longs: _*))

    /**
     * Accesses a `TestRandom` instance in the environment and feeds the buffer
     * with the specified sequence of strings.
     */
    def feedStrings(strings: String*): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.feedStrings(strings: _*))

    /**
     * Constructs a new `TestRandom` with the specified initial state. This can
     * be useful for providing the required environment to an effect that
     * requires a `Random`, such as with [[ZIO!.provide]].
     */
    def make(data: Data): ZLayer.NoDeps[Nothing, Random with TestRandom] =
      ZLayer.fromEffect(for {
        data   <- Ref.make(data)
        buffer <- Ref.make(Buffer())
        test   = Test(data, buffer)
      } yield Has.allOf[Random.Service, TestRandom.Service](test, test))

    val any: ZLayer[Random with TestRandom, Nothing, Random with TestRandom] =
      ZLayer.requires[Random with TestRandom]

    val deterministic: ZLayer.NoDeps[Nothing, Random with TestRandom] =
      make(DefaultData)

    val random: ZLayer[Clock, Nothing, Random with TestRandom] =
      (ZLayer.service[Clock.Service] ++ deterministic) >>>
        (ZLayer.fromEnvironmentM { (env: Clock with Random with TestRandom) =>
          val random     = env.get[Random.Service]
          val testRandom = env.get[TestRandom.Service]

          for {
            time <- env.get[Clock.Service].nanoTime
            _    <- env.get[TestRandom.Service].setSeed(time)
          } yield Has.allOf[Random.Service, TestRandom.Service](random, testRandom)
        })

    /**
     * Constructs a new `Test` object that implements the `TestRandom` interface.
     * This can be useful for mixing in with implementations of other interfaces.
     */
    def makeTest(data: Data): UIO[Test] =
      for {
        data   <- Ref.make(data)
        buffer <- Ref.make(Buffer())
      } yield Test(data, buffer)

    /**
     * Accesses a `TestRandom` instance in the environment and saves the random state in an effect which, when run,
     * will restore the `TestRandom` to the saved state
     */
    val save: ZIO[TestRandom, Nothing, UIO[Unit]] = ZIO.accessM[TestRandom](_.get.save)

    /**
     * Accesses a `TestRandom` instance in the environment and sets the seed to
     * the specified value.
     */
    def setSeed(seed: Long): ZIO[TestRandom, Nothing, Unit] =
      ZIO.accessM(_.get.setSeed(seed))

    /**
     * The buffer of the `TestRandom`.
     */
    final case class Buffer(
      booleans: List[Boolean] = List.empty,
      bytes: List[Chunk[Byte]] = List.empty,
      chars: List[Char] = List.empty,
      doubles: List[Double] = List.empty,
      floats: List[Float] = List.empty,
      integers: List[Int] = List.empty,
      longs: List[Long] = List.empty,
      strings: List[String] = List.empty
    )
  }

  /**
   * `TestSystem` supports deterministic testing of effects involving system
   * properties. Internally, `TestSystem` maintains mappings of environment
   * variables and system properties that can be set and accessed. No actual
   * environment variables or system properties will be accessed or set as a
   * result of these actions.
   *
   * {{{
   * import zio.system
   * import zio.test.environment.TestSystem
   *
   * for {
   *   _      <- TestSystem.putProperty("java.vm.name", "VM")
   *   result <- system.property("java.vm.name")
   * } yield result == Some("VM")
   * }}}

   */
  object TestSystem extends Serializable {

    trait Service extends Restorable {
      def putEnv(name: String, value: String): UIO[Unit]
      def putProperty(name: String, value: String): UIO[Unit]
      def setLineSeparator(lineSep: String): UIO[Unit]
      def clearEnv(variable: String): UIO[Unit]
      def clearProperty(prop: String): UIO[Unit]
    }

    final case class Test(systemState: Ref[TestSystem.Data]) extends System.Service with TestSystem.Service {

      /**
       * Returns the specified environment variable if it exists.
       */
      override def env(variable: String): ZIO[Any, SecurityException, Option[String]] =
        systemState.get.map(_.envs.get(variable))

      /**
       * Returns the specified system property if it exists.
       */
      override def property(prop: String): ZIO[Any, Throwable, Option[String]] =
        systemState.get.map(_.properties.get(prop))

      /**
       * Returns the system line separator.
       */
      override val lineSeparator: ZIO[Any, Nothing, String] =
        systemState.get.map(_.lineSeparator)

      /**
       * Adds the specified name and value to the mapping of environment
       * variables maintained by this `TestSystem`.
       */
      def putEnv(name: String, value: String): UIO[Unit] =
        systemState.update(data => data.copy(envs = data.envs.updated(name, value))).unit

      /**
       * Adds the specified name and value to the mapping of system properties
       * maintained by this `TestSystem`.
       */
      def putProperty(name: String, value: String): UIO[Unit] =
        systemState.update(data => data.copy(properties = data.properties.updated(name, value))).unit

      /**
       * Sets the system line separator maintained by this `TestSystem` to the
       * specified value.
       */
      def setLineSeparator(lineSep: String): UIO[Unit] =
        systemState.update(_.copy(lineSeparator = lineSep)).unit

      /**
       * Clears the mapping of environment variables.
       */
      def clearEnv(variable: String): UIO[Unit] =
        systemState.update(data => data.copy(envs = data.envs - variable)).unit

      /**
       * Clears the mapping of system properties.
       */
      def clearProperty(prop: String): UIO[Unit] =
        systemState.update(data => data.copy(properties = data.properties - prop)).unit

      /**
       * Saves the `TestSystem``'s current state in an effect which, when run, will restore the `TestSystem`
       * state to the saved state.
       */
      val save: UIO[UIO[Unit]] =
        for {
          sState <- systemState.get
        } yield systemState.set(sState)
    }

    /**
     * The default initial state of the `TestSystem` with no environment variable
     * or system property mappings and the system line separator set to the new
     * line character.
     */
    val DefaultData: Data = Data(Map(), Map(), "\n")

    /**
     * Constructs a new `TestSystem` with the specified initial state. This can
     * be useful for providing the required environment to an effect that
     * requires a `Console`, such as with [[ZIO!.provide]].
     */
    def live(data: Data): ZLayer.NoDeps[Nothing, System with TestSystem] =
      ZLayer.fromEffect(Ref.make(data).map(ref => Has.allOf[System.Service, TestSystem.Service](Test(ref), Test(ref))))

    val any: ZLayer[System with TestSystem, Nothing, System with TestSystem] =
      ZLayer.requires[System with TestSystem]

    val default: ZLayer.NoDeps[Nothing, System with TestSystem] =
      live(DefaultData)

    /**
     * Accesses a `TestSystem` instance in the environment and adds the specified
     * name and value to the mapping of environment variables.
     */
    def putEnv(name: String, value: String): ZIO[TestSystem, Nothing, Unit] =
      ZIO.accessM(_.get.putEnv(name, value))

    /**
     * Accesses a `TestSystem` instance in the environment and adds the specified
     * name and value to the mapping of system properties.
     */
    def putProperty(name: String, value: String): ZIO[TestSystem, Nothing, Unit] =
      ZIO.accessM(_.get.putProperty(name, value))

    /**
     * Accesses a `TestSystem` instance in the environment and saves the system state in an effect which, when run,
     * will restore the `TestSystem` to the saved state
     */
    val save: ZIO[TestSystem, Nothing, UIO[Unit]] = ZIO.accessM[TestSystem](_.get.save)

    /**
     * Accesses a `TestSystem` instance in the environment and sets the line
     * separator to the specified value.
     */
    def setLineSeparator(lineSep: String): ZIO[TestSystem, Nothing, Unit] =
      ZIO.accessM(_.get.setLineSeparator(lineSep))

    /**
     * Accesses a `TestSystem` instance in the environment and clears the mapping
     * of environment variables.
     */
    def clearEnv(variable: String): ZIO[TestSystem, Nothing, Unit] =
      ZIO.accessM(_.get.clearEnv(variable))

    /**
     * Accesses a `TestSystem` instance in the environment and clears the mapping
     * of system properties.
     */
    def clearProperty(prop: String): ZIO[TestSystem, Nothing, Unit] =
      ZIO.accessM(_.get.clearProperty(prop))

    /**
     * The state of the `TestSystem`.
     */
    final case class Data(
      properties: Map[String, String] = Map.empty,
      envs: Map[String, String] = Map.empty,
      lineSeparator: String = "\n"
    )
  }
}
