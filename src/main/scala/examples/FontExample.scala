package examples

import scalaui._

object FontExample {
  object callbacks extends AreaCallbacks {
    override def draw(ha: AreaHandler, area: Area, p: DrawParams): Unit = {
      val str = new AttributedString("Hello from Lorenzo :-)").withColor(Color(1,0,0,1), 11, 11 + "Lorenzo".length)
      p.drawText(str, Point(100, 100), fontButton.font, 300, Align.Fill)
      str.free()
    }

    override def onMouseEvent(ha: AreaHandler, area: Area, event: MouseEvent): Unit = {}

    override def onMouseCrossed(ha: AreaHandler, area: Area, left: Boolean): Unit = {}

    override def onKeyEvent(ha: AreaHandler, a: Area, e: KeyEvent): Boolean = {
      true
    }
  }

  val area = new NonScrollingArea(callbacks.draw _,
                                  callbacks.onMouseEvent _,
                                  callbacks.onMouseCrossed _,
                                  callbacks.onKeyEvent _)

  val fontButton = new FontButton()

  val pane = new VerticalPanel(area, fontButton)

  val window = new Window("Font Example", 500, 500, pane)

  def main(args: Array[String]): Unit = {
    render(window)
  }
}
