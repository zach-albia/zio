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

package zio

import java.io.IOException
import java.util.concurrent._

import zio.internal.tracing.ZIOFn
import zio.internal.{ Executor, NamedThreadFactory }

package object blocking {
  type Blocking = Has[Blocking.Service]

  /**
   * The `Blocking` module provides access to a thread pool that can be used for performing
   * blocking operations, such as thread sleeps, synchronous socket/file reads, and so forth.
   * The contract is that the thread pool will accept unlimited tasks (up to the available memory)
   * and continuously create new threads as necessary.
   */
  object Blocking extends Serializable {
    trait Service extends Serializable {

      /**
       * Retrieves the executor for all blocking tasks.
       */
      def blockingExecutor: Executor

      /**
       * Locks the specified effect to the blocking thread pool.
       */
      def blocking[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
        zio.lock(blockingExecutor)

      /**
       * Imports a synchronous effect that does blocking IO into a pure value.
       */
      def effectBlocking[A](effect: => A): Task[A] =
        blocking(ZIO.effect(effect))

      /**
       * Imports a synchronous effect that does blocking IO into a pure value,
       * with a custom cancel effect.
       *
       * If the returned `ZIO` is interrupted, the blocked thread running the
       * synchronous effect will be interrupted via the cancel effect.
       */
      def effectBlockingCancelable[A](effect: => A)(cancel: UIO[Unit]): Task[A] =
        blocking(ZIO.effect(effect)).fork.flatMap(_.join).onInterrupt(cancel)

      /**
       * Imports a synchronous effect that does blocking IO into a pure value.
       *
       * If the returned `ZIO` is interrupted, the blocked thread running the
       * synchronous effect will be interrupted via `Thread.interrupt`.
       *
       * Note that this adds significant overhead. For performance sensitive
       * applications consider using `effectBlocking` or
       * `effectBlockingCancel`.
       */
      def effectBlockingInterrupt[A](effect: => A): Task[A] =
        // Reference user's lambda for the tracer
        ZIOFn.recordTrace(() => effect) {
          ZIO.effectSuspendTotal {
            import java.util.concurrent.atomic.AtomicReference
            import java.util.concurrent.locks.ReentrantLock

            import zio.internal.OneShot

            val lock    = new ReentrantLock()
            val thread  = new AtomicReference[Option[Thread]](None)
            val barrier = OneShot.make[Unit]

            def withMutex[B](b: => B): B =
              try {
                lock.lock(); b
              } finally lock.unlock()

            val interruptThread: UIO[Unit] =
              ZIO.effectTotal {
                var looping = true
                var n       = 0L
                val base    = 2L
                while (looping) {
                  withMutex(thread.get match {
                    case None         => looping = false; ()
                    case Some(thread) => thread.interrupt()
                  })

                  if (looping) {
                    n += 1
                    Thread.sleep(math.min(50, base * n))
                  }
                }
              }

            val awaitInterruption: UIO[Unit] = ZIO.effectTotal(barrier.get())

            blocking(for {
              a <- (for {
                    fiber <- ZIO
                              .effectTotal[IO[Throwable, A]] {
                                val current = Some(Thread.currentThread)

                                withMutex(thread.set(current))

                                try {
                                  val a = effect
                                  ZIO.succeed(a)
                                } catch {
                                  case _: InterruptedException =>
                                    Thread.interrupted // Clear interrupt status
                                    ZIO.interrupt
                                  case t: Throwable =>
                                    ZIO.fail(t)
                                } finally {
                                  withMutex { thread.set(None); barrier.set(()) }
                                }
                              }
                              .fork
                    a <- fiber.join.flatten
                  } yield a).ensuring(interruptThread *> awaitInterruption)
            } yield a)
          }
        }
    }

    val any: ZLayer[Blocking, Nothing, Blocking] =
      ZLayer.requires[Blocking]

    val live: ZLayer.NoDeps[Nothing, Blocking] = ZLayer.succeed {
      new Service {
        override val blockingExecutor: Executor = internal.blockingExecutor0
      }
    }
  }

  def blocking[R <: Blocking, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.accessM[R](_.get.blocking(zio))

  def effectBlocking[A](effect: => A): ZIO[Blocking, Throwable, A] =
    ZIO.accessM[Blocking](_.get.effectBlocking(effect))

  def effectBlockingCancelable[A](effect: => A)(cancel: UIO[Unit]): ZIO[Blocking, Throwable, A] =
    ZIO.accessM[Blocking](_.get.effectBlockingCancelable(effect)(cancel))

  def effectBlockingIO[A](effect: => A): ZIO[Blocking, IOException, A] =
    effectBlocking(effect).refineToOrDie[IOException]

  def effectBlockingInterrupt[A](effect: => A): ZIO[Blocking, Throwable, A] =
    ZIO.accessM(_.get.effectBlockingInterrupt(effect))

  private[blocking] object internal {
    private[blocking] val blockingExecutor0 =
      Executor.fromThreadPoolExecutor(_ => Int.MaxValue) {
        val corePoolSize  = 0
        val maxPoolSize   = Int.MaxValue
        val keepAliveTime = 1000L
        val timeUnit      = TimeUnit.MILLISECONDS
        val workQueue     = new SynchronousQueue[Runnable]()
        val threadFactory = new NamedThreadFactory("zio-default-blocking", true)

        val threadPool = new ThreadPoolExecutor(
          corePoolSize,
          maxPoolSize,
          keepAliveTime,
          timeUnit,
          workQueue,
          threadFactory
        )

        threadPool
      }
  }
}
