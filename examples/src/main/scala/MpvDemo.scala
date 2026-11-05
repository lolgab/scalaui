import scalaui.color.*
import scalaui.geom.*
import scalaui.gpu.*
import scalaui.window.*

/** Plays a local MPEG-TS/MP4 file through an in-process libmpv, composited into a window by `Canvas.drawImage`.
  * Headless-friendly: renders a handful of frames and prints what happened instead of running an interactive loop.
  * Run: `sbt "examples/run mpv /path/to/file.ts"` (needs `brew install mpv`).
  */
def mpvDemo(path: Option[String]): Unit =
  val file = path.getOrElse("/tmp/mpvtest/test.ts")
  val (w, h) = (320, 180)
  MpvSurface.create(w, h) match
    case Left(err) => println(s"mpv: FAIL create: $err")
    case Right(surface) =>
      println(s"mpv: created surface ${surface.width}x${surface.height}")
      val win = Window.create("mpv", 400, 260)
      val gpu = GpuBackend.create(win)
      val canvas = new Canvas(gpu.whiteTexture)
      val video = gpu.createTexture(w, h, null)
      val in = new java.io.FileInputStream(file)
      val chunk = new Array[Byte](64 * 1024)
      var pending = 0 // bytes of `chunk` still to push, held at the tail
      var pendingOff = 0
      var eof = false
      var rendered = 0
      var frame = 0
      val rgba = new Array[Byte](w * h * 4)
      val deadline = System.currentTimeMillis() + 8000
      while rendered < 5 && System.currentTimeMillis() < deadline do
        // feed: never blocks, retry the remainder next iteration
        if pending == 0 && !eof then
          val n = in.read(chunk)
          if n < 0 then { eof = true; surface.endOfStream() } else { pending = n; pendingOff = 0 }
        if pending > 0 then
          val n = surface.pushBytes(chunk, pendingOff, pending)
          if n > 0 then { pendingOff += n; pending -= n }
        // paint
        if surface.renderInto(video) then
          rendered += 1
          surface.renderPixels(rgba)
          val nonBlack = (0 until w * h).count(i => (rgba(i * 4) & 0xff) + (rgba(i * 4 + 1) & 0xff) + (rgba(i * 4 + 2) & 0xff) > 30)
          println(s"mpv: frame $rendered ok, ${w}x$h rgba8, alpha=${rgba(3) & 0xff}, non-black pixels=$nonBlack/${w * h}, pixel(0,0)=${rgba.take(4).map(_ & 0xff).mkString(",")}")
        canvas.begin(win.width, win.height, win.scaleFactor, Color.rgb8(30, 32, 40))
        canvas.drawImage(video, Rect(40, 40, 40 + w, 40 + h))
        gpu.submit(canvas.finish())
        win.pollEvents()
        frame += 1
        Thread.sleep(80)
      println(s"mpv: ${if rendered >= 5 then "OK" else "FAIL"} rendered=$rendered after $frame paints")
      surface.close()
      win.close()
