package io.github.mercurievv.cats

import cats.arrow.Arrow
import cats.implicits.*
import cats.{Functor, Monad, Traverse}

package object arrow {

  extension [G[_, _]: Arrow, A](g: G[Unit, A]) {
    def const[From]: G[From, A] = Arrow[G].lift[From, Unit](_ => ()) >>> g
  }
}

def composeMonads[F[_]: Monad, G[_]: {Monad, Traverse}]: Monad[[a] =>> F[G[a]]] = new Monad[[a] =>> F[G[a]]] {
  type FG[a] = F[G[a]]
  override def pure[A](x: A): F[G[A]] = x.pure[G].pure[F]

  given Functor[G] = Monad[G]
  override def flatMap[A, B](fa: F[G[A]])(f: A => F[G[B]]): F[G[B]] = fa.flatMap(la =>
    val flb = la.map(f).sequence.map(_.flatten)
    flb,
  )

  override def tailRecM[A, B](a: A)(f: A => F[G[Either[A, B]]]): F[G[B]] =
    Monad[F].tailRecM((Left(a): Either[A, B]).pure[G]) { current =>
      current
        .traverse { case Right(b) => Monad[F].pure((Right(b): Either[A, B]).pure[G]); case Left(a) => f(a) }
        .map(Monad[G].flatten)
        .map(next =>
          next.traverse[Either[G[Either[A, B]], _], B] {
            case Right(b) => Right(b)
            case Left(_)  => Left(next)
          },
        )
    }
}
