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

package zio.stm

import scala.collection.immutable.{ Queue => ScalaQueue }

final class TQueue[A] private (val capacity: Int, ref: TRef[ScalaQueue[A]]) {

  /**
   * Checks if the queue is empty.
   */
  def isEmpty: STM[Nothing, Boolean] =
    ref.get.map(_.isEmpty)

  /**
   * Checks if the queue is at capacity.
   */
  def isFull: STM[Nothing, Boolean] =
    ref.get.map(_.size == capacity)

  /**
   * Views the last element inserted into the queue, retrying if the queue is
   * empty.
   */
  def last: STM[Nothing, A] =
    ref.get.flatMap(
      _.lastOption match {
        case Some(a) => STM.succeed(a)
        case None    => STM.retry
      }
    )

  /**
   * Offers the specified value to the queue, retrying if the queue is at
   * capacity.
   */
  def offer(a: A): STM[Nothing, Unit] =
    ref.get.flatMap { q =>
      if (q.length < capacity) ref.update(_.enqueue(a)).unit
      else STM.retry
    }

  /**
   * Offers each of the elements in the specified collection to the queue up to
   * the maximum capacity of the queue, retrying if there is not capacity in
   * the queue for all of these elements. Returns any remaining elements in the
   * specified collection.
   */
  def offerAll(as: Iterable[A]): STM[Nothing, Iterable[A]] = {
    val (forQueue, remaining) = as.splitAt(capacity)
    ref.get.flatMap { q =>
      if (forQueue.size <= capacity - q.length) ref.update(_.enqueue(forQueue.toList))
      else STM.retry
    } *> STM.succeed(remaining)
  }

  /**
   * Views the next element in the queue without removing it, retrying if the
   * queue is empty.
   */
  def peek: STM[Nothing, A] =
    ref.get.flatMap(
      _.headOption match {
        case Some(a) => STM.succeed(a)
        case None    => STM.retry
      }
    )

  /**
   * Views the next element in the queue without removing it, returning `None`
   * if the queue is empty.
   */
  def peekOption: STM[Nothing, Option[A]] =
    ref.get.map(_.headOption)

  /**
   * Takes a single element from the queue, returning `None` if the queue is
   * empty.
   */
  def poll: STM[Nothing, Option[A]] =
    takeUpTo(1).map(_.headOption)

  /**
   * Drops elements from the queue while they do not satisfy the predicate,
   * taking and returning the first element that does satisfy the predicate.
   * Retries if no elements satisfy the predicate.
   */
  def seek(f: A => Boolean): STM[Nothing, A] = {
    @annotation.tailrec
    def go(q: ScalaQueue[A]): STM[Nothing, A] =
      q.dequeueOption match {
        case Some((a, as)) =>
          if (f(a)) ref.set(as) *> STM.succeed(a)
          else go(as)
        case None => STM.retry
      }

    ref.get.flatMap(go)
  }

  /**
   * Returns the number of elements currently in the queue.
   */
  def size: STM[Nothing, Int] =
    ref.get.map(_.length)

  /**
   * Takes a single element from the queue, retrying if the queue is empty.
   */
  def take: STM[Nothing, A] =
    ref.get.flatMap { q =>
      q.dequeueOption match {
        case Some((a, as)) =>
          ref.set(as) *> STM.succeed(a)
        case _ => STM.retry
      }
    }

  /**
   * Takes all elements from the queue.
   */
  def takeAll: STM[Nothing, List[A]] =
    ref.modify(q => (q.toList, ScalaQueue.empty[A]))

  /**
   * Takes up to the specified maximum number of elements from the queue.
   */
  def takeUpTo(max: Int): STM[Nothing, List[A]] =
    ref.get
      .map(_.splitAt(max))
      .flatMap(split => ref.set(split._2) *> STM.succeed(split._1))
      .map(_.toList)
}

object TQueue {

  /**
   * Constructs a new bounded queue with the specified capacity.
   */
  def bounded[A](capacity: Int): STM[Nothing, TQueue[A]] =
    TRef.make(ScalaQueue.empty[A]).map(ref => new TQueue(capacity, ref))

  /**
   * Constructs a new unbounded queue.
   */
  def unbounded[A]: STM[Nothing, TQueue[A]] =
    bounded(Int.MaxValue)
}
