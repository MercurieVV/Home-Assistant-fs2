package io.github.mercurievv.home_automation.impl

import io.github.mercurievv.home_automation.Wiring.TypeSystemWithStates
import io.github.mercurievv.home_automation.rules.EventTypes.Topic
import io.github.mercurievv.home_automation.state.{KVStore, TypeSystemWithMeta}

import cats.Functor
import cats.implicits.toFunctorOps

import io.circe.{Json, JsonObject}

import doobie.Meta

class TypeSystemImpl[F[_]] extends TypeSystemWithStates[F] with TypeSystemWithMeta {

  override type EventId = Topic
  override type EventState = JsonObject

  extension [F[_]: Functor](e: Event[F]) {
    def eventId: F[EventId] = e.map(_._1)
    def eventState: F[EventState] = e.map(_._2)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  override given eventIdMeta: Meta[EventId] =
    Meta[String].imap(s => Topic.parse(s).fold(throw _, identity))(_.value)

  override given eventStateMeta: Meta[EventState] =
    KVStore.jsonMeta.imap(_.asObject.getOrElse(JsonObject.empty))(Json.fromJsonObject)

}
