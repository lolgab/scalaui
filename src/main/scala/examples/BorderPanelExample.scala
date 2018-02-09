package examples

import scalaui._

object BorderPanelExample {
  val north = new Button("North", doNothing _)
  val south = new Button("South", doNothing _)
  val west = new Button("West", doNothing _)
  val east = new Button("East", doNothing _)
  val center = new Button("Center", doNothing _)

  val borderPanel = new BorderPanel(north, west, center, east, south)

  val window = new Window("Border Panel Example", 500, 500, borderPanel)

  def main(args: Array[String]): Unit = {
    render(window)
  }
}