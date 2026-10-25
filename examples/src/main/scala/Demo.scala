import scalaui.color.Color
import scalaui.geom.Point
import scalaui.ui.*

@main def demo(args: String*): Unit =
  if args.headOption.contains("damageBench") then return scalaui.examples.damageBench()
  if args.contains("canvas") then return canvasDemo()
  if args.headOption.contains("viewer") then return viewerDemo(args.lift(1))
  val name = Var("Ferris")
  val notes = Var("Multi-line\ntext editing:\nselect, copy, paste.")
  val enabled = Var(true)
  val volume = Var(0.35)
  val mode = Var("fast")
  val choice = Var(1)
  val counter = Var(0)
  val progress = Var(0.6)
  val dark = Var(true)
  val tab = Var(0)
  val tint = Var(Color(0.9f, 0.4f, 0.2f, 1f))
  val amount = Var(12.5)
  val showWin = Var(true)
  val logo = scalaui.svg.SvgDocument.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
    <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ff7a59"/><stop offset="1" stop-color="#7a5cff"/></linearGradient></defs>
    <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#g)"/>
    <path d="M20 40 L32 16 L44 40 M25 33 H39" fill="none" stroke="white" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/></svg>""")

  def basics(ui: Ui): Unit =
    ui.horizontal { h =>
      h.label("Name:")
      h.textEdit(name, hint = "type here")
      if h.button("Clear").clicked then name.value = ""
    }
    ui.label(s"Hello, ${name.value}!")
    ui.horizontal { h =>
      val b = h.button("Click me")
      if b.clicked then counter.value += 1
      h.tooltip(b, "Increments the counter")
      h.label(s"clicked ${counter.value} times")
    }
    ui.checkbox(enabled, "Enabled")
    if ui.checkbox(dark, "Dark theme").changed then ui.ctx.style = if dark.value then Style.Dark else Style.Light
    ui.horizontal { h => h.radio(mode, "slow", "Slow"); h.radio(mode, "fast", "Fast"); h.radio(mode, "turbo", "Turbo") }
    ui.slider(volume, 0, 1, "volume")
    ui.slider(progress, 0, 1, "progress")
    ui.progressBar(progress.value, 300)
    ui.horizontal { h => h.label("Combo:"); h.comboBox(choice, Seq("Alpha", "Beta", "Gamma", "Delta")) }
    ui.collapsing("Notes", defaultOpen = true) { c => c.textEdit(notes, multiline = true, rows = 4, width = Some(420)) }
    ui.collapsing("Scroll area") { c =>
      c.scrollArea(120, Some(420)) { s => (1 to 30).foreach(i => s.label(s"Row $i — scroll with the wheel or drag the bar")) }
    }
    ui.group { g => g.label("Grouped content"); g.monospace("val x = 42 // monospace") }

  App.run("scalaui demo", 760, 820) { ui =>
    ui.menuBar { b =>
      b.menu("File") { m => m.menuItem("New", "⌘N"); m.menuItem("Open…", "⌘O"); m.separator(); m.menuItem("Quit", "⌘Q") }
      b.menu("View") { m => if m.menuItem("Toggle window").clicked then showWin.value = !showWin.value }
    }
    ui.window("Floating window", Point(430, 400), 300, Some(showWin)) { w =>
      w.label("Drag my title bar.")
      w.colorEdit(tint)
      w.dragValue(amount, suffix = " px")
    }
    ui.horizontal { h => h.svg(logo, scalaui.geom.Size(40, 40)); h.vertical { v => v.heading("scalaui widgets"); v.weak("Pure Scala Native · Metal · no third-party C") } }
    ui.tabBar(tab, Seq("Basics", "Grid", "Colors", "Rich text"))
    ui.space(4)
    tab.value match
      case 1 =>
        ui.grid(3) { g =>
          for (k, v, u) <- Seq(("Width", "1920", "px"), ("Height", "1080", "px"), ("Color depth", "8", "bit")) do
            g.cell(_.label(k)); g.cell(_.label(v)); g.cell(_.weak(u))
        }
      case 3 =>
        ui.richText(Seq(
          ui.span("Rich text ", bold = true, size = Some(22)),
          ui.span("mixes "), ui.span("bold", bold = true), ui.span(", "), ui.span("italic", italic = true), ui.span(", "),
          ui.span("bold italic", bold = true, italic = true), ui.span(", "), ui.span("monospace", monospace = true), ui.span(", "),
          ui.span("colored", color = Some(Color.rgb8(255, 140, 90))), ui.span(", "), ui.span("underlined", underline = true), ui.span(", "),
          ui.span("struck", strike = true), ui.span(" and "), ui.span("highlighted", color = Some(Color.Black), background = Some(Color.rgb8(255, 220, 90))),
          ui.span(" runs in one wrapped paragraph, with kerning (AV To Wa) and font fallback ─ │ ┼ ▓ from Hack when Noto lacks a glyph."),
        ))
      case 2 => ui.horizontal { h => h.label("Tint:"); h.colorEdit(tint); h.dragValue(amount, suffix = " px") }
      case _ => basics(ui)
  }
