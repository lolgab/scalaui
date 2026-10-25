package scalaui.gpu

/** A backend with no window: frames are rendered by [[SoftwareRenderer]] and kept in memory, for screenshots,
  * snapshot tests and offscreen rendering. */
final class HeadlessBackend extends GpuBackend:
  val whiteTexture: Texture = createTexture(1, 1, Array(-1, -1, -1, -1))
  /** The most recently submitted frame as premultiplied RGBA8 plus its pixel size. */
  private var lastScale = 0.0
  var last: Option[(Array[Byte], Int, Int)] = None

  def createTexture(width: Int, height: Int, rgba: Array[Byte]): Texture =
    val t = new SoftTexture(width, height)
    if rgba != null then t.update(0, 0, width, height, rgba)
    t
  def submit(frame: Frame): Unit = submit(frame, None)
  /** With `damage` only that rectangle is redrawn into the previous frame's pixels (the array in [[last]] is reused and
    * modified in place, so copy it to keep a frame); a size or scale change falls back to a full render. */
  override def submit(frame: Frame, damage: scala.Option[scalaui.geom.Rect]): Unit =
    val (pw, ph) = SoftwareRenderer.pixelSize(frame)
    last match
      case Some((px, w, h)) if damage.isDefined && w == pw && h == ph && lastScale == frame.scale =>
        SoftwareRenderer.renderInto(frame, px, damage)
      case _ =>
        val px = new Array[Byte](pw * ph * 4)
        SoftwareRenderer.renderInto(frame, px, None)
        last = Some((px, pw, ph))
    lastScale = frame.scale
  def close(): Unit = ()

  /** The last frame as a non-premultiplied image. */
  def screenshot(): scalaui.codecs.Image =
    val (px, w, h) = last.getOrElse(throw new IllegalStateException("no frame has been submitted"))
    scalaui.codecs.Image.fromPremultiplied(w, h, px)
