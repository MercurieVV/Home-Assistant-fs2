package io.github.mercurievv.home_automation.mqtt

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.home_automation.rules.EventTypes.{In, Out, Topic}

import cats.FlatMap.ops.toAllFlatMapOps
import cats.data.Kleisli
import cats.implicits.{catsSyntaxSemigroup, toComposeOps}

import io.circe.{Json, JsonObject}

import net.sigusr.mqtt.api.Message

object MessageCoders {

  val decodeMessage: Message => List[In[(Topic, JsonObject)]] = { case Message(topic, payload) =>
    val jsonObjects: Kleisli[List, Vector[Byte], In[(Topic, JsonObject)]] =
      (
        (v: Vector[Byte]) =>
          val bodyString = new String(v.toArray, "UTF-8")
          io.circe.parser.parse(bodyString).toOption.toList,
      ).k >>> (
        ((_: Json).asObject.toList).k |+| ((_: Json).asArray.toList.flatten.flatMap(_.asObject).toList).k
      ).tupleLeft(Topic.parse(topic).right.get)
        .map(In.apply)

    jsonObjects.apply(payload)
  }

  val encodeMessage: Out[(Topic, JsonObject)] => Message = { case Out(topic, eventState) =>
    Message(
      topic.value,
      io.circe.Json.fromJsonObject(eventState).noSpaces.getBytes("UTF-8").toVector,
    )
  }
}
