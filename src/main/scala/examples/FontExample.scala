package examples

import scalaui._

object FontExample {
  object callbacks extends AreaCallbacks {
    override def draw(ha: AreaHandler, area: Area, p: DrawParams): Unit = {
      p.drawText("Hello from Lorenzo :-)", Point(100, 100), fontButton.font, 300)
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
