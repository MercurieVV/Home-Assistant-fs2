package io.github.mercurievv.home_automation.state

import io.github.mercurievv.home_automation.TypeSystem.StatesT
import io.github.mercurievv.home_automation.rules.EventTypes.Topic

import scala.concurrent.duration.DurationInt

import cats.data.Kleisli
import cats.implicits.*
import cats.kernel.Semigroup
import cats.mtl.Stateful
import cats.{Id, Monad}

import cats.effect.kernel.{Async, Ref, Temporal}
import cats.effect.std.MapRef
import cats.effect.{Concurrent, kernel}

import io.circe.JsonObject

import fetch.{Data, DataCache, DataSource}
import net.sigusr.mqtt.api.Session
import org.typelevel.log4cats.LoggerFactory

case class MapRefCache[F[_]: Monad, K, V: Semigroup](mr: MapRef[F, K, Option[V]]) extends DataCache[F] {

  override def lookup[I, A](
    i: I,
    data: Data[I, A],
  ): F[Option[A]] = mr.apply(i.asInstanceOf[K]).get.map(_.map(_.asInstanceOf[A]))

  override def insert[I, A](
    i: I,
    v: A,
    data: Data[I, A],
  ): F[DataCache[F]] = mr
    .apply(i.asInstanceOf[K])
    .update(_ |+| v.asInstanceOf[V].some)
    .as(this)
}

object DeviceState extends Data[Topic, JsonObject] {
  override def name: String = "entity id"

  def source[F[_]: {Temporal, LoggerFactory}](session: Session[F]): DataSource[F, Topic, JsonObject] =
    val logger = LoggerFactory.getLogger[F]
    new DataSource[F, Topic, JsonObject] {

      override def data: Data[Topic, JsonObject] = DeviceState

      override given CF: Concurrent[F] = kernel.Concurrent[F]

      override def fetch(id: Topic): F[Option[JsonObject]] =
        import io.circe.parser.parse
        session
          .publish(id.value + "/get", "{\"state\": \"\"}".getBytes.toVector) *> session.messages
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

object DeviceStateAcessor:

  given createStates
    : [F[_]: Async, K, V: Semigroup] => Kleisli[Id, (DataSource[F, K, V], Ref[F, Map[K, V]]), StatesT[F, K, V]] =
    Kleisli {
      (
        deviceStateSource: DataSource[F, K, V],
        ref: Ref[F, Map[K, V]],
      ) =>

        val mapRef: MapRef[F, K, Option[V]] = MapRef.fromSingleImmutableMapRef(ref)
        (k: K) =>
          new Stateful[F, Option[V]] {
            override def monad: Monad[F] = summon

            override def get: F[Option[V]] = mapRef(k).get

            override def set(s: Option[V]): F[Unit] = mapRef(k).update(_ |+| s)
          }
    }
