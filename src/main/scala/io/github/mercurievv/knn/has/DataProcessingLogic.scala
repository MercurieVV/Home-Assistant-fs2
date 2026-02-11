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
      (settings.topic, AtMostOnce),
      ("tets-topic", AtMostOnce)
    )
    val mqttProgram: SignallingRef[F, Boolean] => Stream[F, Unit] =
      stopSignal =>
        Stream.bracket(session.subscribe(subscribedTopics))(_ => session.unsubscribe(subscribedTopics.map(_._1))) >>
          session.messages
            .evalMap(processMessages(stopSignal)(session))
            .interruptWhen(stopSignal)
            .drain
    SignallingRef[F, Boolean](false).flatMap(mqttProgram(_).compile.drain).as(ExitCode.Success)
  }.handleErrorWith(_ => ExitCode.Error.pure)

  private def processMessages[F[_] : {Sync, Logger}](stopSignal: SignallingRef[F, Boolean])(session: Session[F]): Message => F[Unit] =
    case Message(topic, pl) =>
      val jsonString = new String(pl.toArray, "UTF-8")
      topic match
        case DataProcessingLogic.stopTopic =>
          Logger[F].info(s"Stop signal received: $jsonString") *> stopSignal.set(true)
        case "tets-topic" =>
          Logger[F].info(s"Oho: $jsonString")
        case "zigbee2mqtt/bridge/devices" =>
          Logger[F].info(s"Devices: $jsonString")
        case "zigbee2mqtt/bridge/groups" =>
          Logger[F].info(s"Groups: $jsonString")
        case topic =>
          Logger[F].info(
            s"Topic ${scala.Console.CYAN}$topic${scala.Console.RESET}: " +
              s"${scala.Console.BOLD}$jsonString${scala.Console.RESET}"
          ) >> session.publish("zigbee2mqtt/tets-topic/set", Vector.empty, AtMostOnce)

}
