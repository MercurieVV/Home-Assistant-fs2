package io.github.mercurievv.knn.has

import io.github.mercurievv.home_automation.HomeAutomationsPlugin

import cats.effect.{IO, IOApp}

import org.typelevel.log4cats.SelfAwareLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple:
  given SelfAwareLogger[IO] = Slf4jLogger.getLogger[IO]

  val plugin = new HomeAutomationsPlugin

  def run: IO[Unit] =
    plugin.programmF[IO]
