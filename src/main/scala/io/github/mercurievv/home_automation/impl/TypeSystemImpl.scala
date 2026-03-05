package io.github.mercurievv.home_automation.impl

import io.github.mercurievv.home_automation.Wiring.TypeSystemWithStates

import io.circe.JsonObject

class TypeSystemImpl[F[_]] extends TypeSystemWithStates[F] {

  override type EventId = String
  override type EventState = JsonObject

  extension (e: InputEvent) {
    def eventId: EventId = e._1
    def eventState: EventState = e._2
  }
}
