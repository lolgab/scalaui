import scalaui.color.*
import scalaui.geom.*
import scalaui.gpu.*
import scalaui.font.FontFace
import scalaui.shaping.Shaper
import scalaui.text.*
import scalaui.window.*

def canvasDemo(): Unit =
  val win = Window.create("scalaui", 800, 500)
  val gpu = GpuBackend.create(win)
  val canvas = new Canvas(gpu.whiteTexture)
  // checkerboard texture, premultiplied RGBA8
  val checker = gpu.createTexture(8, 8, Array.tabulate(8 * 8 * 4) { i =>
    val px = i / 4; val on = ((px % 8) / 2 + (px / 8) / 2) % 2 == 0
    (if i % 4 == 3 then 255 else if on then 230 else 60).toByte
  })
  val atlas = new GlyphAtlas(gpu)
  val textRenderer = new TextRenderer(atlas)
  val sans = new Shaper(FontFace.notoSans)
  val mono = new Shaper(FontFace.hack)
  val title = TextLayout.layout("scalaui: AV To Wa — kerning, wrap & alignment", sans, 22)
  val para = TextLayout.layout("The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs, then wrap at word boundaries inside a fixed width box.", sans, 14, Some(300))
  val code = TextLayout.layout("def hello() =\n  println(\"Hi\") // mono", mono, 13)
  var mouse = Point(400, 250)
  var running = true
  var frame = 0
  while running do
    win.pollEvents().foreach {
      case Event.CloseRequested => running = false
      case Event.MouseMoved(x, y) => mouse = Point(x, y)
      case Event.KeyDown(_, _, "q", _, _) => running = false
      case _ => ()
    }
    canvas.begin(win.width, win.height, win.scaleFactor, Color.rgb8(30, 32, 40))
    canvas.fillRoundedRect(Rect(40, 40, 340, 180), 18, Color.rgb8(70, 90, 200))
    canvas.fillRectGradient(Rect(60, 60, 320, 160), Color.rgb8(255, 120, 80), Color.rgb8(255, 220, 90), Color.rgb8(150, 60, 220), Color.rgb8(60, 200, 255))
    canvas.pushClip(Rect(400, 40, 560, 180))
    canvas.drawImage(checker, Rect(380, 30, 600, 200), Rect(0, 0, 4, 2))
    canvas.fillCircle(mouse, 50, Color(1, 1, 1, 0.35f))
    canvas.popClip()
    canvas.strokeCircle(mouse, 40 + 10 * math.sin(frame * 0.05), 3, Color.White)
    canvas.strokeRect(Rect(40, 220, 340, 460), 2, Color.rgb8(120, 130, 160))
    canvas.strokeLine(Point(60, 240), Point(320, 440), 4, Color.rgb8(255, 90, 90))
    canvas.fillEllipse(Point(600, 340), 140, 80, Color(0.2f, 0.9f, 0.5f, 0.6f))
    textRenderer.draw(canvas, title, Point(40, 6), Color.White)
    textRenderer.draw(canvas, para, Point(400, 220), Color.rgb8(210, 215, 230))
    textRenderer.draw(canvas, code, Point(400, 330), Color.rgb8(130, 220, 160))
    gpu.submit(canvas.finish())
    frame += 1
    Thread.sleep(16)
  win.close()
