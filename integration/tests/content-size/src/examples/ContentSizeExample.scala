package examples

import scalaui._

object ContentSizeExample {
  def increaseWidth(): Unit = {
    window.contentWidth = window.contentWidth + 10
  }
  def decreaseWidth(): Unit = {
    window.contentWidth = window.contentWidth - 10
  }
  def increaseHeight(): Unit = {
    window.contentHeight = window.contentHeight + 10
  }
  def decreaseHeight(): Unit = {
    window.contentHeight = window.contentHeight - 10
  }

  val button1 = new Button("- width", decreaseWidth _)
  val button2 = new Button("+ width", increaseWidth _)
  val button3 = new Button("- height", decreaseHeight _)
  val button4 = new Button("+ height", increaseHeight _)
  val panel =
    new VerticalPanel(
      new HorizontalPanel(button1, button2),
      new HorizontalPanel(button3, button4)
    )
  val window = new Window(
    "Hello World Example",
    width = 200,
    height = 200,
    content = panel
  )

  def main(args: Array[String]): Unit = {
    render(window)
  }
}
