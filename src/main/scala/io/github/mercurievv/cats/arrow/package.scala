package io.github.mercurievv.cats

import cats.arrow.Arrow
import cats.implicits.toComposeOps

package object arrow {
  extension [G[_, _] : Arrow, A](g: G[Unit, A]) {
    def const[From]: G[From, A] = Arrow[G].lift[From, Unit](_ => ()) >>> g
  }
}
