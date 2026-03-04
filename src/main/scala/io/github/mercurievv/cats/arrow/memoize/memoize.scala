package io.github.mercurievv.cats.arrow.memoize

import cats.data.Kleisli
import cats.syntax.all.*

import cats.effect.kernel.{Concurrent, Deferred, Ref}

object memoize {

  /** Memoizes a Kleisli. The first call for a given key runs the effect and caches the result. Concurrent calls with
    * the same key may compute the value more than once (no locking).
    */
  def apply[F[_]: Concurrent, A, B](k: Kleisli[F, A, B]): F[Kleisli[F, A, B]] =
    Ref.of[F, Map[A, B]](Map.empty).map { ref =>
      Kleisli { a =>
        ref.get.flatMap {
          _.get(a) match {
            case Some(b) => b.pure[F]
            case None    => k.run(a).flatTap(b => ref.update(_ + (a -> b)))
          }
        }
      }
    }

  /** Memoizes a Kleisli with exactly-once semantics. Concurrent callers for the same key wait for the first computation
    * to complete. If the computation fails, all waiters receive the same error.
    */
  def once[F[_]: Concurrent, A, B](k: Kleisli[F, A, B]): F[Kleisli[F, A, B]] =
    Ref.of[F, Map[A, Deferred[F, Either[Throwable, B]]]](Map.empty).map { ref =>
      Kleisli { a =>
        Concurrent[F].deferred[Either[Throwable, B]].flatMap { newDef =>
          ref.modify { m =>
            m.get(a) match {
              case Some(existing) => (m, existing.get.rethrow)
              case None           => (m + (a -> newDef), k.run(a).attempt.flatTap(newDef.complete(_).void).rethrow)
            }
          }.flatten
        }
      }
    }
}
