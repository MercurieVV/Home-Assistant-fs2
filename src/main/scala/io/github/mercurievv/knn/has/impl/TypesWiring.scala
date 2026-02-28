package io.github.mercurievv.knn.has.impl

import io.github.mercurievv.knn.has.EventsStreamProcessing
import io.github.mercurievv.knn.has.state.StateUpdate
import io.github.mercurievv.knn.has.{EventProcessing, TypeSystem}

import cats.arrow.Arrow
import cats.data.Kleisli
import cats.effect.std.MapRef
import cats.implicits.{toArrowOps, toComposeOps}
import cats.{Applicative, CommutativeMonad, Id}

import net.sigusr.mqtt.api.Session

import language.experimental.pureFunctions

class TypesWiring[F[_], TS <: TypeSystem](val ts: TS):

  class EPT[TS <: TypeSystem](val typeSystem: TS) extends EventProcessing.Types {
    type InputEvent = typeSystem.InputEvent
    type States = typeSystem.States
    type OutputEvent = typeSystem.OutputEvent
  }
  type EPTTS = EPT[TS]
  val eventProcessingTypes: EPTTS = new EPT[TS](ts)

  class ESPT extends EventsStreamProcessing.Types {
    override type Consumer = Session[F]
    override type Producer = Session[F]
  }
  type ESPTTS = ESPT
  val eventStreamProcessingTypes: ESPTTS = new ESPT()

  type StateUpdateTS[-->[_, _], States] = StateUpdate[-->, ts.InputEvent, ts.EventId, ts.EventState, States]
