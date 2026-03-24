package io.github.mercurievv.home_automation.state

import io.github.mercurievv.home_automation.rules.EventTypes.EntityId

import scala.concurrent.duration.DurationInt

import cats.Monad
import cats.implicits.*
import cats.kernel.Semigroup

import cats.effect.kernel.Temporal
import cats.effect.std.MapRef
import cats.effect.{Concurrent, kernel}

import io.circe.JsonObject

import fetch.{Data, DataCache, DataSource}
import net.sigusr.mqtt.api.Session

case class MapRefCache[F[_]: Monad, K, V: Semigroup](mr: MapRef[F, K, Option[V]]) extends DataCache[F] {

  override def lookup[I, A](
    i: I,
    data: Data[I, A],
  ): F[Option[A]] = mr.apply(i.asInstanceOf[K]).get.map(_.map(_.asInstanceOf[A]))

  override def insert[I, A](
    i: I,
    v: A,
    data: Data[I, A],
  ): F[DataCache[F]] = mr.apply(i.asInstanceOf[K]).update(i => i |+| v.asInstanceOf[V].some).as(this)
}

object DeviceState extends Data[EntityId, JsonObject] {
  override def name: String = "entity id"

  def source[F[_]: Temporal](session: Session[F]): DataSource[F, EntityId, JsonObject] =
    new DataSource[F, EntityId, JsonObject] {

      override def data: Data[EntityId, JsonObject] = DeviceState

      override given CF: Concurrent[F] = kernel.Concurrent[F]

      override def fetch(id: EntityId): F[Option[JsonObject]] =
        import io.circe.parser.parse
        session.publish(id.value + "/get", "{\"state\": \"\"}".getBytes.toVector) *> session.messages
          .filter(_.topic == id.value)
          .head
          .interruptAfter(5.seconds)
          .compile
          .last
          .map(_.flatMap { msg =>
            parse(new String(msg.payload.toArray))
              .flatMap(_.as[JsonObject])
              .toOption
          })
    }
}
