package examples

import scalaui._

object BorderPaneExample {
  val north = new Button("North", scalaui.doNothing _)
  val south = new Label("South")
  val west = new Button("West", scalaui.doNothing _)
  val east = new Button("East", scalaui.doNothing _)
  val center = new Button("Center", scalaui.doNothing _)

  val borderPane = new BorderPane(north, west, center, east, south)

  val window = new Window("Border Pane Example", 500, 500, borderPane)

  def main(args: Array[String]): Unit = {
    scalaui.render(window)
  }
}