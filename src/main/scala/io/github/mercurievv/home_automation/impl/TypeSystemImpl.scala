package io.github.mercurievv.home_automation.impl

import cats.Functor
import io.github.mercurievv.home_automation.Wiring.TypeSystemWithStates
import io.circe.JsonObject
import cats.implicits.toFunctorOps
import io.github.mercurievv.home_automation.rules.EventTypes.EntityId

class TypeSystemImpl[F[_]] extends TypeSystemWithStates[F] {

  override type EventId = EntityId
  override type EventState = JsonObject

  extension [F[_]: Functor](e: Event[F]) {
    def eventId: F[EventId] = e.map(_._1)
    def eventState: F[EventState] = e.map(_._2)
  }
}
