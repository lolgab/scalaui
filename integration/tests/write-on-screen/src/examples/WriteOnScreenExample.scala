package examples

import scalaui._

object WriteOnScreenExample {
  var typing = false
  var texts: List[(String, Font, Point)] = List[(String, Font, Point)]()

  object callbacks extends AreaCallbacks {
    val whiteBrush = new SolidBrush(Color.White)
    val blackBrush = new SolidBrush(Color.Black)
    val stroke = new Stroke(Cap.Flat, Join.Bevel, 3)

    override def draw(ha: AreaHandler, area: Area, p: DrawParams): Unit = {
      val r = new Rectangle(Point(0, 0), p.areaWidth, p.areaHeight)
      p.fill(r, whiteBrush)
      for ((s, font, point) <- texts) {
        val str = new AttributedString(s)
        p.drawText(str, point, font, p.areaWidth - point.x, Align.Fill)
        str.free()
      }
    }

    override def onMouseEvent(
        ha: AreaHandler,
        area: Area,
        event: MouseEvent
    ): Unit = {
      event.down match {
        case MouseButton.Left =>
          val f = fontButton.font
          val y = event.y /*- f.ascent / 2*/
          texts = ("", f, Point(event.x, y)) :: texts
          typing = true
        case MouseButton.Right =>
          typing = false
          texts = Nil
          area.queueRedraw()
        case _ =>
      }
    }

    override def onMouseCrossed(
        ha: AreaHandler,
        area: Area,
        left: Boolean
    ): Unit = {}

    override def onKeyEvent(ha: AreaHandler, a: Area, e: KeyEvent): Boolean = {
      if (typing) e.key match {
        case Key.Down(Key.Coded(8)) =>
          val s = texts.head._1
          if (s != "") texts = texts.head.copy(_1 = s.init) :: texts.tail
        case Key.Down(Key.Coded(key)) =>
          val k = if (key >= 'a' && key <= 'z' && e.shiftDown) key - 32 else key
          texts = texts.head.copy(_1 = texts.head._1 + k.toChar) :: texts.tail
        case _ =>
      }
      a.queueRedraw()
      false
    }
  }

  val area = new NonScrollingArea(
    callbacks.draw _,
    callbacks.onMouseEvent _,
    callbacks.onMouseCrossed _,
    callbacks.onKeyEvent _
  )

  def onFontChange(): Unit = {
    val h = texts.head
    texts = (h._1, fontButton.font, h._3) :: texts.tail
  }

  val fontButton = new FontButton(onFontChange _)

  val pane = new VerticalPanel(area, fontButton)

  val window = new Window("Write On Screen Example", 500, 500, pane)

  def main(args: Array[String]): Unit = {
    render(window)
  }
}
