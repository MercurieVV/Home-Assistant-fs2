package io.github.mercurievv.knn.has

import cats.arrow.Arrow
import io.github.mercurievv.knn.has.state.StateUpdate
import cats.data.Kleisli
import cats.effect.std.MapRef
import cats.implicits.*
import cats.kernel.Monoid
import cats.{Applicative, CommutativeMonad}
import fs2.*
import net.sigusr.mqtt.api.{Message, Session}

object Wiring {

  def wire[F[_] : CommutativeMonad](ts: TypeSystem)(using Monoid[ts.EventState]) = {
    import ts.*
    type StatesS = MapRef[F, EventId, Option[EventState]]
    type --->[A, B] = Kleisli[F, A, B]
    type ===>[A, B] = Kleisli[Stream[F, _], A, B]

    val inputEvents: Unit ---> InputEvent = Kleisli(_ => ???)
    val states: StatesS = ??? // ] = MapRef.ofSingleImmutableMap[F, EventId, EventState]()
    val stateUpdate = StateUpdate.refMapStateUpdate[F, InputEvent, EventId, EventState, StatesS](
      getStates = Kleisli.pure(states),
      getEventId = Kleisli.fromFunction(_.eventId),
      getEntityState = Kleisli.fromFunction(_.eventState),
    )
    val ept = new EventProcessing.Types {
      override type InputEvent = Message
      override type States = this.type
      override type OutputEvent = this.type
    }
    val ep = new EventProcessing[--->, ept.type ] {
      override val t: ept.type = ept

      import t.*

      override val updateState: InputEvent ---> States = ???
      override val makeDecision: (InputEvent, States) ---> Option[OutputEvent] = ???
    }
    val blti = new BusinessLogic.Types {
      override type Consumer = Session[F]
      override type Producer = Session[F]
    }
    val bl = new BusinessLogic[===>, --->, blti.type, ept.type ] {
      override val blt = blti
      import this.blt.*

      override val ep: EventProcessing[--->, ept.type ] = ep

      override val consume: Consumer ===> (Unit ---> ep.t.InputEvent) = Kleisli((_: Consumer).messages).map(m => Arrow[--->].lift(_ => m))
      override val produce: Producer ===> (ep.t.OutputEvent ---> Unit) = ???//Kleisli((_: Producer).publish).mapK(p => p.flatMap(_ => ))
    }
    bl.run
  }
}