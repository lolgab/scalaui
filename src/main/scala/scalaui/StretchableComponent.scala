package scalaui

sealed trait StretchableComponent {
  val component: Component
}

case class Stretchy(component: Component) extends StretchableComponent
case class NonStretchy(component: Component) extends StretchableComponent
