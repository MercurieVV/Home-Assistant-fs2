package io.github.mercurievv.knn.has.state

import cats.Monad
import io.github.mercurievv.cats.arrow.*
import cats.arrow.Arrow
import cats.data.Kleisli
import cats.effect.std.MapRef
import cats.implicits.{catsSyntaxSemigroup, toArrowOps, toComposeOps}
import cats.kernel.Monoid

case class StateUpdate[
  -->[_, _] : Arrow,
  EntityId,
  EntityState,
  States,
](
   getStates: Unit --> States,
   getEventId: EntityState --> EntityId,
   mergeIntoState: (States, (EntityState, EntityId)) --> Unit,
 ):
  val apply: EntityState --> Unit = (
    getStates.const &&& (
      Arrow[-->].id[EntityState] &&&
        getEventId
      )) >>> mergeIntoState

object StateUpdate:
  def refMapStateUpdate[
    F[_]: Monad,
    EntityId,
    EntityState: Monoid,
    States <: MapRef[F, EntityId, EntityState],
  ](
     getStates: Kleisli[F, Unit, States],
     getEventId: Kleisli[F, EntityState, EntityId],
   ): StateUpdate[Kleisli[F, _, _], EntityId, EntityState, States] =
    StateUpdate(getStates, getEventId, refMapUpdate)

  def refMapUpdate[
    F[_],
    EntityId,
    EntityState: Monoid,
    States <: MapRef[F, EntityId, EntityState],
  ]: Kleisli[F, (States, (EntityState, EntityId)), Unit] = Kleisli {
    case (states, (inputEvent, id)) => states(id).update(_ |+| inputEvent)
  }