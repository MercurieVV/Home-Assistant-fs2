package io.github.mercurievv.home_automation.state

import cats.{Id, ~>}
import cats.implicits.toArrowOps
import cats.arrow.Arrow.ops.toAllArrowOps
import cats.arrow.FunctionK
import cats.effect.IO
import cats.effect.kernel.Resource
import io.circe.Json
import io.circe.testing.instances.arbitraryJson
import org.scalacheck.Gen

class SqliteKVStoreLawsTest extends KVStoreLaws[String, Json]:

  import KVStore.given

  import cats.implicits.catsSyntaxApplicativeId
  val Id2Resource: Id ~> Resource[IO, *] = new FunctionK { def apply[a](fa: Id[a]) = fa.pure[Resource[IO, *]] }

  override def storeResource: Resource[IO, KVStore[IO, String, Json]] =
    (KVStore.inMemory[IO] >>> KVStore.fromTransactor("kv_store").mapK(Id2Resource) >>> KVStore
      .fromDbTable[IO, String, Json]).apply(())

  override def genKey: Gen[String] =
    Gen.alphaNumStr.suchThat(_.nonEmpty)

  override def genValue: Gen[Json] =
    arbitraryJson.arbitrary
