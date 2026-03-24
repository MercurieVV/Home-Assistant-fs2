package io.github.mercurievv.home_automation.mqtt

import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, Out}

import io.circe.JsonObject

import net.sigusr.mqtt.api.Message

object MessageCoders {

  val decodeMessage: Message => In[(EntityId, JsonObject)] = { case Message(topic, payload) =>
    val jsonObject = io.circe.parser
      .parse(new String(payload.toArray, "UTF-8"))
      .toOption
      .flatMap(_.asObject)
      .getOrElse(io.circe.JsonObject.empty)
    In((EntityId(topic), jsonObject))
  }

  val encodeMessage: Out[(EntityId, JsonObject)] => Message = { case Out(eventId, eventState) =>
    Message(
      eventId.value,
      io.circe.Json.fromJsonObject(eventState).noSpaces.getBytes("UTF-8").toVector,
    )
  }
}
