package examples

import scalaui._
object OnClosingExample {
  var n = 0
  val clicksToClose = 5
  def message =
    s"Tried to close $n times! Try to close another ${clicksToClose - n} times to close the window"

  def onClosing(): Boolean = {
    n += 1
    if (n < clicksToClose) {
      window.messageBox("Message", message)
      false
    } else {
      println("You successfully closed the window")
      true
    }
  }

  val label = new Label("Close the window!")
  val window = new Window(
    "Closing Example",
    200,
    200,
    content = label,
    onClosing = onClosing _
  )

  def main(args: Array[String]): Unit = {
    render(window)
  }
}
