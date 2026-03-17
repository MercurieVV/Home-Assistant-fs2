package io.github.mercurievv.home_automation.rules

import io.github.mercurievv.home_automation.rules.EventTypes.{EntityId, In, Out}

import cats.data.Kleisli
import cats.implicits.*
import cats.{Applicative, Functor, Monad, Traverse, ~>}

import io.circe.JsonObject

class BindingsProcessor[F[_]: Applicative, S[_]: {Monad, Traverse}](
  o2S: Option ~> S,
  map: Map[
    In[EntityId],
    S[
      (
        Out[EntityId],
        Kleisli[[a] =>> F[S[a]], EntityId, JsonObject] => Kleisli[[a] =>> F[S[a]], In[JsonObject], Out[JsonObject]],
      ),
    ],
  ]) {
  given Functor[S] = summon[Monad[S]]
  type -->[a, b] = Kleisli[[a] =>> F[S[a]], a, b]
  type ActionInput = (In[(EntityId, JsonObject)], EntityId --> JsonObject)
  type ActionOutput = Out[(EntityId, JsonObject)]

  val processBindings: ActionInput --> ActionOutput =
    // MessageLogger[F].info.bnk.lmap[Input](i => s"Info: $i") *>
    Kleisli[[a] =>> F[S[a]], ActionInput, ActionOutput] { case (input, state) =>
      o2S(map.get(input.map(_._1))).flatten
        .map { case (outId, action) =>
          val inJson = input.map(_._2)
          action(state).apply(inJson).map(_.map((outId, _).tupled))
        }
        .sequence
        .map(_.flatten)
    }

}
