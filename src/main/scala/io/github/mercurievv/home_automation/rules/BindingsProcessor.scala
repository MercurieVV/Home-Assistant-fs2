package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, Out}

import cats.Applicative
import cats.data.Kleisli
import cats.implicits.*

import io.circe.JsonObject

class BindingsProcessor[F[_]: Applicative](
  map: Map[
    In[EntityId],
    (Out[EntityId], Kleisli[F, EntityId, JsonObject] => Kleisli[F, In[JsonObject], Maybe[Out[JsonObject]]]),
  ]) {
  type -->[a, b] = Kleisli[F, a, b]
  type ActionInput = (In[(EntityId, JsonObject)], EntityId --> JsonObject)
  type ActionOutput = Option[Out[(EntityId, JsonObject)]]

  val processBindings: ActionInput --> ActionOutput =
    // MessageLogger[F].info.bnk.lmap[Input](i => s"Info: $i") *>
    Kleisli[F, ActionInput, ActionOutput] { case (input, state) =>
      map
        .get(input.map(_._1))
        .map { case (outId, action) =>
          action(state).apply(input.map(_._2)).map(_.toOption.map((outId, _).tupled))
        }
        .sequence
        .map(_.flatten)
    }

}
