package io.github.mercurievv.home_automation

import io.github.mercurievv.home_automation.TypeSystem.StatesT
import io.github.mercurievv.home_automation.rules.EventTypes.{In, Out}

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.implicits.*
import cats.mtl.Stateful
import cats.{Functor, Id, Monad}

import cats.effect.{IO, Ref}

import munit.CatsEffectSuite
import net.sigusr.mqtt.api.Session
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class WiringTest extends CatsEffectSuite {

  object TestTS extends TypeSystem {
    override type EventId = String
    override type EventState = String
    override type States = StatesT[IO, EventId, EventState]

    extension [F[_]: Functor](e: Event[F]) {
      def eventId: F[EventId] = e.map(_._1)
      def eventState: F[EventState] = e.map(_._2)
    }
  }

  given SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  val noopStates: TestTS.States = _ =>
    new Stateful[IO, Option[String]] {
      def monad: Monad[IO] = Monad[IO]
      def get: IO[Option[String]] = IO.pure(None)
      def set(s: Option[String]): IO[Unit] = IO.unit
    }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Null"))
  def stubSession[F[_]]: Session[F] = null.asInstanceOf[Session[F]]

  test("wire routes input event through decision-making to publish") {
    val inputEvent: TestTS.InputEvent = In(("sensor/1", "on"))
    val outputEvent: TestTS.OutputEvent = Out(("light/1", "on"))

    for {
      publishedRef <- Ref.of[IO, Option[TestTS.OutputEvent]](None)

      pipeline = Wiring.wire[IO, Id, Id](TestTS)(
        consume        = Kleisli.pure(inputEvent),
        produce        = Kleisli.pure(Kleisli(oe => publishedRef.set(Some(oe)))),
        decisionMaking = Kleisli.pure(outputEvent),
        addLogContext  = _ => Map.empty,
        idToStream     = FunctionK.id[Id],
      )

      (event, publish) = pipeline.run(((TestTS, noopStates), stubSession[IO]))

      _         <- IO(assertEquals(event, inputEvent))
      _         <- publish.run(inputEvent)
      published <- publishedRef.get
      _         <- IO(assertEquals(published, Some(outputEvent)))
    } yield ()
  }
}
