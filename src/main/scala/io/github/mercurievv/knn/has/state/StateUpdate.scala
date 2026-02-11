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
  States
](
   getStates: Unit --> States,
   getEventId: EntityState --> EntityId,
   updateEntityState: (States, (EntityState, EntityId)) --> Unit,
 ):
  val apply: EntityState --> Unit = (
    getStates.const &&& (
      Arrow[-->].id[EntityState] &&&
        getEventId
      )) >>> updateEntityState

object StateUpdate:
  def refMapUpdate[
    F[_],
    EntityId,
    EntityState: Monoid,
    States <: MapRef[F, EntityId, EntityState]
  ]: Kleisli[F, (States, (EntityState, EntityId)), Unit] = Kleisli {
    case (states, (inputEvent, id)) => states(id).update(_ |+| inputEvent)
  }