package io.github.mercurievv.knn.has

import cats.effect.IO
import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.kernel.Resource
import cats.effect.unsafe.IORuntime
import com.comcast.ip4s.{Host, Port}
import io.github.mercurievv.knn.has.mqtt.Mqtt
import net.sigusr.mqtt.api.{Session, SessionConfig, TransportConfig}
import net.sigusr.mqtt.examples.localSubscriber
import org.pf4j.Plugin

class KnnHomeAutomations extends Plugin {

  var rlz: IO[Unit] = _

  given IORuntime = IORuntime.builder().build()

  override def start(): Unit = {


    val res = Mqtt.loadSettings[IO].toResource.mproduct(Mqtt.create[IO])
    {
      for {
        ((conn, settings), release) <- res.allocated
        _ <- IO {
          rlz = release
        }
        _ <- DataProcessingLogic.consume(conn, settings)
        _ <- IO.println(s"using $conn")
      } yield ()
    }.unsafeRunAsync(e => IO.println(e))
  }

  override def stop(): Unit = {
    if (rlz == null)
      println("WARN. rlz is null, nothing to release")
    else
      (rlz *> IO.println("Stopped")).unsafeRunSync()
  }

  override def delete(): Unit = super.delete()
}
