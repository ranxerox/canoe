package canoe.api.matching

import canoe.api.matching.Episode._
import cats.instances.list._
import cats.syntax.all._
import cats.{ApplicativeError, Monad, MonadError, StackSafeMonad}
import fs2.{Pipe, Pull, Stream}

/**
  * Type which represents a description of sequence of elements.
  *
  * Such description can be applied to the actual sequence/stream of elements
  * to find the subsequences which match the description.
  *
  * For each partially matched subsequence (such that at least first element was matched)
  * one of 3 possible results is yielded:
  *  - Matched(o)   - when the whole episode was matched as a subsequence of input sequence.
  *                   Contains the result value of type `O`.
  *  - Missed(i)    - when the episode was started (partially matched) but was not fully matched.
  *                   Contains the first element of type `I` which didn't match the description.
  *  - Cancelled(i) - when the episode was cancelled by specific input.
  *                   Contains the input element of type `I` which caused cancellation.
  *
  * `Episode` forms a monad in `O` with `pure` and `flatMap`.
  *
  * @tparam F Effect type
  * @tparam I Input elements type
  * @tparam O Result value type
  */
private[api] sealed trait Episode[F[_], -I, +O] {

  /**
    * Pipe which produces value `O` for each subsequence of `I` elements
    * matching this description, evaluated in `F` effect.
    * Fails on the first unhandled error result.
    */
  def matching: Pipe[F, I, O] =
    find(this, _, Nil).collect {
      case (Matched(o), _) => o
      case (Failed(e), _)  => throw e
    }

  def flatMap[I2 <: I, O2](fn: O => Episode[F, I2, O2]): Episode[F, I2, O2] =
    Bind(this, fn)

  def map[O2](fn: O => O2): Episode[F, I, O2] = flatMap(fn.andThen(Pure(_)))

  /**
    * @return Episode which is cancellable by the occurrence of input element described
    *         by predicate `p` at any point after the episode is started and before it is finished
    */
  def cancelOn[I2 <: I](p: I2 => Boolean): Episode[F, I2, O] =
    Cancellable(this, p, None)

  /**
    * @param p Predicate which determines what input value causes cancellation
    * @param cancellation Function which result is going to be evaluated during the cancellation
    *
    * @return Episode which is cancellable by the occurrence of input element described
    *         by predicate `p` at any point after the episode is started and before it is finished,
    *         and evaluates `cancellation` when such element occurs.
    */
  def cancelWith[I2 <: I](
    p: I2 => Boolean
  )(cancellation: I2 => F[Unit]): Episode[F, I2, O] =
    Cancellable(this, p, Some(cancellation))

  /**
    * @return Episode which ignores the input element, which causes
    *         missed result, `n` time and evaluates `fn` for every such element
    */
  def tolerateN[I2 <: I](n: Int)(fn: I2 => F[Unit]): Episode[F, I2, O] =
    Tolerate(this, Some(n), fn)

  /**
    * @return Episode which ignores every input element which causes
    *         missed result and evaluates `fn` for every such element
    */
  def tolerateAll[I2 <: I](fn: I2 => F[Unit]): Episode[F, I2, O] =
    Tolerate(this, None, fn)

  def handleErrorWith[I2 <: I, O2 >: O](f: Throwable => Episode[F, I2, O2]): Episode[F, I2, O2] =
    Protected(this, f)

  def attempt: Episode[F, I, Either[Throwable, O]] =
    map(Right(_): Either[Throwable, O]).handleErrorWith(e => Episode.pure(Left(e)))

}

private final case class Pure[F[_], I, A](a: A) extends Episode[F, I, A]

private final case class Eval[F[_], I, A](fa: F[A]) extends Episode[F, I, A]

private final case class Next[F[_], A](p: A => Boolean) extends Episode[F, A, A]

private final case class First[F[_], A](p: A => Boolean) extends Episode[F, A, A]

private final case class Protected[F[_], I, O1, O2 >: O1](episode: Episode[F, I, O1],
                                                          onError: Throwable => Episode[F, I, O2])
    extends Episode[F, I, O2]

private final case class Bind[F[_], I, O1, O2](episode: Episode[F, I, O1], fn: O1 => Episode[F, I, O2])
    extends Episode[F, I, O2]

private final case class Cancellable[F[_], I, O](
  episode: Episode[F, I, O],
  cancelOn: I => Boolean,
  finalizer: Option[I => F[Unit]]
) extends Episode[F, I, O]

private final case class Tolerate[F[_], I, O](episode: Episode[F, I, O], limit: Option[Int], fn: I => F[Unit])
    extends Episode[F, I, O]

object Episode {

  private[api] def pure[F[_], I, A](a: A): Episode[F, I, A] = Pure(a)

  private[api] def eval[F[_], I, A](fa: F[A]): Episode[F, I, A] = Eval(fa)

  private[api] def first[F[_], I](p: I => Boolean): Episode[F, I, I] = First(p)

  private[api] def next[F[_], I](p: I => Boolean): Episode[F, I, I] = Next(p)

  private[api] implicit def monadErrorInstance[F[_]: ApplicativeError[*[_], Throwable], I]
    : MonadError[Episode[F, I, *], Throwable] =
    new MonadError[Episode[F, I, *], Throwable] with StackSafeMonad[Episode[F, I, *]] {
      println("Constructing monad error instance")

      def pure[A](a: A): Episode[F, I, A] = Pure(a)

      def flatMap[A, B](fa: Episode[F, I, A])(f: A => Episode[F, I, B]): Episode[F, I, B] =
        fa.flatMap(f)

      def handleErrorWith[A](fa: Episode[F, I, A])(f: Throwable => Episode[F, I, A]): Episode[F, I, A] =
        fa.handleErrorWith(f)

      def raiseError[A](e: Throwable): Episode[F, I, A] = Eval(e.raiseError[F, A])
    }

  private[api] implicit def monadInstance[F[_], I]: Monad[Episode[F, I, *]] =
    new StackSafeMonad[Episode[F, I, *]] {
      println("Constructing monad instance")

      def pure[A](a: A): Episode[F, I, A] = Pure(a)

      def flatMap[A, B](
        episode: Episode[F, I, A]
      )(fn: A => Episode[F, I, B]): Episode[F, I, B] =
        episode.flatMap(fn)
    }

  private sealed trait Result[+I, +O] {
    def map[O1](f: O => O1): Result[I, O1] = this match {
      case Matched(o)          => Matched(f(o))
      case same @ Missed(_)    => same
      case same @ Cancelled(_) => same
      case same @ Failed(_)    => same
    }
  }

  private final case class Matched[O](o: O) extends Result[Nothing, O]
  private final case class Missed[I](elem: I) extends Result[I, Nothing]
  private final case class Cancelled[I](elem: I) extends Result[I, Nothing]
  private final case class Failed(e: Throwable) extends Result[Nothing, Nothing]

  private def find[F[_], I, O](
    episode: Episode[F, I, O],
    input: Stream[F, I],
    cancelTokens: List[(I => Boolean, Option[I => F[Unit]])]
  ): Stream[F, (Result[I, O], Stream[F, I])] =
    episode match {
      case Pure(a) =>
        Stream(a).covary[F].map(o => Matched(o) -> input)

      case Protected(episode, onError) =>
        find(episode, input, cancelTokens).flatMap {
          case (Failed(e), rest) => find(onError(e), rest, cancelTokens)
          case other             => Stream(other)
        }

      case Eval(fa) =>
        Stream
          .eval(fa)
          .map(o => Matched(o) -> input)
          .handleErrorWith(e => Stream(Failed(e) -> input))

      case First(p: (I => Boolean)) =>
        def go(in: Stream[F, I]): Pull[F, (Result[I, O], Stream[F, I]), Unit] =
          in.dropWhile(!p(_)).pull.uncons1.flatMap {
            case Some((m, rest)) =>
              Pull.output1(Matched(m.asInstanceOf[O]) -> rest) >> go(rest)

            case None => Pull.done
          }

        go(input).stream

      case Next(p) =>
        def go(in: Stream[F, I]): Pull[F, (Result[I, O], Stream[F, I]), Unit] =
          in.pull.uncons1.flatMap {
            case Some((m, rest)) =>
              cancelTokens.collect { case (p, f) if p(m) => f } match {
                case Nil =>
                  if (p(m)) Pull.output1(Matched(m.asInstanceOf[O]) -> rest)
                  else Pull.output1(Missed(m) -> rest)

                case nonEmpty =>
                  nonEmpty
                    .collect { case Some(f) => f }
                    .traverse(f => Pull.eval(f(m))) >>
                    Pull.output1(Cancelled(m) -> rest)
              }
            case None => Pull.done
          }

        go(input).stream

      case Cancellable(episode, p, f) =>
        find(episode, input, (p, f) :: cancelTokens)

      case Tolerate(episode, limit, fn) =>
        limit match {
          case Some(n) if n <= 0 =>
            find(episode, input, cancelTokens)

          case _ =>
            find(episode, input, cancelTokens).flatMap {
              case (Missed(m), rest) =>
                Stream.eval(fn(m)) >> find(
                  Tolerate(episode, limit.map(_ - 1), fn),
                  rest,
                  cancelTokens
                )

              case res => Stream(res)
            }
        }

      case Bind(prev, fn) =>
        find(prev, input, cancelTokens).flatMap {
          case (Matched(a), rest)   => find(fn(a), rest, cancelTokens)
          case (Missed(m), rest)    => Stream(Missed(m) -> rest)
          case (Cancelled(m), rest) => Stream(Cancelled(m) -> rest)
          case (Failed(e), rest)    => Stream(Failed(e) -> rest)
        }
    }
}
