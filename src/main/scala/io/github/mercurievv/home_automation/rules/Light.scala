package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, OnOffState, Out}
import io.github.mercurievv.home_automation.rules.Zigbee.{InputAction, OutputAction, StudioLightSwitch_single_right_action}
import cats.{Functor, MonadThrow}
import cats.arrow.{Arrow, ArrowChoice}
import cats.data.Kleisli
import cats.implicits.*
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.optics.JsonPath.root
import io.circe.{Codec, Json, JsonObject}
import monocle.{Fold, Getter, Optional}
import org.typelevel.log4cats.Logger

import language.experimental.pureFunctions

type Maybe[A] = Either[Unit, A]

object Zigbee:

  case class InputAction[Action](
    id: In[EntityId],
    action: In[JsonObject] => Option[Action])
  
  case class OutputAction[OutT](
    id: Out[EntityId],
    encoder: Encoder[OutT],
    decoder: Decoder[OutT],
                              )

  val StudioLightSwitch_single_right_action = InputAction[Unit](
    id     = In(EntityId("zigbee2mqtt/Workroom switch")),
    action = v => Option.when(composed.headOption(v.value).contains("single_right"))(()),
  )
  val StudioLights = OutputAction[LightState](id = Out(EntityId("zigbee2mqtt/studio_lights/set")), decoder = summon, encoder = summon)
  val jsonObject: Getter[JsonObject, Json] = Getter(Json.fromJsonObject)
  private val sla: Optional[Json, String] = root.action.string
  val composed: Fold[JsonObject, String] = Zigbee.jsonObject andThen sla
  val stateOff: JsonObject = JsonObject("state" -> Json.fromString("OFF"))

import io.github.mercurievv.monocle.circe.*
import monocle.syntax.all.*

given Codec[OnOffState] = OnOffState.prism.toCodec
case class LightState(state: OnOffState)
given Codec[LightState] = deriveCodec

class Light[F[_]: {MonadThrow, Logger}]:

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

  type ActionInput = (In[(EntityId, JsonObject)], EntityId --> JsonObject)
  type ActionOutput = Option[Out[(EntityId, JsonObject)]]

  val map: Map[In[EntityId], (Out[EntityId], In[JsonObject] --> Maybe[Out[JsonObject]])] = Map(
    studioLight
  )
  val FE: Functor[[x] =>> F[Either[Unit, x]]] = ???
  val lightSwitch: ActionInput --> ActionOutput =
    // MessageLogger[F].info.bnk.lmap[Input](i => s"Info: $i") *>
    Kleisli[F, ActionInput, ActionOutput] {
      case (input, state) =>
        map.get(input.map(_._1)).map { case (outId, action) =>
          action.apply(input.map(_._2)).map(_.toOption.map((outId, _).tupled))
        }.sequence.map(_.flatten)
    }
/*
        (state(switchId), state(Zigbee.StudioLights)).tupled
          .mproduct(v => decodeLightStateF(v._2))
          .flatMap { case ((switchState, lightState), decodedLightState) =>
            val json: Json = decodedLightState.focus(_.state).modify(_.toggle).asJson
            Logger
              .apply[F]
              .info(
                s"Switch state: ${switchState}, light state: ${lightState}, decoded light state: ${decodedLightState}, output json: ${json}",
              )
              .as((Zigbee.StudioLights, json.asObject.get).some)
              .as(None)
          }
      case _ => None.pure[F]
    } // >>> logInfo.lmap(_.toString)
*/

  type -->[A, B] = Kleisli[F, A, B]

  case class OutputEntity[-->[_, _], T, Out](
    id: EntityId,
    decode: T --> Out)

/*
  createAction[-->, JsonObject, Unit, JsonObject, OnOffState](In(Zigbee.StudioLightSwitch))(
    Arrow[-->].lift(composed.headOption).map(_.contains("single_right")).map(Either.cond(_, (), ())),
  )(Out(Zigbee.StudioLights))(???)(???)(Arrow[-->].lift { case (v, _) => v.asRight[Unit] })
*/

  val toggle: (LightState, Unit) --> Maybe[LightState] = Arrow[-->].lift{case (lightState, _) => lightState.focus(_.state).modify(_.toggle).asRight[Unit]}
  
  val studioLight: (In[EntityId], (Out[EntityId], In[JsonObject] --> Maybe[Out[JsonObject]])) = bindE[-->, Unit, LightState](StudioLightSwitch_single_right_action, Zigbee.StudioLights, ???, toggle)
  
  def bindE[-->[_, _]: ArrowChoice, Action, OutT](
    in: InputAction[Action],
    out: OutputAction[OutT],
    states: EntityId --> JsonObject,
    decision: (OutT, Action) --> Maybe[OutT],
  ): (In[EntityId], (Out[EntityId], In[JsonObject] --> Maybe[Out[JsonObject]])) = {
    val getAction = Arrow[-->].lift(in.action).map(_.toRight(()))
    given Decoder[OutT] = out.decoder

    val value: In[JsonObject] --> Maybe[Out[JsonObject]] = createActionForJson[-->, In[JsonObject], Action, OutT](getAction, out.id, states, decision)
      .map(_.map(v => Out(out.encoder.apply(v).asObject.get)))
    (in.id, (out.id, value))
  }

  def bind[-->[_, _]](
    inEntityId: In[EntityId],
    outEntityId: Out[EntityId],
    action: ActionInput --> Maybe[ActionOutput],
  ) = (inEntityId, (outEntityId, action))

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
