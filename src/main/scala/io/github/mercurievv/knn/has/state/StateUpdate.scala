package io.github.mercurievv.knn.has.state

import io.github.mercurievv.cats.arrow._

import cats.Monad
import cats.arrow.Arrow
import cats.data.Kleisli
import cats.effect.std.MapRef
import cats.implicits.{catsSyntaxOptionId, catsSyntaxSemigroup, toArrowOps, toComposeOps}
import cats.kernel.Monoid

case class StateUpdate[-->[_, _]: Arrow, Event, EntityId, EntityState, States](
  getStates: Unit --> States,
  getEventId: Event --> EntityId,
  getEventState: Event --> EntityState,
  mergeIntoState: (States, (EntityState, EntityId)) --> Unit):

  val apply: Event --> Unit =
    (getStates.const &&& (
      getEventState &&&
        getEventId
    )) >>> mergeIntoState

object StateUpdate:

  def refMapStateUpdate[
    F[_]: Monad,
    Event,
    EntityId,
    EntityState: Monoid,
    States <: MapRef[F, EntityId, Option[EntityState]],
  ](
    getStates: Kleisli[F, Unit, States],
    getEventId: Kleisli[F, Event, EntityId],
    getEntityState: Kleisli[F, Event, EntityState],
  ): StateUpdate[Kleisli[F, _, _], Event, EntityId, EntityState, States] =
    StateUpdate(getStates, getEventId, getEntityState, refMapUpdate)

  def refMapUpdate[F[_], EntityId, EntityState: Monoid, States <: MapRef[F, EntityId, Option[EntityState]]]
    : Kleisli[F, (States, (EntityState, EntityId)), Unit] = Kleisli { case (states, (inputEvent, id)) =>
    states(id).update(_ |+| inputEvent.some)
  }
