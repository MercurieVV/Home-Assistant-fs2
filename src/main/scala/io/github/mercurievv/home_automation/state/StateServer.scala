package io.github.mercurievv.home_automation.state

import io.github.mercurievv.cats.arrow.kleisli.*

import java.net.InetSocketAddress

import cats.Applicative
import cats.data.Kleisli
import cats.implicits.*

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Dispatcher

import io.circe.syntax.*
import io.circe.{Encoder, Json}

import com.sun.net.httpserver.HttpServer
import org.typelevel.log4cats.SelfAwareStructuredLogger

object StateServer {

  def resource[F[_]: Async](
    port: Int,
    snapshot: F[String],
  ): Resource[F, Unit] =
    Dispatcher.parallel[F].flatMap { dispatcher =>
      Resource
        .make(
          Async[F].delay {
            val server = HttpServer.create(new InetSocketAddress(port), 0)
            server.createContext(
              "/state",
              exchange => {
                val body = dispatcher.unsafeRunSync(snapshot).getBytes("UTF-8")
                exchange.getResponseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.length.toLong)
                val os = exchange.getResponseBody
                os.write(body)
                os.close()
              },
            )
            server.start()
            server
          },
        )(server => Async[F].delay(server.stop(0)))
        .void
    }

  def createStateServer[F[_]: {SelfAwareStructuredLogger, Async, Applicative}, K, V: Encoder]
    : Kleisli[Resource[F, *], F[Map[K, V]], Unit] = (
    (getState: F[Map[K, V]]) => {
      val snapshot: F[String] = getState.map { map =>
        Json
          .obj(map.toSeq.map { case (k, v) => k.toString -> v.asJson }*)
          .noSpaces
      }
      StateServer.resource[F](8668, snapshot)
    },
  ).k
}
