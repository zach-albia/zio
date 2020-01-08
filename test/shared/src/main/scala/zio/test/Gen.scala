/*
 * Copyright 2019-2020 John A. De Goes and the ZIO Contributors
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

import java.util.UUID

import scala.collection.immutable.SortedMap
import scala.math.Numeric.DoubleIsFractional

import zio.random._
import zio.stream.{ Stream, ZStream }
import zio.{ UIO, ZIO }

/**
 * A `Gen[R, A]` represents a generator of values of type `A`, which requires
 * an environment `R`. Generators may be random or deterministic.
 */
final case class Gen[-R, +A](sample: ZStream[R, Nothing, Sample[R, A]]) { self =>

  /**
   * A symbolic alias for `zip`.
   */
  def <&>[R1 <: R, B](that: Gen[R1, B]): Gen[R1, (A, B)] =
    self.zip(that)

  /**
   * A symbolic alias for `cross`.
   */
  def <*>[R1 <: R, B](that: Gen[R1, B]): Gen[R1, (A, B)] =
    self.cross(that)

  /**
   * Composes this generator with the specified generator to create a cartesian
   * product of elements.
   */
  def cross[R1 <: R, B](that: Gen[R1, B]): Gen[R1, (A, B)] =
    self.crossWith(that)((_, _))

  /**
   * Composes this generator with the specified generator to create a cartesian
   * product of elements with the specified function.
   */
  def crossWith[R1 <: R, B, C](that: Gen[R1, B])(f: (A, B) => C): Gen[R1, C] =
    self.flatMap(a => that.map(b => f(a, b)))

  /**
   * Filters the values produced by this generator, discarding any values that
   * do not meet the specified predicate. Using `filter` can reduce test
   * performance, especially if many values must be discarded. It is
   * recommended to use combinators such as `map` and `flatMap` to create
   * generators of the desired values instead.
   *
   * {{{
   * val evens: Gen[Random, Int] = Gen.anyInt.map(_ * 2)
   * }}}
   */
  def filter(f: A => Boolean): Gen[R, A] = Gen {
    sample.flatMap { sample =>
      if (f(sample.value)) sample.filter(f) else ZStream.empty
    }
  }

  def withFilter(f: A => Boolean): Gen[R, A] = filter(f)

  def flatMap[R1 <: R, B](f: A => Gen[R1, B]): Gen[R1, B] = Gen {
    self.sample.flatMap { sample =>
      val values  = f(sample.value).sample
      val shrinks = Gen(sample.shrink).flatMap(f).sample
      values.map(_.flatMap(Sample(_, shrinks)))
    }
  }

  def flatten[R1 <: R, B](implicit ev: A <:< Gen[R1, B]): Gen[R1, B] =
    flatMap(ev)

  def map[B](f: A => B): Gen[R, B] =
    Gen(sample.map(_.map(f)))

  /**
   * Maps an effectual function over a generator.
   */
  def mapM[R1 <: R, B](f: A => ZIO[R1, Nothing, B]): Gen[R1, B] =
    Gen(sample.mapM(_.traverse(f)))

  /**
   * Discards the shrinker for this generator.
   */
  def noShrink: Gen[R, A] =
    reshrink(Sample.noShrink)

  /**
   * Discards the shrinker for this generator and applies a new shrinker by
   * mapping each value to a sample using the specified function. This is
   * useful when the process to shrink a value is simpler than the process used
   * to generate it.
   */
  def reshrink[R1 <: R, B](f: A => Sample[R1, B]): Gen[R1, B] =
    Gen(sample.map(sample => f(sample.value)))

  /**
   * Runs the generator and collects all of its values in a list.
   */
  def runCollect: ZIO[R, Nothing, List[A]] =
    sample.map(_.value).runCollect

  /**
   * Repeatedly runs the generator and collects the specified number of values
   * in a list.
   */
  def runCollectN(n: Int): ZIO[R, Nothing, List[A]] =
    sample.map(_.value).forever.take(n.toLong).runCollect

  /**
   * Runs the generator returning the first value of the generator.
   */
  def runHead: ZIO[R, Nothing, Option[A]] =
    sample.map(_.value).runHead

  /**
   * Zips two generators together pairwise. The new generator will generate
   * elements as long as either generator is generating elements, running the
   * other generator multiple times if necessary.
   */
  def zip[R1 <: R, B](that: Gen[R1, B]): Gen[R1, (A, B)] =
    self.zipWith(that)((_, _))

  /**
   * Zips two generators together pairwise with the specified function. The new
   * generator will generate elements as long as either generator is generating
   * elements, running the other generator multiple times if necessary.
   */
  def zipWith[R1 <: R, B, C](that: Gen[R1, B])(f: (A, B) => C): Gen[R1, C] = Gen {
    val left  = self.sample.map(Right(_)) ++ self.sample.map(Left(_)).forever
    val right = that.sample.map(Right(_)) ++ that.sample.map(Left(_)).forever
    left.zipWith(right) {
      case (Some(Right(l)), Some(Right(r))) => Some(l.zipWith(r)(f))
      case (Some(Right(l)), Some(Left(r)))  => Some(l.zipWith(r)(f))
      case (Some(Left(l)), Some(Right(r)))  => Some(l.zipWith(r)(f))
      case _                                => None
    }
  }
}

object Gen extends GenZIO with FunctionVariants with TimeVariants {

  /**
   * A generator of alphanumeric characters. Shrinks toward '0'.
   */
  val alphaNumericChar: Gen[Random, Char] =
    weighted(char(48, 57) -> 10, char(65, 90) -> 26, char(97, 122) -> 26)

  /**
   * A generator of alphanumeric strings. Shrinks towards the empty string.
   */
  val alphaNumericString: Gen[Random with Sized, String] =
    Gen.string(alphaNumericChar)

  /**
   * A generator of alphanumeric strings whose size falls within the specified
   * bounds.
   */
  def alphaNumericStringBounded(min: Int, max: Int): Gen[Random with Sized, String] =
    Gen.stringBounded(min, max)(alphaNumericChar)

  /**
   * A generator of bytes. Shrinks toward '0'.
   */
  val anyByte: Gen[Random, Byte] =
    fromEffectSample {
      nextInt(Byte.MaxValue - Byte.MinValue + 1)
        .map(r => (Byte.MinValue + r).toByte)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of characters. Shrinks toward '0'.
   */
  val anyChar: Gen[Random, Char] =
    fromEffectSample {
      nextInt(Char.MaxValue - Char.MinValue + 1)
        .map(r => (Char.MinValue + r).toChar)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of integers. Shrinks toward '0'.
   */
  val anyFloat: Gen[Random, Float] =
    fromEffectSample(nextFloat.map(Sample.shrinkFractional(0f)))

  /**
   * A generator of integers. Shrinks toward '0'.
   */
  val anyInt: Gen[Random, Int] =
    fromEffectSample(nextInt.map(Sample.shrinkIntegral(0)))

  /**
   * A generator of longs. Shrinks toward '0'.
   */
  val anyLong: Gen[Random, Long] =
    fromEffectSample(nextLong.map(Sample.shrinkIntegral(0L)))

  /**
   * A generator of shorts. Shrinks toward '0'.
   */
  val anyShort: Gen[Random, Short] =
    fromEffectSample {
      nextInt(Short.MaxValue - Short.MinValue + 1)
        .map(r => (Short.MinValue + r).toShort)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of strings. Shrinks towards the empty string.
   */
  def anyString: Gen[Random with Sized, String] =
    Gen.string(Gen.anyUnicodeChar)

  /**
   * A generator of Unicode characters. Shrinks toward '0'.
   */
  val anyUnicodeChar: Gen[Random, Char] =
    Gen.oneOf(Gen.char('\u0000', '\uD7FF'), Gen.char('\uE000', '\uFFFD'))

  /**
   * A generator of universally unique identifiers. The returned generator will
   * not have any shrinking.
   */
  val anyUUID: Gen[Random, UUID] =
    for {
      mostSigBits  <- Gen.anyLong.noShrink
      leastSigBits <- Gen.anyLong.noShrink
    } yield new UUID(
      (mostSigBits & ~0x0000F000) | 0x00004000,
      (leastSigBits & ~(0xC0000000L << 32)) | (0x80000000L << 32)
    )

  /**
   * A generator of booleans. Shrinks toward 'false'.
   */
  val boolean: Gen[Random, Boolean] =
    elements(false, true)

  /**
   * A generator whose size falls within the specified bounds.
   */
  def bounded[R <: Random, A](min: Int, max: Int)(f: Int => Gen[R, A]): Gen[R, A] =
    int(min, max).flatMap(f)

  /**
   * A generator of byte values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def byte(min: Byte, max: Byte): Gen[Random, Byte] =
    int(min.toInt, max.toInt).map(_.toByte)

  /**
   * A generator of character values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def char(min: Char, max: Char): Gen[Random, Char] =
    int(min.toInt, max.toInt).map(_.toChar)

  /**
   * A constant generator of the specified value.
   */
  def const[A](a: => A): Gen[Any, A] =
    Gen(ZStream.succeed(Sample.noShrink(a)))

  /**
   * A constant generator of the specified sample.
   */
  def constSample[R, A](sample: => Sample[R, A]): Gen[R, A] =
    fromEffectSample(ZIO.succeed(sample))

  /**
   * Composes the specified generators to create a cartesian product of
   * elements with the specified function.
   */
  def crossAll[R, A](gens: Iterable[Gen[R, A]]): Gen[R, List[A]] =
    gens.foldRight[Gen[R, List[A]]](Gen.const(List.empty))(_.crossWith(_)(_ :: _))

  /**
   * Composes the specified generators to create a cartesian product of
   * elements with the specified function.
   */
  def crossN[R, A, B, C](gen1: Gen[R, A], gen2: Gen[R, B])(f: (A, B) => C): Gen[R, C] =
    gen1.crossWith(gen2)(f)

  /**
   * Composes the specified generators to create a cartesian product of
   * elements with the specified function.
   */
  def crossN[R, A, B, C, D](gen1: Gen[R, A], gen2: Gen[R, B], gen3: Gen[R, C])(f: (A, B, C) => D): Gen[R, D] =
    for {
      a <- gen1
      b <- gen2
      c <- gen3
    } yield f(a, b, c)

  /**
   * Composes the specified generators to create a cartesian product of
   * elements with the specified function.
   */
  def crossN[R, A, B, C, D, F](gen1: Gen[R, A], gen2: Gen[R, B], gen3: Gen[R, C], gen4: Gen[R, D])(
    f: (A, B, C, D) => F
  ): Gen[R, F] =
    for {
      a <- gen1
      b <- gen2
      c <- gen3
      d <- gen4
    } yield f(a, b, c, d)

  /**
   * A generator of double values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def double(min: Double, max: Double): Gen[Random, Double] =
    uniform.map { r =>
      val n = min + r * (max - min)
      if (n < max) n else Math.nextAfter(max, Double.NegativeInfinity)
    }

  def either[R <: Random, A, B](left: Gen[R, A], right: Gen[R, B]): Gen[R, Either[A, B]] =
    oneOf(left.map(Left(_)), right.map(Right(_)))

  def elements[A](as: A*): Gen[Random, A] =
    if (as.isEmpty) empty else int(0, as.length - 1).map(as)

  val empty: Gen[Any, Nothing] =
    Gen(Stream.empty)

  /**
   * A generator of exponentially distributed doubles with mean `1`.
   * The shrinker will shrink toward `0`.
   */
  val exponential: Gen[Random, Double] =
    uniform.map(n => -math.log(1 - n))

  /**
   * Constructs a generator from an effect that constructs a value.
   */
  def fromEffect[R, A](effect: ZIO[R, Nothing, A]): Gen[R, A] =
    Gen(ZStream.fromEffect(effect.map(Sample.noShrink)))

  /**
   * Constructs a generator from an effect that constructs a sample.
   */
  def fromEffectSample[R, A](effect: ZIO[R, Nothing, Sample[R, A]]): Gen[R, A] =
    Gen(ZStream.fromEffect(effect))

  /**
   * Constructs a deterministic generator that only generates the specified fixed values.
   */
  def fromIterable[R, A](
    as: Iterable[A],
    shrinker: (A => ZStream[R, Nothing, A]) = defaultShrinker
  ): Gen[R, A] =
    Gen(ZStream.fromIterable(as).map(a => Sample.unfold(a)(a => (a, shrinker(a)))))

  /**
   * Constructs a generator from a function that uses randomness. The returned
   * generator will not have any shrinking.
   */
  def fromRandom[A](f: Random.Service[Any] => UIO[A]): Gen[Random, A] =
    Gen(ZStream.fromEffect(ZIO.accessM[Random](r => f(r.random)).map(Sample.noShrink)))

  /**
   * Constructs a generator from a function that uses randomness to produce a
   * sample.
   */
  def fromRandomSample[R <: Random, A](f: Random.Service[Any] => UIO[Sample[R, A]]): Gen[R, A] =
    Gen(ZStream.fromEffect(ZIO.accessM[Random](r => f(r.random))))

  /**
   * A generator of integers inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def int(min: Int, max: Int): Gen[Random, Int] =
    Gen.fromEffectSample {
      val difference = max - min + 1
      val effect =
        if (difference > 0) nextInt(difference).map(min + _)
        else nextInt.doUntil(n => min <= n && n <= max)
      effect.map(Sample.shrinkIntegral(min))
    }

  /**
   * A sized generator that uses a uniform distribution of size values. A large
   * number of larger sizes will be generated.
   */
  def large[R <: Random with Sized, A](f: Int => Gen[R, A], min: Int = 0): Gen[R, A] =
    size.flatMap(max => int(min, max)).flatMap(f)

  def listOf[R <: Random with Sized, A](g: Gen[R, A]): Gen[R, List[A]] =
    small(listOfN(_)(g))

  def listOf1[R <: Random with Sized, A](g: Gen[R, A]): Gen[R, List[A]] =
    small(listOfN(_)(g), 1)

  /**
   * A generator of lists whose size falls within the specified bounds.
   */
  def listOfBounded[R <: Random, A](min: Int, max: Int)(g: Gen[R, A]): Gen[R, List[A]] =
    bounded(min, max)(listOfN(_)(g))

  def listOfN[R <: Random, A](n: Int)(g: Gen[R, A]): Gen[R, List[A]] =
    List.fill(n)(g).foldRight[Gen[R, List[A]]](const(Nil))((a, gen) => a.crossWith(gen)(_ :: _))

  /**
   * A generator of long values in the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def long(min: Long, max: Long): Gen[Random, Long] =
    Gen.fromEffectSample {
      val difference = max - min + 1
      val effect =
        if (difference > 0) nextLong(difference).map(min + _)
        else nextLong.doUntil(n => min <= n && n <= max)
      effect.map(Sample.shrinkIntegral(min))
    }

  /**
   * A sized generator that uses an exponential distribution of size values.
   * The majority of sizes will be towards the lower end of the range but some
   * larger sizes will be generated as well.
   */
  def medium[R <: Random with Sized, A](f: Int => Gen[R, A], min: Int = 0): Gen[R, A] = {
    val gen = for {
      max <- size
      n   <- exponential
    } yield clamp(math.round(n * max / 10.0).toInt, min, max)
    gen.reshrink(Sample.shrinkIntegral(min)).flatMap(f)
  }

  /**
   * A constant generator of the empty value.
   */
  val none: Gen[Any, Option[Nothing]] =
    Gen.const(None)

  /**
   * A generator of optional values. Shrinks toward `None`.
   */
  def option[R <: Random, A](gen: Gen[R, A]): Gen[R, Option[A]] =
    oneOf(none, gen.map(Some(_)))

  def oneOf[R <: Random, A](as: Gen[R, A]*): Gen[R, A] =
    if (as.isEmpty) empty else int(0, as.length - 1).flatMap(as)

  /**
   * Constructs a generator of partial functions from `A` to `B` given a
   * generator of `B` values. Two `A` values will be considered to be equal,
   * and thus will be guaranteed to generate the same `B` value or both be
   * outside the partial function's domain, if they have the same `hashCode`.
   */
  def partialFunction[R <: Random, A, B](gen: Gen[R, B]): Gen[R, PartialFunction[A, B]] =
    partialFunctionWith(gen)(_.hashCode)

  /**
   * Constructs a generator of partial functions from `A` to `B` given a
   * generator of `B` values and a hashing function for `A` values. Two `A`
   * values will be considered to be equal, and thus will be guaranteed to
   * generate the same `B` value or both be outside the partial function's
   * domain, if they have have the same hash. This is useful when `A` does not
   * implement `hashCode` in a way that is consistent with equality.
   */
  def partialFunctionWith[R <: Random, A, B](gen: Gen[R, B])(hash: A => Int): Gen[R, PartialFunction[A, B]] =
    functionWith(option(gen))(hash).map(Function.unlift)

  /**
   * A generator of printable characters. Shrinks toward '!'.
   */
  val printableChar: Gen[Random, Char] =
    char(33, 126)

  /**
   * A generator of short values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def short(min: Short, max: Short): Gen[Random, Short] =
    int(min.toInt, max.toInt).map(_.toShort)

  def size: Gen[Sized, Int] =
    Gen.fromEffect(Sized.size)

  /**
   * A sized generator, whose size falls within the specified bounds.
   */
  def sized[R <: Sized, A](f: Int => Gen[R, A]): Gen[R, A] =
    size.flatMap(f)

  /**
   * A sized generator that uses an exponential distribution of size values.
   * The values generated will be strongly concentrated towards the lower end
   * of the range but a few larger values will still be generated.
   */
  def small[R <: Random with Sized, A](f: Int => Gen[R, A], min: Int = 0): Gen[R, A] = {
    val gen = for {
      max <- size
      n   <- exponential
    } yield clamp(math.round(n * max / 25.0).toInt, min, max)
    gen.reshrink(Sample.shrinkIntegral(min)).flatMap(f)
  }

  def some[R, A](gen: Gen[R, A]): Gen[R, Option[A]] =
    gen.map(Some(_))

  def string[R <: Random with Sized](char: Gen[R, Char]): Gen[R, String] =
    listOf(char).map(_.mkString)

  def string1[R <: Random with Sized](char: Gen[R, Char]): Gen[R, String] =
    listOf1(char).map(_.mkString)

  /**
   * A generator of strings whose size falls within the specified bounds.
   */
  def stringBounded[R <: Random](min: Int, max: Int)(g: Gen[R, Char]): Gen[R, String] =
    bounded(min, max)(stringN(_)(g))

  def stringN[R <: Random](n: Int)(char: Gen[R, Char]): Gen[R, String] =
    listOfN(n)(char).map(_.mkString)

  /**
   * Lazily constructs a generator. This is useful to avoid infinite recursion
   * when creating generators that refer to themselves.
   */
  def suspend[R, A](gen: => Gen[R, A]): Gen[R, A] =
    fromEffect(ZIO.effectTotal(gen)).flatten

  /**
   * A generator of throwables.
   */
  val throwable: Gen[Random, Throwable] =
    Gen.const(new Throwable)

  /**
   * A generator of uniformly distributed doubles between [0, 1].
   * The shrinker will shrink toward `0`.
   */
  def uniform: Gen[Random, Double] =
    fromEffectSample(nextDouble.map(Sample.shrinkFractional(0.0)))

  /**
   * A constant generator of the unit value.
   */
  val unit: Gen[Any, Unit] =
    const(())

  def vectorOf[R <: Random with Sized, A](g: Gen[R, A]): Gen[R, Vector[A]] =
    listOf(g).map(_.toVector)

  def vectorOf1[R <: Random with Sized, A](g: Gen[R, A]): Gen[R, Vector[A]] =
    listOf1(g).map(_.toVector)

  /**
   * A generator of vectors whose size falls within the specified bounds.
   */
  def vectorOfBounded[R <: Random, A](min: Int, max: Int)(g: Gen[R, A]): Gen[R, Vector[A]] =
    bounded(min, max)(vectorOfN(_)(g))

  def vectorOfN[R <: Random, A](n: Int)(g: Gen[R, A]): Gen[R, Vector[A]] =
    listOfN(n)(g).map(_.toVector)

  def weighted[R <: Random, A](gs: (Gen[R, A], Double)*): Gen[R, A] = {
    val sum = gs.map(_._2).sum
    val (map, _) = gs.foldLeft((SortedMap.empty[Double, Gen[R, A]], 0.0)) {
      case ((map, acc), (gen, d)) => (map.updated((acc + d) / sum, gen), acc + d)
    }
    uniform.flatMap(n => map.rangeImpl(Some(n), None).head._2)
  }

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipAll[R, A](gens: Iterable[Gen[R, A]]): Gen[R, List[A]] =
    gens.foldRight[Gen[R, List[A]]](Gen.const(List.empty))(_.zipWith(_)(_ :: _))

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C](gen1: Gen[R, A], gen2: Gen[R, B])(f: (A, B) => C): Gen[R, C] =
    gen1.zipWith(gen2)(f)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D](gen1: Gen[R, A], gen2: Gen[R, B], gen3: Gen[R, C])(f: (A, B, C) => D): Gen[R, D] =
    (gen1 <&> gen2 <&> gen3).map {
      case ((a, b), c) => f(a, b, c)
    }

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D, F](gen1: Gen[R, A], gen2: Gen[R, B], gen3: Gen[R, C], gen4: Gen[R, D])(
    f: (A, B, C, D) => F
  ): Gen[R, F] =
    (gen1 <&> gen2 <&> gen3 <&> gen4).map {
      case (((a, b), c), d) => f(a, b, c, d)
    }

  /**
   * Restricts an integer to the specified range.
   */
  private def clamp(n: Int, min: Int, max: Int): Int =
    if (n < min) min
    else if (n > max) max
    else n

  private val defaultShrinker: Any => ZStream[Any, Nothing, Nothing] =
    _ => ZStream.empty
}
