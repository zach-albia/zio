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

package zio.scheduler

import java.util.concurrent._

import zio.duration.Duration
import zio.internal.{ IScheduler, NamedThreadFactory }

private[scheduler] trait PlatformSpecific {
  import IScheduler.CancelToken

  private[scheduler] val globalScheduler = new IScheduler {

    private[this] val service = Executors.newScheduledThreadPool(1, new NamedThreadFactory("zio-timer", true))

    private[this] val ConstFalse = () => false

    override def schedule(task: Runnable, duration: Duration): CancelToken = duration match {
      case Duration.Infinity => ConstFalse
      case Duration.Zero =>
        task.run()

        ConstFalse
      case duration: Duration.Finite =>
        val future = service.schedule(new Runnable {
          def run: Unit =
            task.run()
        }, duration.toNanos, TimeUnit.NANOSECONDS)

        () => {
          val canceled = future.cancel(true)

          canceled
        }
    }
  }
}
