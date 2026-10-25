import scalaui.codecs.Image
import scalaui.color.Color
import scalaui.dialogs.Dialogs
import scalaui.geom.*
import scalaui.gpu.Texture
import scalaui.ui.*
import scala.scalanative.meta.LinktimeInfo

/** Minimal image viewer: open PNG/JPEG, wheel to zoom, drag to pan. */
def viewerDemo(initial: Option[String]): Unit =
  var tex: Option[Texture] = None
  var size = Size(0, 0)
  var status = "Open an image to begin"
  var zoom = 1.0
  var pan = Vec2.Zero
  val path = Var(initial.getOrElse(""))
  var pendingImage: Option[Image] = None

  def open(p: String): Unit =
    try
      val img = Image.decode(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p)))
      size = Size(img.width, img.height); zoom = 1.0; pan = Vec2.Zero
      status = s"${img.width} x ${img.height}"
      pendingImage = Some(img)
    catch case e: Exception => status = s"Failed to open: ${e.getMessage}"
  initial.foreach(open)
  // Linux has no native dialog: use the in-app picker there
  val picker = new FileDialog("Open image", sys.env.getOrElse("HOME", "/"), FileDialogMode.OpenFile, Seq("png", "jpg", "jpeg"))

  App.run("scalaui viewer", 1000, 720) { ui =>
    pendingImage.foreach { img => tex = Some(ui.ctx.texture(img)); pendingImage = None }
    ui.topPanel(34) { bar =>
      if bar.button("Open…").clicked then
        if LinktimeInfo.isLinux then picker.show()
        else Dialogs.openFile("Open image", Seq("png", "jpg", "jpeg")).foreach { p => path.value = p; open(p) }
      if bar.button("Fit").clicked && size.width > 0 then zoom = math.min((ui.availableWidth - 20) / size.width, 600 / size.height)
      if bar.button("100%").clicked then { zoom = 1.0; pan = Vec2.Zero }
      bar.label(status)
    }
    picker.update(ui).foreach { p => path.value = p; open(p) }
    ui.custom(ui.availableWidth - 4, ui.availableHeight - 4) { (canvas, rect, r) =>
      canvas.fillRect(rect, Color.rgb8(40, 40, 44))
      // checkerboard
      val cs = 12.0
      for y <- 0 until (rect.height / cs).toInt + 1; x <- 0 until (rect.width / cs).toInt + 1 do
        if (x + y) % 2 == 0 then canvas.fillRect(Rect(rect.x0 + x * cs, rect.y0 + y * cs, rect.x0 + (x + 1) * cs, rect.y0 + (y + 1) * cs).intersect(rect), Color.rgb8(52, 52, 58))
      pan = pan + r.dragDelta
      if r.scroll.y != 0 then
        val factor = math.pow(1.01, r.scroll.y)
        r.mouse.foreach { m => // zoom around the cursor
          val c = rect.center + pan
          val rel = Point(m.x, m.y) - c
          pan = pan - rel * (factor - 1)
        }
        zoom = (zoom * factor).max(0.02).min(64)
      tex.foreach { t =>
        val w = size.width * zoom; val h = size.height * zoom
        val c = rect.center + pan
        canvas.drawImage(t, Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2))
      }
    }
  }
