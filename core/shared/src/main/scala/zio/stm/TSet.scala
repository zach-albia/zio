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

/**
 * Transactional set implemented on top of [[TMap]].
 */
final class TSet[A] private (private val tmap: TMap[A, Unit]) extends AnyVal {

  /**
   * Tests whether or not set contains an element.
   */
  def contains(a: A): STM[Nothing, Boolean] =
    tmap.contains(a)

  /**
   * Removes element from set.
   */
  def delete(a: A): STM[Nothing, Unit] =
    tmap.delete(a)

  /**
   * Atomically transforms the set into the difference of itself and the
   * provided set.
   */
  def diff(other: TSet[A]): STM[Nothing, Unit] =
    other.toList.map(_.toSet).flatMap(vals => removeIf(vals.contains))

  /**
   * Atomically folds using a pure function.
   */
  def fold[B](zero: B)(op: (B, A) => B): STM[Nothing, B] =
    tmap.fold(zero)((acc, kv) => op(acc, kv._1))

  /**
   * Atomically folds using a transactional function.
   */
  def foldM[B, E](zero: B)(op: (B, A) => STM[E, B]): STM[E, B] =
    tmap.foldM(zero)((acc, kv) => op(acc, kv._1))

  /**
   * Atomically performs transactional-effect for each element in set.
   */
  def foreach[E](f: A => STM[E, Unit]): STM[E, Unit] =
    foldM(())((_, a) => f(a))

  /**
   * Atomically transforms the set into the intersection of itself and the
   * provided set.
   */
  def intersect(other: TSet[A]): STM[Nothing, Unit] =
    other.toList.map(_.toSet).flatMap(vals => retainIf(vals.contains))

  /**
   * Stores new element in the set.
   */
  def put(a: A): STM[Nothing, Unit] =
    tmap.put(a, ())

  /**
   * Removes elements matching predicate.
   */
  def removeIf(p: A => Boolean): STM[Nothing, Unit] =
    tmap.removeIf((k, _) => p(k))

  /**
   * Retains elements matching predicate.
   */
  def retainIf(p: A => Boolean): STM[Nothing, Unit] =
    tmap.retainIf((k, _) => p(k))

  /**
   * Returns the set's cardinality.
   */
  def size: STM[Nothing, Int] = toList.map(_.size)

  /**
   * Collects all elements into a list.
   */
  def toList: STM[Nothing, List[A]] = tmap.keys

  /**
   * Atomically updates all elements using a pure function.
   */
  def transform(f: A => A): STM[Nothing, Unit] =
    tmap.transform((k, v) => f(k) -> v)

  /**
   * Atomically updates all elements using a transactional function.
   */
  def transformM[E](f: A => STM[E, A]): STM[E, Unit] =
    tmap.transformM((k, v) => f(k).map(_ -> v))

  /**
   * Atomically transforms the set into the union of itself and the provided
   * set.
   */
  def union(other: TSet[A]): STM[Nothing, Unit] =
    other.toList.flatMap[Any, Nothing, List[Unit]](vals => STM.collectAll(vals.map(put))).unit
}

object TSet {

  /**
   * Makes an empty `TSet`.
   */
  def empty[A]: STM[Nothing, TSet[A]] = fromIterable(Nil)

  /**
   * Makes a new `TSet` initialized with provided iterable.
   */
  def fromIterable[A](data: Iterable[A]): STM[Nothing, TSet[A]] =
    TMap.fromIterable(data.map((_, ()))).map(new TSet(_))

  /**
   * Makes a new `TSet` that is initialized with specified values.
   */
  def make[A](data: A*): STM[Nothing, TSet[A]] = fromIterable(data)
}
