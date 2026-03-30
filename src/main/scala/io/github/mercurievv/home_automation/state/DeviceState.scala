package io.github.mercurievv.home_automation.state

import io.github.mercurievv.home_automation.TypeSystem
import io.github.mercurievv.home_automation.TypeSystem.StatesT
import io.github.mercurievv.home_automation.rules.EventTypes.Topic

import scala.compiletime.deferred

import cats.data.{Kleisli, OptionT}
import cats.implicits.*
import cats.kernel.Semigroup
import cats.mtl.Stateful
import cats.{Id, Monad}

import cats.effect.kernel.{Async, Ref, Temporal}
import cats.effect.std.MapRef
import cats.effect.{Concurrent, kernel}

import io.circe.JsonObject

import doobie.Meta
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
        session
          .publish(id.value + "/get", "{\"state\": \"\"}".getBytes.toVector)
          .as(None)
    }
}

// A layered key lookup: try each accessor in order, return first Some.
// Combination via SemigroupK: accessor1 <+> accessor2
// Add more layers later the same way.
type Accessor[F[_], K, V] = Kleisli[OptionT[F, *], K, V]

object Accessor:

  def fromMapRef[F[_]: Monad, K, V](mr: MapRef[F, K, Option[V]]): Accessor[F, K, V] =
    Kleisli(k => OptionT(mr(k).get))

  def fromDataSource[F[_]: Monad, K, V](source: DataSource[F, K, V]): Accessor[F, K, V] =
    Kleisli(k => OptionT(source.fetch(k)))

trait TypeSystemWithMeta extends TypeSystem:
  given eventIdMeta: Meta[EventId] = deferred
  given eventStateMeta: Meta[EventState] = deferred

object PersistentDeviceState {

  def create[F[_]: Async, TS <: TypeSystemWithMeta](ts: TS)
    : cats.effect.kernel.Resource[F, KVStore[F, ts.EventId, ts.EventState]] = ???
  // import ts.given
//    KVStore.file[F, ts.EventId, ts.EventState]("./db/")

  def getAccessors[F[_], K, V](persistentStore: KVStore[F, K, V]) =
    val getter: Accessor[F, K, V] = Kleisli(k => OptionT(persistentStore.get(k)))
    val setter: Kleisli[F, (K, V), Unit] = Kleisli(
      (
        k,
        v,
      ) => persistentStore.put(k, v),
    )
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
        // val bb: Accessor[F, K, V] = Alternative[Kleisli[OptionT[F, *], K, V]].combineK(Accessor.fromMapRef(mapRef), Accessor.fromMapRef(mapRef))
        val accessor: Accessor[F, K, V] =
          Accessor.fromMapRef(mapRef) combineK Accessor.fromDataSource(deviceStateSource)
        val mapRefSetter: Kleisli[F, (K, V), Unit] = Kleisli(
          (
            k,
            v,
          ) => mapRef.apply(k).update(_ |+| v.some),
        )
        (k: K) =>
          new Stateful[F, Option[V]] {
            override def monad: Monad[F] = summon

            override def get: F[Option[V]] = accessor(k).value

            override def set(s: Option[V]): F[Unit] = mapRef(k).update(_ |+| s)
          }
    }
