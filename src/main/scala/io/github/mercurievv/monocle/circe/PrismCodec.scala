package io.github.mercurievv.monocle.circe

import io.circe.{Codec, Decoder, Encoder}

import monocle.Prism

extension [A, B](prism: Prism[A, B])

  def toCodec(
    using ce: Encoder[A],
    cd: Decoder[A],
  ): Codec[B] =
    Codec.from(
      cd.emap(a => prism.getOption(a).toRight(s"Invalid value: $a")),
      ce.contramap(prism.reverseGet),
    )
