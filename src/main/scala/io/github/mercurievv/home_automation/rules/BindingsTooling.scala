package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, OnOffState, Out}
import io.github.mercurievv.home_automation.rules.Devices.{InputAction, OutputAction}

import cats.MonadThrow
import cats.arrow.{Arrow, ArrowChoice}
import cats.data.Kleisli
import cats.implicits.*

import io.circe.{Decoder, Json, JsonObject}

import monocle.syntax.all.*
import org.typelevel.log4cats.Logger

class BindingsTooling[F[_]: {MonadThrow, Logger}]:

  private val decodeLightStateF =
    Kleisli[F, JsonObject, LightState](input =>
      Json
        .fromJsonObject(input)
        .as[LightState]
        .fold(f =>
                println(s"$f $input")
                LightState(state = OnOffState.Off)
              ,
              identity,
        )
        .pure[F],
    )

  type -->[A, B] = Kleisli[F, A, B]

  val toggle: (LightState, Unit) --> Maybe[LightState] = Arrow[-->].lift { case (lightState, _) =>
    lightState.focus(_.state).modify(_.toggle).asRight[Unit]
  }

  def bindAction[-->[_, _]: ArrowChoice, Action, OutT](
    in: InputAction[Action],
    out: OutputAction[OutT],
    decision: (OutT, Action) --> Maybe[OutT],
  ): (In[EntityId], (Out[EntityId], EntityId --> JsonObject => In[JsonObject] --> Maybe[Out[JsonObject]])) = {
    val getAction = Arrow[-->].lift(in.action).map(_.toRight(()))
    given Decoder[OutT] = out.decoder
    (
      in.id,
      (
        out.id,
        createActionForJson[-->, In[JsonObject], Action, OutT](getAction, out.id, _, decision)
          .map(_.map(v => Out(out.encoder.apply(v).asObject.get))),
      ),
    )
  }

  def createActionForJson[-->[_, _]: ArrowChoice, Input, Action, Output: Decoder](
    filter: Input --> Maybe[Action],
    outputId: Out[EntityId],
    getOutState: EntityId --> JsonObject,
    decision: (Output, Action) --> Maybe[Output],
  ): Input --> Maybe[Output] = createAction[-->, Input, Action, JsonObject, Output](
    filter,
    outputId,
    getOutState,
    v => Decoder[Output].decodeJson(v.toJson).toOption.get,
    decision,
  )

  def createAction[-->[_, _]: ArrowChoice, Input, Action, StateOut, Output](
    filter: Input --> Maybe[Action],
    outputId: Out[EntityId],
    getOutState: EntityId --> StateOut,
    convert: StateOut => Output,
    decision: (Output, Action) --> Maybe[Output],
  ): Input --> Maybe[Output] = {
    val prepare: Action --> Output =
      outputId.pure[-->[Action, *]] >>> Arrow[-->].lift(_.value) >>> getOutState >>> Arrow[-->]
        .lift(convert)
    val fullProcess: Input --> Maybe[Output] =
      filter >>> (
        (prepare &&& Arrow[-->].id[Action]) >>> decision
      ).right[Unit].map(_.flatten)
    fullProcess
  }
