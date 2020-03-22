import scala.scalanative.libc.stdlib.malloc
import scala.scalanative.unsafe.{CFuncPtr0, CInt, Ptr, sizeof, _}
import scala.scalanative.unsigned._

package object scalaui {
  import ui._
  import uiOps._

  private var windows = List[Window]()

  private var _initialized = false
  private[scalaui] def initialized: Boolean = _initialized

  private val onShouldQuit = new CFuncPtr1[Ptr[Byte], CInt] {
    def apply(data: Ptr[Byte]): CInt = {
      uiControlDestroy(data.asInstanceOf[Ptr[uiWindow]])
      1
    }
  }

  private[scalaui] val cCallback2 =
    new CFuncPtr2[Ptr[uiButton], Ptr[Byte], Unit] {
      def apply(component: Ptr[uiButton], ptr: Ptr[Byte]): Unit = {
        PtrConverter.fromPtr[() => Unit](ptr)()
      }
    }
  private[scalaui] val cCallback3 =
    new CFuncPtr3[Ptr[uiMenuItem], Ptr[uiWindow], Ptr[Byte], Unit] {
      def apply(
          component: Ptr[uiMenuItem],
          window: Ptr[uiWindow],
          ptr: Ptr[Byte]
      ): Unit = {
        PtrConverter.fromPtr[() => Unit](ptr)()
      }
    }

  private val onClosing = new CFuncPtr2[Ptr[uiWindow], Ptr[Byte], CInt] {
    def apply(w: Ptr[uiWindow], data: Ptr[Byte]): CInt = {
      if (PtrConverter.fromPtr[() => Boolean](data)()) {
        for (window <- windows; if window.control == w) {
          windows = windows.filter(_ != window)
          window.free()
        }
        if (windows == Nil) uiQuit()
        1
      } else 0
    }
  }

  def render(window: Window): Unit = {
    def create(): Unit = {
      window.build()
      uiWindowOnClosing(
        window.control,
        onClosing,
        PtrConverter.toPtr(window.onClosing)
      )
      uiOnShouldQuit(onShouldQuit, window.control.asInstanceOf[Ptr[Byte]])
      uiControlShow(window.control)
    }

    windows match {
      case Nil =>
        windows = window :: windows
        val options: Ptr[uiInitOptions] = malloc(sizeof[uiInitOptions])
          .asInstanceOf[Ptr[uiInitOptions]]
        uiInit(options)
        create()
        _initialized = true
        uiMain()
        uiUninit()
      case _ =>
        windows = window :: windows
        create()
    }
  }

  type Width = Int
  type Height = Int

  implicit def toBoolean(i: CInt): Boolean = i match {
    case 0 => false
    case _ => true
  }

  implicit def toCInt(b: Boolean): CInt = if (b) 1 else 0

  // implicit def areaToStratchable(c: AbstractArea): StretchableComponent = Stretchy(c)

  implicit def componentToStratchable(c: Component): StretchableComponent =
    NonStretchy(c)

  implicit def toSeq(c: StretchableComponent) = Seq(c)

  val doNothing = new CFuncPtr0[Unit] {
    def apply(): Unit = ()
  }

  def doNothingThenClose(): Boolean = true

  type AreaHandler = Ptr[uiAreaHandler]
  type Area = Ptr[uiArea]

  implicit class AreaOps(val area: Area) extends AnyVal {
    def setSize(width: Int, height: Int): Unit =
      uiAreaSetSize(area, width, height)

    def queueRedraw(): Unit = uiAreaQueueRedrawAll(area)
  }

  type DrawContext = Ptr[uiDrawContext]
  type DrawCallback = CFuncPtr3[AreaHandler, Area, DrawParams, Unit]
  type DrawParams = Ptr[uiAreaDrawParams]
  type DrawPath = Ptr[uiDrawPath]

  implicit def StringToAttributed(s: String): AttributedString =
    new AttributedString(s)

  implicit class DrawParamsOps(val p: DrawParams) extends AnyVal {
    private def context: DrawContext = p.Context
    def areaWidth: Double =
      p.ClipWidth //TODO difference between Scrolling and NotScrolling
    def areaHeight: Double = p.ClipHeight
    def areaStartX: Double = p.ClipX
    def areaStartY: Double = p.ClipY

//    //TODO Can't DRY because of Scala Native bug
//    private def createPath(drawable: Drawable, draw: DrawPath => Unit): Unit = {
//      val path = uiDrawNewPath(uiDrawFillMode.uiDrawFillModeWinding)
//      drawable.build(path)
//      uiDrawPathEnd(path)
//      draw(path)
//      uiDrawFreePath(path)
//    }

    def fill(drawable: Drawable, brush: Brush): Unit = {
//      def draw(path: DrawPath): Unit = uiDrawFill(p.Context, path, brush.control)
//      createPath(drawable, draw)
      val path = uiDrawNewPath(uiDrawFillMode.uiDrawFillModeWinding)
      drawable.addTo(path)
      uiDrawPathEnd(path)
      uiDrawFill(p.Context, path, brush.control)
      uiDrawFreePath(path)
    }

    def stroke(drawable: Drawable, brush: Brush, stroke: Stroke): Unit = {
//      def draw(path: DrawPath): Unit = uiDrawStroke(p.Context, path, brush.control, stroke.control)
//      createPath(drawable.build, draw)
      val path = uiDrawNewPath(uiDrawFillMode.uiDrawFillModeWinding)
      drawable.addTo(path)
      uiDrawPathEnd(path)
      uiDrawStroke(p.Context, path, brush.control, stroke.control)
      uiDrawFreePath(path)
    }

    // def drawText(text: AttributedString,
    //              p: Point,
    //              font: Font,
    //              width: Double,
    //              align: Align.Value): Unit = {
    //   val layout = new TextLayout(text, font, width, align)
    //   layout.build()
    //   uiDrawText(this.p.Context, layout.control, p.x, p.y)
    //   uiDrawFreeTextLayout(layout.control)
    // }
  }

  object MouseButton extends Enumeration {
    val NoButton, Left, Center, Right = Value
  }

  type MouseEventCallback = CFuncPtr3[AreaHandler, Area, MouseEvent, Unit]
  type MouseEvent = Ptr[uiAreaMouseEvent]
  implicit class MouseEventOps(val e: MouseEvent) extends AnyVal {
    def x: Double = e.X
    def y: Double = e.Y
    def down: MouseButton.Value = MouseButton(e.Down) //  buttonMatch(e.Down)
    def up: MouseButton.Value = MouseButton(e.Up)
    def clicks: Int = e.Count
    def ctrlDown: Boolean = e.Modifiers == uiModifiers.uiModifierCtrl
    def altDown: Boolean = e.Modifiers == uiModifiers.uiModifierAlt
    def shiftDown: Boolean = e.Modifiers == uiModifiers.uiModifierShift
    def superDown: Boolean = e.Modifiers == uiModifiers.uiModifierSuper
    def areaWidth: Double = e.AreaWidth
    def areaHeight: Double = e.AreaHeight

    def startMovingWindow(area: Area): Unit = {
      require(
        down != MouseButton.NoButton,
        s"WARNING: You can't call startMovingWindow if down == ${MouseButton.NoButton}"
      )
      uiAreaBeginUserWindowMove(area)
    }

    def startResizingWindow(area: Area, edge: ResizeEdge.Value): Unit = {
      require(
        down == MouseButton.NoButton,
        s"WARNING: You can't call startResizingWindow if down == ${MouseButton.NoButton}"
      )
      uiAreaBeginUserWindowResize(area, edge.id.toUInt)
    }

    def held(b: MouseButton.Value): Boolean = {
      if (b == MouseButton.NoButton) e.Held1To64 == 0.toULong
      else (e.Held1To64 & (1.toULong << b.id - 1)) == 1.toULong
    }
  }

  //TODO ugly boolean Int unsafe conversion!
  type MouseCrossedCallback = CFuncPtr3[AreaHandler, Area, Boolean, Unit]

  type DragBrokenCallback = CFuncPtr2[AreaHandler, Area, Unit]

  private[scalaui] def doNothingOnDragBroken(
      ah: Ptr[uiAreaHandler],
      a: Ptr[uiArea]
  ): Unit = ()

  type KeyEvent = Ptr[uiAreaKeyEvent]
  type KeyEventCallback = CFuncPtr3[AreaHandler, Area, KeyEvent, Boolean]

  //TODO find better names!
  object Key {
    sealed trait Value
    case class Down(key: KeyValue) extends Value
    case class Up(key: KeyValue) extends Value

    sealed trait KeyValue
    case object Ctrl extends KeyValue
    case object Alt extends KeyValue
    case object Shift extends KeyValue
    case object Super extends KeyValue
    case class Coded(key: Char) extends KeyValue
    case object Escape extends KeyValue
    case object Insert extends KeyValue
    case object Delete extends KeyValue
    case object Home extends KeyValue
    case object End extends KeyValue
    case object PageUp extends KeyValue
    case object PageDown extends KeyValue
    case object Up extends KeyValue
    case object Down extends KeyValue
    case object Left extends KeyValue
    case object Right extends KeyValue
    case object Findex extends KeyValue
    case object F2 extends KeyValue
    case object F3 extends KeyValue
    case object F4 extends KeyValue
    case object F5 extends KeyValue
    case object F6 extends KeyValue
    case object F7 extends KeyValue
    case object F8 extends KeyValue
    case object F9 extends KeyValue
    case object F10 extends KeyValue
    case object F11 extends KeyValue
    case object F12 extends KeyValue
    case object N0 extends KeyValue
    case object N1 extends KeyValue
    case object N2 extends KeyValue
    case object N3 extends KeyValue
    case object N4 extends KeyValue
    case object N5 extends KeyValue
    case object N6 extends KeyValue
    case object N7 extends KeyValue
    case object N8 extends KeyValue
    case object N9 extends KeyValue
    case object NDot extends KeyValue
    case object NEnter extends KeyValue
    case object NAdd extends KeyValue
    case object NSubtract extends KeyValue
    case object NMultiply extends KeyValue
    case object NDivide extends KeyValue
  }
  implicit class KeyEventOps(val e: KeyEvent) extends AnyVal {

    def key = if (e.Up == 1) Key.Up(_key) else Key.Down(_key)

    def ctrlDown: Boolean =
      (e.Modifiers & uiModifiers.uiModifierCtrl) != 0.toUInt
    def altDown: Boolean = (e.Modifiers & uiModifiers.uiModifierAlt) != 0.toUInt
    def shiftDown: Boolean =
      (e.Modifiers & uiModifiers.uiModifierShift) != 0.toUInt
    def superDown: Boolean =
      (e.Modifiers & uiModifiers.uiModifierSuper) != 0.toUInt

    private def _key =
      if (e.Key == 0) {
        if (e.Modifier != 0.toUInt)
          e.Modifier match {
            case uiModifiers.uiModifierCtrl  => Key.Ctrl
            case uiModifiers.uiModifierAlt   => Key.Alt
            case uiModifiers.uiModifierShift => Key.Shift
            case uiModifiers.uiModifierSuper => Key.Super
          }
        else if (e.ExtKey != 0.toUInt)
          e.ExtKey match {
            case uiExtKey.uiExtKeyEscape    => Key.Escape
            case uiExtKey.uiExtKeyInsert    => Key.Insert
            case uiExtKey.uiExtKeyDelete    => Key.Delete
            case uiExtKey.uiExtKeyHome      => Key.Home
            case uiExtKey.uiExtKeyEnd       => Key.End
            case uiExtKey.uiExtKeyPageUp    => Key.PageUp
            case uiExtKey.uiExtKeyPageDown  => Key.PageDown
            case uiExtKey.uiExtKeyUp        => Key.Up
            case uiExtKey.uiExtKeyDown      => Key.Down
            case uiExtKey.uiExtKeyLeft      => Key.Left
            case uiExtKey.uiExtKeyRight     => Key.Right
            case uiExtKey.uiExtKeyFindex    => Key.Findex
            case uiExtKey.uiExtKeyF2        => Key.F2
            case uiExtKey.uiExtKeyF3        => Key.F3
            case uiExtKey.uiExtKeyF4        => Key.F4
            case uiExtKey.uiExtKeyF5        => Key.F5
            case uiExtKey.uiExtKeyF6        => Key.F6
            case uiExtKey.uiExtKeyF7        => Key.F7
            case uiExtKey.uiExtKeyF8        => Key.F8
            case uiExtKey.uiExtKeyF9        => Key.F9
            case uiExtKey.uiExtKeyF10       => Key.F10
            case uiExtKey.uiExtKeyF11       => Key.F11
            case uiExtKey.uiExtKeyF12       => Key.F12
            case uiExtKey.uiExtKeyN0        => Key.N0
            case uiExtKey.uiExtKeyN1        => Key.N1
            case uiExtKey.uiExtKeyN2        => Key.N2
            case uiExtKey.uiExtKeyN3        => Key.N3
            case uiExtKey.uiExtKeyN4        => Key.N4
            case uiExtKey.uiExtKeyN5        => Key.N5
            case uiExtKey.uiExtKeyN6        => Key.N6
            case uiExtKey.uiExtKeyN7        => Key.N7
            case uiExtKey.uiExtKeyN8        => Key.N8
            case uiExtKey.uiExtKeyN9        => Key.N9
            case uiExtKey.uiExtKeyNDot      => Key.NDot
            case uiExtKey.uiExtKeyNEnter    => Key.NEnter
            case uiExtKey.uiExtKeyNAdd      => Key.NAdd
            case uiExtKey.uiExtKeyNSubtract => Key.NSubtract
            case uiExtKey.uiExtKeyNMultiply => Key.NMultiply
            case uiExtKey.uiExtKeyNDivide   => Key.NDivide
          }
        else {
          println("Should never happen! Maybe it is a bug!")
          Key.Coded(0)
        }
      } else Key.Coded(e.Key.toChar)
  }
}
