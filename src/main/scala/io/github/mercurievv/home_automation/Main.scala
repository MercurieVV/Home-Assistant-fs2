package io.github.mercurievv.home_automation

import io.github.mercurievv.home_automation.HomeAutomationsPlugin

import cats.effect.{IO, IOApp}

import org.typelevel.log4cats.*
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.{Slf4jFactory, Slf4jLogger}

object Main extends IOApp.Simple:
  given SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  given LoggerFactory[IO] = Slf4jFactory.create[IO]

  val plugin = new HomeAutomationsPlugin

  def run: IO[Unit] =
    plugin.programmF[IO]
