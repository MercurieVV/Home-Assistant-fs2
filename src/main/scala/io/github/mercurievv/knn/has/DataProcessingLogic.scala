package io.github.mercurievv.knn.has

import cats.effect.ExitCode
import cats.effect.kernel.{Async, Sync}
import cats.implicits.*
import io.github.mercurievv.knn.has.mqtt.Mqtt
import fs2.*
import fs2.concurrent.SignallingRef
import net.sigusr.mqtt.api.QualityOfService.*
import net.sigusr.mqtt.api.{Message, QualityOfService, Session}
import org.typelevel.log4cats.Logger


object DataProcessingLogic {
  private val stopTopic: String = s"addons/stop"

  def consume[F[_] : {Async, Logger}](settings: Mqtt.MqttSettings, session: Session[F]): F[ExitCode] = {
    val subscribedTopics: Vector[(String, QualityOfService)] = Vector(
      (stopTopic, ExactlyOnce),
      (settings.topic, AtMostOnce)
    )
    val mqttProgram: SignallingRef[F, Boolean] => Stream[F, Unit] =
      stopSignal =>
        Stream.bracket(session.subscribe(subscribedTopics))(_ => session.unsubscribe(subscribedTopics.map(_._1))) >>
          session.messages
            .evalMap(processMessages(stopSignal))
            .interruptWhen(stopSignal)
            .drain
    SignallingRef[F, Boolean](false).flatMap(mqttProgram(_).compile.drain).as(ExitCode.Success)
  }.handleErrorWith(_ => ExitCode.Error.pure)

  private def processMessages[F[_] : {Sync, Logger}](stopSignal: SignallingRef[F, Boolean]): Message => F[Unit] = {
    case Message(DataProcessingLogic.stopTopic, pl) =>
      Logger[F].info(s"Stop signal received: ${new String(pl.toArray, "UTF-8")}") *> stopSignal.set(true)
    case Message(topic, payload) =>
      Logger[F].info(
        s"Topic ${scala.Console.CYAN}$topic${scala.Console.RESET}: " +
          s"${scala.Console.BOLD}${new String(payload.toArray, "UTF-8")}${scala.Console.RESET}"
      )
  }
}
