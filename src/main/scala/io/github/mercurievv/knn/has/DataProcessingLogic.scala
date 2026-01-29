package io.github.mercurievv.knn.has

import cats.data.Kleisli
import io.github.mercurievv.knn.has.mqtt.Mqtt
import cats.effect.{Concurrent, ExitCode, Temporal}
import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.kernel.{Async, Sync}
import fs2.*
import fs2.concurrent.SignallingRef
import net.sigusr.mqtt.api.QualityOfService.*
import net.sigusr.mqtt.api.{Message, QualityOfService, Session}
import net.sigusr.mqtt.examples.{LocalSubscriber, localSubscriber, logSessionStatus, onSessionError, putStrLn}
import org.typelevel.log4cats.Logger

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object DataProcessingLogic {
  private val stopTopic: String = s"addons/stop"

  def consume[F[_] : {Async, Logger}](settings: Mqtt.MqttSettings, session: Session[F]): F[ExitCode] = {
    val subscribedTopics: Vector[(String, QualityOfService)] = Vector(
      (stopTopic, ExactlyOnce),
      (settings.topic, AtMostOnce)
    )
    SignallingRef[F, Boolean](false).flatMap { stopSignal =>
      val sessionStatus = session.state.discrete
        .evalMap(logSessionStatus[F])
        .evalMap(onSessionError[F])
        .interruptWhen(stopSignal)
        .compile
        .drain
      val subscriber = for {
        s <- session.subscribe(subscribedTopics)
        _ <- s.traverse { p =>
          putStrLn[F](
            s"Topic ${scala.Console.CYAN}${p._1}${scala.Console.RESET} subscribed with QoS " +
              s"${scala.Console.CYAN}${p._2.show}${scala.Console.RESET}"
          )
        }
        _ <- Temporal[F].sleep(FiniteDuration(23, TimeUnit.SECONDS))
        _ <- stopSignal.discrete.compile.drain
      } yield ()
      val reader = session.messages.flatMap(processMessages(stopSignal)).interruptWhen(stopSignal).compile.drain
      for {
        _ <- Async[F].racePair(sessionStatus, subscriber.race(reader))
      } yield ExitCode.Success
    }
  }.handleErrorWith(_ => ExitCode.Error.pure)

  private def processMessages[F[_] : Sync](stopSignal: SignallingRef[F, Boolean]): Message => Stream[F, Unit] = {
    case Message(DataProcessingLogic.stopTopic, pl) =>
      Stream.eval(
        putStrLn[F](s"Stop signal received: ${new String(pl.toArray, "UTF-8")}")
      ) *> Stream.exec(stopSignal.set(true))
    case Message(topic, payload) =>
      Stream.eval(
        putStrLn[F](
          s"Topic ${scala.Console.CYAN}$topic${scala.Console.RESET}: " +
            s"${scala.Console.BOLD}${new String(payload.toArray, "UTF-8")}${scala.Console.RESET}"
        )
      )
  }
}
