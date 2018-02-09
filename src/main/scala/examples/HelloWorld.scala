package examples
import scalaui._

object HelloWorld {
  def greeting(): Unit = println("Hello world!")

  val button = new Button("My Button", greeting _)
  val window = new Window("Hello World Example", width = 200, height = 200, content = button)

  def main(args: Array[String]): Unit = {
    render(window)
  }
}
