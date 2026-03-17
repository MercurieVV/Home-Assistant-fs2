package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.home_automation.rules.Devices.{InputAction, OutputAction}
import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, OnOffState, Out, St}

import cats.Monad
import cats.arrow.{Arrow, ArrowChoice}
import cats.data.Kleisli
import cats.implicits.*

import io.circe.{Decoder, JsonObject}

import monocle.syntax.all.*

class BindingsTooling[F[_]: Monad]:
  type -->[A, B] = Kleisli[F, A, B]

  val toggle: (St[LightState], Unit) --> Out[LightState] = Arrow[-->].lift { case (lightState, _) =>
    Out(lightState.value.focus(_.state).modify(_.toggle))
  }

  def bindStatefulAction[-->[_, _]: ArrowChoice, Action, T](
    in: InputAction[Action],
    out: OutputAction[T],
    decision: (St[T], Action) --> Out[T],
  ): (In[EntityId], (Out[EntityId], EntityId --> JsonObject => In[JsonObject] --> Out[JsonObject])) =
    bindAction[-->, Action, T](
      in,
      out,
      Arrow[-->].lift[T, St[T]](St.apply).first >>> decision >>> Arrow[-->].lift[Out[T], T](_.value),
    )

  def bindAction[-->[_, _]: ArrowChoice, Action, OutT](
    in: InputAction[Action],
    out: OutputAction[OutT],
    decision: (OutT, Action) --> OutT,
  ): (In[EntityId], (Out[EntityId], EntityId --> JsonObject => In[JsonObject] --> Out[JsonObject])) = {
    val getAction = Arrow[-->].lift(in.action)
    given Decoder[OutT] = out.decoder
    (
      in.id,
      (
        out.id,
        createActionForJson[-->, In[JsonObject], Action, OutT](getAction, out.id, _, decision)
          .map(v => Out(out.encoder.apply(v).asObject.get)),
      ),
    )
  }

  def createActionForJson[-->[_, _]: ArrowChoice, Input, Action, Output: Decoder](
    filter: Input --> Action,
    outputId: Out[EntityId],
    getOutState: EntityId --> JsonObject,
    decision: (Output, Action) --> Output,
  ): Input --> Output = createAction[-->, Input, Action, JsonObject, Output](
    filter,
    outputId,
    getOutState,
    v => Decoder[Output].decodeJson(v.toJson).toOption.get,
    decision,
  )

  def createAction[-->[_, _]: ArrowChoice, Input, Action, StateOut, Output](
    filter: Input --> Action,
    outputId: Out[EntityId],
    getOutState: EntityId --> StateOut,
    convert: StateOut => Output,
    decision: (Output, Action) --> Output,
  ): Input --> Output = {
    val prepare: Action --> Output =
      outputId.pure[-->[Action, *]] >>> Arrow[-->].lift(_.value) >>> getOutState >>> Arrow[-->]
        .lift(convert)
    val fullProcess: Input --> Output =
      filter >>> (
        (prepare &&& Arrow[-->].id[Action]) >>> decision
      ) // .map(_.flatten)
    fullProcess
  }
