package examples

import scalaui._

object MultipleWindows {
  var i = 0
  def newW = {
    def onClosing(): Boolean = { println("closing window"); true }
    val w = new Window(
      s"Window n° $i",
      300,
      300,
      new Label(s"Hi from window n° $i"),
      onClosing = onClosing _
    )
    i += 1
    w
  }

  def action(): Unit = scalaui.render(newW)

  val b = new Button("create new Window", action _)

  val w = new Window("Main Window", 100, 100, b)

  def main(args: Array[String]): Unit = scalaui.render(w)
}
