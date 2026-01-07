package io.github.mercurievv.knn.has

import cats.effect.IO
import cats.effect.implicits.*
import cats.effect.kernel.Resource
import cats.effect.unsafe.IORuntime
import org.pf4j.Plugin

class KnnHomeAutomations extends Plugin{

  var rlz: IO[Unit] = _
  given IORuntime = IORuntime.builder().build()
  override def start(): Unit = {
    val res = Resource.unit[IO]
    {
      for {
        (conn, release) <- res.allocated
        _ <- IO.println(s"using $conn")
        _ <- IO{rlz = release}
      } yield ()
    }.unsafeRunAsync(e => IO.println(e))
  }

  override def stop(): Unit =
    rlz.unsafeRunSync()

  override def delete(): Unit = super.delete()
}
