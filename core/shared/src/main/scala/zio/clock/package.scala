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

import java.time.{ Instant, OffsetDateTime, ZoneId }
import java.util.concurrent.TimeUnit

import zio.duration.Duration
import zio.scheduler.Scheduler

package object clock {
  type Clock = Has[Clock.Service]

  object Clock extends Serializable {
    trait Service extends Serializable {
      def currentTime(unit: TimeUnit): UIO[Long]
      def currentDateTime: UIO[OffsetDateTime]
      val nanoTime: UIO[Long]
      def sleep(duration: Duration): UIO[Unit]
    }

    val any: ZLayer[Clock, Nothing, Clock] =
      ZLayer.requires[Clock]

    val live: ZLayer[Scheduler, Nothing, Clock] = ZLayer.fromService { (scheduler: Scheduler.Service) =>
      Has(new Service {
        def currentTime(unit: TimeUnit): UIO[Long] =
          IO.effectTotal(System.currentTimeMillis).map(l => unit.convert(l, TimeUnit.MILLISECONDS))

        val nanoTime: UIO[Long] = IO.effectTotal(System.nanoTime)

        def sleep(duration: Duration): UIO[Unit] =
          scheduler.schedule(UIO.unit, duration)

        def currentDateTime: ZIO[Any, Nothing, OffsetDateTime] =
          for {
            millis <- currentTime(TimeUnit.MILLISECONDS)
            zone   <- ZIO.effectTotal(ZoneId.systemDefault)
          } yield OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

      })
    }
  }

  /**
   * Returns the current time, relative to the Unix epoch.
   */
  def currentTime(unit: TimeUnit): ZIO[Clock, Nothing, Long] =
    ZIO.accessM(_.get.currentTime(unit))

  /**
   * Get the current time, represented in the current timezone.
   */
  def currentDateTime: ZIO[Clock, Nothing, OffsetDateTime] =
    ZIO.accessM(_.get.currentDateTime)

  /**
   * Returns the system nano time, which is not relative to any date.
   */
  val nanoTime: ZIO[Clock, Nothing, Long] =
    ZIO.accessM(_.get.nanoTime)

  /**
   * Sleeps for the specified duration. This is always asynchronous.
   */
  def sleep(duration: Duration): ZIO[Clock, Nothing, Unit] =
    ZIO.accessM(_.get.sleep(duration))

}
