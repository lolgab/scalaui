package scalaui

case class Color(red: Double, green: Double, blue: Double, alpha: Double)

object Color {
  val Black = Color(0.0, 0.0, 0.0, 1.0)
  val White = Color(1.0, 1.0, 1.0, 1.0)
}
