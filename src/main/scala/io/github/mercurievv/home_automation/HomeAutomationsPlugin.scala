package io.github.mercurievv.home_automation

import io.github.mercurievv.knn.has.DataProcessingLogic
import io.github.mercurievv.knn.has.mqtt.Mqtt

import java.util.concurrent.atomic.AtomicReference

import cats.effect.implicits._
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Console, Dispatcher}
import cats.effect.unsafe.IORuntime
import cats.effect.{ExitCode, FiberIO, IO}
import cats.implicits._

import com.comcast.ip4s.{Host, Port}
import net.sigusr.mqtt.api.{Session, SessionConfig, TransportConfig}
import net.sigusr.mqtt.examples.localSubscriber
import org.pf4j.Plugin
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareLogger}

class HomeAutomationsPlugin extends Plugin {
  given SelfAwareLogger[IO] = Slf4jLogger.getLogger[IO]

  given IORuntime = IORuntime.global

  private val fiberRef: AtomicReference[Option[FiberIO[Unit]]] =
    new AtomicReference[Option[FiberIO[Unit]]](None)

  def programmF[F[_]: {SelfAwareLogger, Async, Console}]: F[Unit] = {
    val pluginResources = Mqtt
      .loadSettings[F]
      .toResource
      .mproduct(Mqtt.create[F])

    pluginResources
      .use {
        DataProcessingLogic.consume(_, _)
      }
      .onError(e =>
        Logger[F].error(e)(s"Plugin program failed: ${e.getMessage}"),
      )
      .flatMap(ec => Logger[F].info(s"Plugin program exited: ${ec}"))
  }

  override def start(): Unit = {
    val newFiber = programmF[IO].start.unsafeRunSync()

    // If start() is called again, stop the previous fiber to avoid leaks
    val previous = fiberRef.getAndSet(Some(newFiber))
    previous.foreach { old =>
      (old.cancel *> old.join.void)
        .handleErrorWith(e =>
          Logger[IO].error(e)(s"Previous run stop failed: $e"),
        )
        .unsafeRunSync()
    }
  }

  override def stop(): Unit =
    fiberRef.getAndSet(None) match {
      case Some(fiber) =>
        (fiber.cancel *> fiber.join.void)
          .handleErrorWith(e =>
            SelfAwareLogger[IO].error(e)(s"Stop failed: $e"),
          )
          .unsafeRunCancelable()

      case None =>
        System.err.println("WARN: stop called but plugin not running")
    }
}
