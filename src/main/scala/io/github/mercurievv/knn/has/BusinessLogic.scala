package io.github.mercurievv.knn.has

trait BusinessLogic[T <: BusinessDsl](using dsl: T) {
  import dsl.*
  
  

}

trait BusinessDsl {
  type InputEvent
  type State
  type -->[_, _]
  type OutputEvent
  
  def receiveEvent: Unit  --> InputEvent
  def filter: Unit  --> InputEvent
}