import scalaui.color.*
import scalaui.geom.*
import scalaui.gpu.*
import scalaui.window.*

/** GPU libmpv path: plays a file through `MpvSurface.createGpu`, drawing it at the window's full pixel size.
  * Run: `examples/run mpvgpu /path/file.ts [seconds] [software]` (`software` forces the CPU fallback for comparison).
  * Prints surface size, whether the GPU path is active and process CPU time per wall second.
  */
def mpvGpuDemo(path: Option[String], seconds: Int, forceSoftware: Boolean): Unit =
  val file = path.getOrElse("/tmp/mpvtest/test.ts")
  val win = Window.create("mpv gpu", 960, 540)
  val gpu = GpuBackend.create(win)
  val sc = win.scaleFactor
  val pw = (win.width * sc).toInt; val ph = (win.height * sc).toInt
  val created = if forceSoftware then MpvSurface.create(pw / 2, ph / 2) else MpvSurface.createGpu(gpu, pw, ph)
  created match
    case Left(err) => println(s"mpv: FAIL $err")
    case Right(surface) =>
      println(s"mpv: gpu=${surface.isGpu} managed=${surface.managesTexture} initial=${surface.width}x${surface.height} scale=$sc")
      val canvas = new Canvas(gpu.whiteTexture)
      val soft = if forceSoftware then gpu.createTexture(surface.width, surface.height, null) else null
      val chunk = new Array[Byte](64 * 1024)
      var pending = 0; var pendingOff = 0; var eof = false
      var frames = 0
      val t0 = System.nanoTime()
      val deadline = System.currentTimeMillis() + seconds * 1000L
      while System.currentTimeMillis() < deadline do
        // loop the file so the stream outlives short clips
        if pending == 0 && !eof then
          val n = mpvGpuIn.read(file, chunk)
          if n < 0 then { eof = true; surface.endOfStream() } else { pending = n; pendingOff = 0 }
        if pending > 0 then
          val n = surface.pushBytes(chunk, pendingOff, pending)
          if n > 0 then { pendingOff += n; pending -= n }
        canvas.begin(win.width, win.height, win.scaleFactor, Color.rgb8(0, 0, 0))
        val area = Rect(0, 0, win.width, win.height)
        if !forceSoftware then
          val div = if (frames / 100) % 2 == 1 then 3 else 1 // exercise live resize
          surface.resize((win.width * win.scaleFactor).toInt / div, (win.height * win.scaleFactor).toInt / div)
          surface.renderTexture().foreach { t => frames += 1; canvas.drawImage(t, area) }
        else if surface.renderInto(soft) then { frames += 1; canvas.drawImage(soft, area) }
        gpu.submit(canvas.finish())
        win.pollEvents()
        Thread.sleep(8)
      val secs = (System.nanoTime() - t0) / 1e9
      println(s"mpv: surface=${surface.width}x${surface.height} video=${surface.videoSize} frames=$frames (${(frames / secs).toInt}/s)")
      surface.close(); win.close()

private object mpvGpuIn:
  private var in: java.io.FileInputStream = null
  def read(file: String, buf: Array[Byte]): Int =
    if in == null then in = new java.io.FileInputStream(file)
    in.read(buf)
