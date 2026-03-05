package io.github.mercurievv.home_automation.impl

import io.github.mercurievv.home_automation.state.StateUpdate
import io.github.mercurievv.home_automation.{EventProcessing, EventsStreamProcessing, TypeSystem}

import scala.language.experimental.pureFunctions

import net.sigusr.mqtt.api.Session

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
