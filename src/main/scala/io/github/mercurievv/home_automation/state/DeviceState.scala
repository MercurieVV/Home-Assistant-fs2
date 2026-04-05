package io.github.mercurievv.home_automation.state

import io.github.mercurievv.cats.arrow.kleisli.*
import io.github.mercurievv.cats.createApplicativeMonoidK
import io.github.mercurievv.home_automation.TypeSystem
import io.github.mercurievv.home_automation.TypeSystem.StatesT
import io.github.mercurievv.home_automation.rules.EventTypes.Topic

import scala.compiletime.deferred

import cats.data.Kleisli
import cats.implicits.*
import cats.kernel.Semigroup
import cats.mtl.Stateful
import cats.{Id, Monad, MonoidK}

import cats.effect.kernel.{Async, Temporal}
import cats.effect.std.MapRef
import cats.effect.{Concurrent, Resource, kernel}

import io.circe.JsonObject

import doobie.Meta
import fetch.{Data, DataSource}
import net.sigusr.mqtt.api.Session

object DeviceState extends Data[Topic, JsonObject] {
  override def name: String = "entity id"

  def source[F[_]: Temporal](session: Session[F]): DataSource[F, Topic, JsonObject] =
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
type Accessor[F[_], K, V] = Kleisli[F, K, V]

trait TypeSystemWithMeta extends TypeSystem:
  given eventIdMeta: Meta[EventId] = deferred
  given eventStateMeta: Meta[EventState] = deferred

object PersistentDeviceState {

  def create[F[_]: Async, TS <: TypeSystemWithMeta](ts: TS): Resource[
    F,
    (KVStore[F, ts.EventId, ts.EventState], AtomicUpdate[F, ts.EventId, ts.EventState]),
  ] =
    KVStore.create[F, ts.EventId, ts.EventState]("./db/")
}

object DeviceStateAcessor:

  given createStates: [F[_]: Async, K, V: Semigroup]
    => Kleisli[
      Id,
      (MapRef[F, K, Option[V]], KVStore[F, K, V], AtomicUpdate[F, K, V], DataSource[F, K, V]),
      StatesT[F, K, V],
    ] =
    Kleisli { (mapRef, kvstore, _, deviceStateSource) =>
      type FO[a] = F[Option[a]]

      given MonoidK[FO] = createApplicativeMonoidK
      val persistedAccessor: Accessor[FO, K, V] =
        Kleisli[FO, K, V]((k: K) => mapRef(k).get) <+> kvstore.get.toContext
      val getter: Accessor[FO, K, V] =
        persistedAccessor <+>
          Kleisli[FO, K, V]((k: K) => deviceStateSource.fetch(k))

      def updatter(f: Option[V] => Option[V]): Kleisli[F, K, Unit] =
        Kleisli((k: K) => mapRef(k).update(f))

      /*
      def updatter(f: Option[V] => Option[V]): Kleisli[F, K, Unit] =
        (Kleisli((k: K) => mapRef(k).update(f)) &&& (
          (Kleisli.ask[F, K] &&& Kleisli.pure(f)) >>> atomicUpdate.update
        )).void
       */

      (k: K) =>
        new Stateful[F, Option[V]] {
          override def monad: Monad[F] = summon

          override def get: F[Option[V]] = getter(k)

          override def set(s: Option[V]): F[Unit] = modify(_ |+| s)

          // Override to avoid calling deviceStateSource.fetch (which publishes {topic}/get → feedback loop).
          // State updates from incoming events should only consult persisted state (mapRef + kvstore).
          override def modify(f: Option[V] => Option[V]): F[Unit] =
            updatter(f)(k)
        }
    }
