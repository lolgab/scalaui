package examples

import scala.collection.mutable
import scalaui._

object PaintExample {
  val points = mutable.ListBuffer[Point]()

  val width, height = 500

  object callbacks extends AreaCallbacks {
    val whiteBrush = new GradientBrush(
      Seq(
        Color(0, 0, 1, 1),
        Color(1, 0, 0, 1)
      ),
      Point(0, 0),
      Point(width, height)
    )
    val blackBrush = new SolidBrush(Color.Black)

    override def draw(ha: AreaHandler, area: Area, p: DrawParams): Unit = {
      val rectangle = new Rectangle(Point(0, 0), p.areaWidth, p.areaHeight)
      p.fill(rectangle, whiteBrush)

      for (point <- points) {
        val r = new Rectangle(point, 5, 5)
        p.fill(r, blackBrush)
      }
    }

    override def onMouseEvent(ha: AreaHandler, area: Area, event: MouseEvent): Unit = {
      var modified = false
      if (event held MouseButton.Left) {
        if (points.isEmpty || points.last.x != event.x || points.last.y != event.y) {
          points.append(Point(event.x, event.y))
          modified = true
        }
      } else if (event.down == MouseButton.Right) {
        points.clear()
        modified = true
      }

      if (modified) area.queueRedraw()
    }

    override def onMouseCrossed(ha: AreaHandler, area: Area, left: Boolean): Unit = {}

    override def onKeyEvent(ha: AreaHandler, a: Area, e: KeyEvent): Boolean = false
  }

  val area = new NonScrollingArea(callbacks.draw _,
                                  callbacks.onMouseEvent _,
                                  callbacks.onMouseCrossed _,
                                  callbacks.onKeyEvent _)

  val pane = new VerticalPanel(area)

  val window = new Window("Area Example", width, height, pane)

  def main(args: Array[String]): Unit = {
    render(window)
  }
}
