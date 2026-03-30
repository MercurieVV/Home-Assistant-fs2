package io.github.mercurievv.cats

import cats.arrow.{Arrow, FunctionK}
import cats.data.OptionT
import cats.implicits.*
import cats.{Applicative, Id, Monad, MonoidK, Traverse, ~>}

import cats.effect.kernel.Resource

package object arrow {

  extension [G[_, _]: Arrow, A](g: G[Unit, A]) {
    def const[From]: G[From, A] = Arrow[G].lift[From, Unit](_ => ()) >>> g
  }
}

def composeMonads[F[_]: Monad, G[_]: {Monad, Traverse}]: Monad[[a] =>> F[G[a]]] = new Monad[[a] =>> F[G[a]]] {
  override def pure[A](x: A): F[G[A]] = x.pure[G].pure[F]

  override def flatMap[A, B](fa: F[G[A]])(f: A => F[G[B]]): F[G[B]] = fa.flatMap(la =>
    val flb = la.traverse(f).map(_.flatten)
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

def createApplicativeMonoidK[F[_]: Applicative, G[_]: MonoidK]: MonoidK[[a] =>> F[G[a]]] =
  new MonoidK[[a] =>> F[G[a]]] {
    override def empty[A]: F[G[A]] = MonoidK[G].empty.pure[F]
    override def combineK[A](
      x: F[G[A]],
      y: F[G[A]],
    ): F[G[A]] = (x, y).mapN { case (x_, y_) => MonoidK[G].combineK(x_, y_) }
  }

given fo2OptT: [F[_]] => FunctionK[[a] =>> F[Option[a]], OptionT[F, *]] =
  new FunctionK[[a] =>> F[Option[a]], OptionT[F, *]] {
    override def apply[A](fa: F[Option[A]]): OptionT[F, A] = OptionT(fa)
  }

def Id2Resource[F[_]]: Id ~> Resource[F, *] = new FunctionK {
  def apply[a](fa: Id[a]) = fa.pure[Resource[F, *]]
}
