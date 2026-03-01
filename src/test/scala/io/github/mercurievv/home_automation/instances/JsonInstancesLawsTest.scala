package io.github.mercurievv.home_automation.instances

import cats.Eq
import cats.kernel.laws.discipline.{MonoidTests, SemigroupTests}
import io.circe.{Json, JsonObject}
import io.circe.testing.instances.arbitraryJson
import munit.DisciplineSuite
import org.scalacheck.Arbitrary

import JsonInstances.given

class JsonInstancesLawsTest extends DisciplineSuite:

  given Arbitrary[JsonObject] = Arbitrary(
    arbitraryJson.arbitrary.map(_.asObject.getOrElse(JsonObject.empty))
  )

  given Eq[JsonObject] = Eq.instance((a, b) =>
    Json.fromJsonObject(a) == Json.fromJsonObject(b)
  )

  checkAll("Semigroup[Json]", SemigroupTests[Json].semigroup)
  checkAll("Monoid[JsonObject]", MonoidTests[JsonObject].monoid)
