package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.home_automation.rules.EventTypes.{In, Out}

import cats.data.Kleisli
import cats.implicits.*
import cats.{Functor, Monad, Show, Traverse, ~>}

import org.typelevel.log4cats.StructuredLogger

class BindingsProcessor[F[_]: {Monad, StructuredLogger}, S[_]: {Monad, Traverse}, Topic: Show, JsonObject: Show](
  o2S: Option ~> S,
  actionsMap: Map[
    In[Topic],
    S[
      (
        Out[Topic],
        Kleisli[[a] =>> F[S[a]], Topic, JsonObject] => Kleisli[[a] =>> F[S[a]], In[JsonObject], Out[JsonObject]],
      ),
    ],
  ],
  addLogContext: Topic => Map[String, String]) {
  given Functor[S] = summon[Monad[S]]
  type -->[a, b] = Kleisli[[a] =>> F[S[a]], a, b]
  type ActionInput = (In[(Topic, JsonObject)], Topic --> JsonObject)
  type ActionOutput = Out[(Topic, JsonObject)]

  val processBindings: ActionInput --> ActionOutput =
    Kleisli[[a] =>> F[S[a]], ActionInput, ActionOutput] { case (input, state) =>
      o2S(actionsMap.get(input.map(_._1))).flatten
        .traverse { case (outId, action) =>
          val inJson = input.map(_._2)
          action(state)
            .apply(inJson)
            .map(_.map((outId, _).tupled))
            .flatTap(output =>
              val messageJson = inJson.value.show
              StructuredLogger[F]
                .addContext(addLogContext(input.value._1) ++ Map("event" -> "decision_making"))
                .debug(
                  Map(
                    "inputTopic"    -> input.value._1.show,
                    "inputMessage"  -> messageJson,
                    "outputTopic"   -> outId.value.show,
                    "outputMessage" -> output.map(_.value._2.toString).toString,
                  ),
                )(
                  s"Decision making. source: \"${input.value._1}\", input message: ${messageJson.take(500)}, output id: $outId, output message: $output",
                ),
            )
        }
        .map(_.flatten)
        .flatTap(output =>
          val messageJson = input.map(_._2).value.show
          val outVal = output.map(_.value)
          val outputTopic = outVal.map(_._1.toString)
          val outJson = outVal.map(_._2.toString).toString
          StructuredLogger[F]
            .addContext(addLogContext(input.value._1) ++ Map("event" -> "decision_done"))
            .info(
              Map(
                "inputTopic"    -> input.value._1.show,
                "inputMessage"  -> messageJson,
                "outputTopic"   -> outputTopic.toString,
                "outputMessage" -> outJson,
              ),
            )(
              s"Decision done. source: \"${input.value._1}\", input message: ${messageJson.take(500)}, output id: $outputTopic, output message: $outJson",
            ),
        )
    }

}
