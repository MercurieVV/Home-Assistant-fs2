package io.github.mercurievv.cats.arrow.kleisli

import cats.Applicative
import cats.data.Kleisli

import language.experimental.pureFunctions
/*class implicits[F[_]] {
  type K[A, B] = Kleisli[F, A, B]
  type -->[A, B] = Kleisli[F, A, B]
  
  extension [A, B](f: A => B)
    def k[G[_]](using Applicative[G]): Kleisli[G, A, B] =
      Kleisli(a => Applicative[G].pure(f(a)))
}*/
type K[F[_], A, B] = Kleisli[F, A, B]

object K:
  inline def apply[F[_], A, B](f: A -> F[B]): K[F, A, B] = Kleisli(f)

extension [B](b: B) inline def kp[F[_]: Applicative, A]: K[F, A, B] = Kleisli.pure[F, A, B](b)

extension [F[_], B](b: F[B]) inline def k[A]: K[F, A, B] = Kleisli.liftF[F, A, B](b)

extension [A, F[_], B](f: A -> F[B]) inline def k: K[F, A, B] = Kleisli(f)

extension [F[_], A, B](f: (=> A) => F[B])

  inline def bnk0: K[F, () => A, B] =
    Kleisli(thunk => f(thunk()))

  inline def bnk: K[F, A, B] =
    Kleisli(thunk => f(thunk))

extension [A, B](f: A -> B)

  inline def k[F[_]: Applicative]: K[F, A, B] =
    Kleisli.fromFunction(f)
