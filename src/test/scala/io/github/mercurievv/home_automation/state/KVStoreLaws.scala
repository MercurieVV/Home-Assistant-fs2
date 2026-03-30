package io.github.mercurievv.home_automation.state

import cats.syntax.all.*

import cats.effect.IO
import cats.effect.kernel.Resource

import munit.CatsEffectSuite
import org.scalacheck.Gen

/** Abstract law suite for any KVStore[IO, K, V] implementation.
  *
  * Laws checked:
  *   1. empty-get — get on fresh store returns None
  *   2. put-get — get after put returns that value
  *   3. put-overwrite — second put on same key wins
  *   4. delete-get — get after delete returns None
  *   5. all-contents — all returns every inserted entry
  *   6. delete-removes — deleted key absent from all
  *   7. idempotent-put — putting same key/value twice produces no duplicate records
  *   8. delete-nonexistent — deleting absent key is a no-op
  */
abstract class KVStoreLaws[K, V] extends CatsEffectSuite:

  def storeResource: Resource[IO, KVStore[IO, K, V]]
  def genKey: Gen[K]
  def genValue: Gen[V]

  private def withStore[A](f: KVStore[IO, K, V] => IO[A]): IO[A] = storeResource.use(f)

  /** Sample a value, retrying until Gen produces one. */
  private def sample[A](g: Gen[A]): IO[A] =
    IO(g.sample).flatMap:
      case Some(v) => IO.pure(v)
      case None    => sample(g)

  // ---- laws ----------------------------------------------------------

  test("law: empty-get — missing key returns None") {
    withStore { store =>
      sample(genKey).flatMap(k => store.get(k).map(assertEquals(_, None)))
    }
  }

  test("law: put-get — get returns value after put") {
    withStore { store =>
      for
        k <- sample(genKey)
        v <- sample(genValue)
        _ <- store.put(k, v)
        r <- store.get(k)
      yield assertEquals(r, Some(v))
    }
  }

  test("law: put-overwrite — second put wins") {
    withStore { store =>
      for
        k  <- sample(genKey)
        v1 <- sample(genValue)
        v2 <- sample(genValue)
        _  <- store.put(k, v1)
        _  <- store.put(k, v2)
        r  <- store.get(k)
      yield assertEquals(r, Some(v2))
    }
  }

  test("law: delete-get — get returns None after delete") {
    withStore { store =>
      for
        k <- sample(genKey)
        v <- sample(genValue)
        _ <- store.put(k, v)
        _ <- store.delete(k)
        r <- store.get(k)
      yield assertEquals(r, None)
    }
  }

  test("law: all-contents — all contains every inserted entry") {
    withStore { store =>
      for
        n     <- sample(Gen.choose(1, 8))
        pairs <- sample(
          Gen
            .listOfN(n, Gen.zip(genKey, genValue))
            .map(_.distinctBy(_._1.toString))
            .suchThat(_.nonEmpty),
        )
        _ <- pairs.traverse_ {
          (
            k,
            v,
          ) => store.put(k, v)
        }
        m <- store.all(())
      yield pairs.foreach {
        (
          k,
          v,
        ) => assertEquals(m.get(k), Some(v))
      }
    }
  }

  test("law: delete-removes — deleted key absent from all") {
    withStore { store =>
      for
        k <- sample(genKey)
        v <- sample(genValue)
        _ <- store.put(k, v)
        _ <- store.delete(k)
        m <- store.all(())
      yield assert(!m.contains(k))
    }
  }

  test("law: idempotent-put — putting same key/value twice produces no duplicate records") {
    withStore { store =>
      for
        k <- sample(genKey)
        v <- sample(genValue)
        _ <- store.put(k, v)
        _ <- store.put(k, v)
        m <- store.all(())
      yield assertEquals(m.count(_._1 == k), 1)
    }
  }

  test("law: delete-nonexistent — deleting absent key is a no-op") {
    withStore { store =>
      sample(genKey).flatMap(k => store.delete(k).map(_ => ()))
    }
  }
