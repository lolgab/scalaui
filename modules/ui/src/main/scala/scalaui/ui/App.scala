package scalaui.ui

import scalaui.gpu.GpuBackend
import scalaui.window.{AccessNode, Event, MenuEntry, MouseButton, Modifiers, Theme, Window}

/** Desktop app runner (eframe equivalent): owns the window, GPU backend and frame loop. */
object App:
  /** Opens a window and calls `frame` with a root [[Ui]] each time the UI needs repainting. Returns when the window closes.
    * With `followSystemTheme`, `style` is replaced by [[Style.Dark]]/[[Style.Light]] to match the OS and kept in sync. */
  def run(title: String, width: Int = 900, height: Int = 600, style: Style = Style.Dark, followSystemTheme: Boolean = false,
      menuBar: Seq[MenuEntry.Submenu] = Nil)(frame: Ui => Unit): Unit =
    val win = Window.create(title, width, height)
    val backend = GpuBackend.create(win)
    // Textures are tracked and each frame is compared tile by tile with the previous one, so only the changed region is redrawn
    // and presented (set SCALAUI_DAMAGE=0 to always redraw everything).
    val tracker = new DamageTracker(new TrackedBackend(backend))
    val useDamage = !sys.env.get("SCALAUI_DAMAGE").contains("0")
    var lastStyle: Style = null; var lastZoom = 0.0
    val ctx = new Context(tracker.backend)
    ctx.style = if followSystemTheme then themeStyle(win.systemTheme) else style
    if menuBar.nonEmpty then win.setMenuBar(menuBar)
    ctx.nativeMenus = menuBar.nonEmpty && win.hasNativeMenus
    val debug = sys.env.contains("SCALAUI_DEBUG")
    var running = true
    var dirty = 3 // always paint the first frames
    var accessTree = Vector.empty[AccessNode]
    var pendingRelease: Option[scalaui.geom.Point] = None
    // One UI pass + GPU submit; returns whether the frame consumed input (so another frame should follow).
    var painting = false
    def paint(): Boolean =
      painting = true
      try
          ctx.begin(win.width, win.height, win.scaleFactor)
          val root = new Ui(ctx, scalaui.geom.Rect(10, 8, win.width - 10.0, win.height.toDouble), true, 1L)
          frame(root)
          val hadInput = ctx.input.anyEvent
          if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"frame dirty=$dirty input=$hadInput repaint=${ctx.repaintRequested}")
          val t0 = System.nanoTime()
          val fr = ctx.end()
          val t1 = System.nanoTime()
          // a theme (style) or zoom change repaints everything, as does anything the tracker cannot compare (first frame, resize, scale)
          val force = !useDamage || (ctx.style ne lastStyle) || ctx.zoom != lastZoom
          lastStyle = ctx.style; lastZoom = ctx.zoom
          val damage = tracker.submit(fr, force)
          if debug then System.err.println(s"  damage=${damage.fold("full")(r => f"${r.x0}%.0f,${r.y0}%.0f ${r.width}%.0fx${r.height}%.0f")}")
          win.setCursor(ctx.cursorIcon)
          if ctx.accessNodes != accessTree then { accessTree = ctx.accessNodes.toVector; win.setAccessibility(accessTree) }
          ctx.imeRect.foreach(r => win.setImeRect(r.x0, r.y0, r.width, r.height))
          if sys.env.contains("SCALAUI_DEBUG") then System.err.println(f"  ui ${(t1 - t0) / 1e6}%.1f ms, submit ${(System.nanoTime() - t1) / 1e6}%.1f ms, vertices ${fr.vertices.length / 8}")
          // a click or key may change state that the next frame should reflect
          hadInput
      finally { painting = false; ctx.accessEdits.clear() }
    // the OS runs its own modal loop while the user drags a window edge: repaint from inside it so content follows the frame
    win.setLiveResizeCallback { () => if !painting then paint(); () }
    while running do
      // block for OS events when idle: no CPU use until input arrives (or the next animation tick)
      val events = if dirty > 0 then win.pollEvents() else win.waitEvents(if ctx.repaintRequested then 8 else 250)
      if debug && events.nonEmpty then System.err.println(s"events: ${events.take(4)}")
      events.foreach {
        case Event.CloseRequested => running = false
        case Event.ThemeChanged(t) =>
          if followSystemTheme then ctx.style = themeStyle(t)
          dirty = math.max(dirty, 1)
        case Event.AccessPress(i) =>
          // screen readers activate elements by index: replay as a click at the element's center
          accessTree.lift(i).foreach { n =>
            val c = n.bounds.center
            ctx.input.handle(Event.MouseMoved(c.x, c.y)); ctx.input.handle(Event.MouseDown(MouseButton.Left, c.x, c.y, Modifiers.None))
            pendingRelease = Some(c)
          }
          dirty = math.max(dirty, 2)
        case Event.AccessSetText(i, t) =>
          if accessTree.lift(i).exists(_.text.isDefined) then { ctx.accessEdits(i) = (Some(t), ctx.accessEdits.get(i).flatMap(_._2)); dirty = math.max(dirty, 2) }
        case Event.AccessSetSelection(i, a, b) =>
          if accessTree.lift(i).exists(_.text.isDefined) then { ctx.accessEdits(i) = (ctx.accessEdits.get(i).flatMap(_._1), Some((a, b))); dirty = math.max(dirty, 2) }
        case e => ctx.input.handle(e); dirty = math.max(dirty, 1)
      }
      if pendingRelease.isDefined && !ctx.input.pressed then
        pendingRelease.foreach(c => ctx.input.handle(Event.MouseUp(MouseButton.Left, c.x, c.y)))
        pendingRelease = None
      if dirty > 0 || ctx.repaintRequested then
        if dirty > 0 then dirty -= 1
        if paint() then dirty = math.max(dirty, 1)
    tracker.backend.close()
    win.close()

  private def themeStyle(t: Theme): Style = if t == Theme.Dark then Style.Dark else Style.Light
