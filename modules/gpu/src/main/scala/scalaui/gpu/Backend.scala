package scalaui.gpu

import scalaui.color.Color
import scalaui.geom.Rect

/** A GPU texture holding premultiplied RGBA8 pixels. */
trait Texture:
  def width: Int
  def height: Int
  /** Overwrites a sub-rectangle; `rgba` is tightly packed premultiplied RGBA8, `w * h * 4` bytes. */
  def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit

/** Vertex layout: x, y, u, v, r, g, b, a (premultiplied), 8 floats. */
object Vertex:
  val Floats = 8

/** One draw: a run of triangles sharing a texture and scissor (logical pixels; `None` = no clip). */
final case class DrawCall(texture: Texture, scissor: Option[Rect], firstVertex: Int, vertexCount: Int)

/** Everything a backend needs to render one frame. Sizes are logical points; pixels = logical * scale. */
final case class Frame(
    clear: Color,
    width: Double,
    height: Double,
    scale: Double,
    vertices: Array[Float],
    calls: Vector[DrawCall]
)

/** The platform seam: Metal, Vulkan and a software fallback. */
trait GpuBackend:
  def createTexture(width: Int, height: Int, rgba: Array[Byte]): Texture
  /** 1x1 opaque white texture used for untextured geometry. */
  def whiteTexture: Texture
  def submit(frame: Frame): Unit
  /** Like [[submit]] but tells the backend that only `damage` (logical points) differs from the previously submitted frame
    * (same size and scale, same texture contents). Backends that retain the previous image redraw just that rectangle;
    * the default ignores the hint and draws everything. `None` always means "everything changed". The frame must still
    * describe the whole picture: damage is an optimization hint, never a clip.
    */
  def submit(frame: Frame, @annotation.unused damage: Option[Rect]): Unit = submit(frame)
  def close(): Unit

object GpuBackend:
  /** Creates the best backend for the current OS: Metal on macOS, Vulkan (then Direct3D 12, then Direct3D 11) on Windows and Vulkan on Linux when a driver is present,
    * otherwise software rendering. `SCALAUI_SOFTWARE=1` forces the CPU renderer; `SCALAUI_BACKEND=vulkan|d3d12|d3d11|metal|software`
    * picks one explicitly (Vulkan also works on macOS through MoltenVK). The Direct3D backends are only used when Vulkan is missing
    * or when asked for: neither has been run on real Windows yet.
    */
  def create(window: scalaui.window.Window): GpuBackend =
    val b = choose(window)
    if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"gpu backend: ${b.getClass.getSimpleName}")
    b

  private def choose(window: scalaui.window.Window): GpuBackend =
    import scala.scalanative.meta.LinktimeInfo
    val forced = sys.env.get("SCALAUI_BACKEND").map(_.toLowerCase).filter(_.nonEmpty)
      .orElse(if sys.env.get("SCALAUI_SOFTWARE").exists(v => v.nonEmpty && v != "0") then Some("software") else None)
    def vulkan: Option[GpuBackend] = VulkanBackend.create(Some(window))
    forced match
      case Some("software") => new SoftwareBackend(window)
      case Some("vulkan") => vulkan.getOrElse(new SoftwareBackend(window))
      case Some("d3d12") => D3D12Backend.create(window).getOrElse(new SoftwareBackend(window))
      case Some("d3d11") => D3D11Backend.create(window).getOrElse(new SoftwareBackend(window))
      case Some("metal") => if LinktimeInfo.isMac then new MetalBackend(window) else new SoftwareBackend(window)
      case _ =>
        if LinktimeInfo.isMac then new MetalBackend(window)
        else if LinktimeInfo.isWindows then vulkan.orElse(D3D12Backend.create(window)).orElse(D3D11Backend.create(window)).getOrElse(new SoftwareBackend(window))
        else vulkan.getOrElse(new SoftwareBackend(window))
