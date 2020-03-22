package examples

import scalaui._

object BorderPanelExample {
  val north = new Button("North", () => ())
  val south = new Button("South", () => ())
  val west = new Button("West", () => ())
  val east = new Button("East", () => ())
  val center = new Button("Center", () => ())

  val borderPanel = new BorderPanel(north, west, center, east, south)

  val window = new Window("Border Panel Example", 500, 500, borderPanel)

  def main(args: Array[String]): Unit = {
    render(window)
  }
}
