package io.github.mercurievv.home_automation.mqtt

import io.github.mercurievv.home_automation.rules.EventTypes.{In, Out, Topic}

import io.circe.JsonObject

import net.sigusr.mqtt.api.Message

object MessageCoders {

  val decodeMessage: Message => In[(Topic, JsonObject)] = { case Message(topic, payload) =>
    val jsonObject = io.circe.parser
      .parse(new String(payload.toArray, "UTF-8"))
      .toOption
      .flatMap(_.asObject)
      .getOrElse(io.circe.JsonObject.empty)
    In((Topic.parse(topic).right.get, jsonObject))
  }

  val encodeMessage: Out[(Topic, JsonObject)] => Message = { case Out(topic, eventState) =>
    Message(
      topic.value,
      io.circe.Json.fromJsonObject(eventState).noSpaces.getBytes("UTF-8").toVector,
    )
  }
}
