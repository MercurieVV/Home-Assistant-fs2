package io.github.mercurievv.home_automation.instances

import cats.kernel.{Monoid, Semigroup}
import io.circe.{Json, JsonObject}

object JsonInstances:
  given Semigroup[Json] = new Semigroup[Json]:
    def combine(x: Json, y: Json): Json =
      (x.asObject, y.asObject) match
        case (Some(xo), Some(yo)) =>
          val combined = yo.toList.foldLeft(xo) { case (acc, (key, yv)) =>
            acc(key) match
              case Some(xv) => acc.add(key, combine(xv, yv))
              case None     => acc.add(key, yv)
          }
          Json.fromJsonObject(combined)
        case (Some(_), None) => x
        case (None, Some(_)) => y
        case (None, None)    => y

  given Monoid[JsonObject] = new Monoid[JsonObject]:
    val empty: JsonObject = JsonObject.empty
    def combine(x: JsonObject, y: JsonObject): JsonObject =
      val jsonSemigroup = summon[Semigroup[Json]]
      y.toList.foldLeft(x) { case (acc, (key, yv)) =>
        acc(key) match
          case Some(xv) => acc.add(key, jsonSemigroup.combine(xv, yv))
          case None     => acc.add(key, yv)
      }
