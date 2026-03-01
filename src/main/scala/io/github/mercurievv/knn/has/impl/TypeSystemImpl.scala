package io.github.mercurievv.knn.has.impl

import io.circe.JsonObject
import io.github.mercurievv.knn.has.Wiring.TypeSystemWithStates

class TypeSystemImpl[F[_]] extends TypeSystemWithStates[F] {

  override type EventId = String
  override type EventState = JsonObject
  extension (e: InputEvent) {
    def eventId: EventId = e._1
    def eventState: EventState = e._2
  }
}
