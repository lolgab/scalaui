package examples
import scalaui._

object HelloWorld {
  val button = new Button("My Button", () => println("Hello world!"))
  val window = new Window(
    "Hello World Example",
    width = 200,
    height = 200,
    content = button
  )

  def main(args: Array[String]): Unit = {
    render(window)
  }
}
