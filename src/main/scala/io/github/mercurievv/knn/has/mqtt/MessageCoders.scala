package io.github.mercurievv.knn.has.mqtt

import io.circe.JsonObject

import net.sigusr.mqtt.api.Message

object MessageCoders {

  val decodeMessage: Message => (String, JsonObject) = { case Message(topic, payload) =>
    val jsonObject = io.circe.parser
      .parse(new String(payload.toArray, "UTF-8"))
      .toOption
      .flatMap(_.asObject)
      .getOrElse(io.circe.JsonObject.empty)
    (topic, jsonObject)
  }

  val encodeMessage: ((String, JsonObject)) => Message = { case (eventId, eventState) =>
    Message(
      eventId,
      io.circe.Json.fromJsonObject(eventState).noSpaces.getBytes("UTF-8").toVector,
    )
  }
}
