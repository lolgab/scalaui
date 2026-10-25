package scalaui.ui

import scalaui.geom.*
import scalaui.text.{Align, RichLayout, Span}
import scalaui.window.{AccessRole, CursorIcon}

/** A span of rich text that acts as a link: underlined, hover cursor, clickable and keyboard focusable. `target` is reported back
  * as `Response.link` (or to the `onLink` callback) when the link is activated. */
final case class RichLink(span: Span, target: String)

object RichLinks:
  /** Pixel rectangles (relative to the layout origin) covering the glyphs of `[from, to)`, one per visual line the range spans. */
  def fragments(l: RichLayout, from: Int, to: Int): Vector[Rect] =
    l.lines.flatMap { line =>
      val gs = line.glyphs.filter(g => g.cluster >= from && g.cluster < to)
      if gs.isEmpty then None
      else Some(Rect(gs.map(_.x).min, line.top, gs.map(g => g.x + g.advance).max, line.top + line.height))
    }

extension (ui: Ui)

  /** A link span: `text` styled like [[Ui.span]] but underlined in the accent color, reporting `target` when activated. */
  def link(text: String, target: String, bold: Boolean = false, italic: Boolean = false, monospace: Boolean = false): RichLink =
    val base = ui.span(text, bold, italic, monospace, color = Some(ui.style.accentBright), underline = true)
    RichLink(base, target)

  /** Multi-style text containing [[link]]s. Links wrap across lines like other text, show a pointer cursor and brighter color on
    * hover, take part in Tab focus order and are exposed to assistive technology with the link role. The activated link's target
    * is in `Response.link` and passed to `onLink`.
    */
  def richLinks(parts: Seq[Span | RichLink], align: Align = Align.Left)(onLink: String => Unit = _ => ()): Response =
    val ctx = ui.ctx; val st = ui.style
    val w = if ui.vertical then Some(ui.availableWidth) else None
    // character ranges of the links within the concatenated text
    var off = 0
    val ranges = Vector.newBuilder[(Int, Int, RichLink)]
    parts.foreach {
      case l: RichLink => ranges += ((off, off + l.span.text.length, l)); off += l.span.text.length
      case s: Span => off += s.text.length
    }
    val links = ranges.result()
    val ids = links.map(_ => ui.nextId())
    def build(hot: Int): RichLayout =
      var k = -1
      RichLayout.layout(parts.map {
        case s: Span => s
        case l: RichLink =>
          k += 1
          if k == hot then l.span.copy(style = l.span.style.copy(color = Style.mix(st.accentBright, st.textStrong, 0.35))) else l.span
      }, w, align)
    var lay = build(-1)
    val area = ui.allocate(lay.width, lay.height)
    val origin = area.origin
    val frags = links.map((a, b, _) => RichLinks.fragments(lay, a, b).map(_.translate(origin.toVec2)))
    // which link is under the pointer
    val mouse = ctx.input.mouse
    val hovered = frags.indices.indexWhere(i => frags(i).exists(f => ctx.hoverable(f, ids(i))))
    if hovered >= 0 then lay = build(hovered)
    ctx.paintRich(lay, origin)
    var result = Response(area)
    links.indices.foreach { i =>
      val fs = frags(i)
      if fs.nonEmpty then
        val under = mouse.flatMap(m => fs.find(_.contains(m))).getOrElse(fs.head)
        val r = ctx.interact(under, ids(i), focusable = true)
        if r.hovered then ctx.cursorIcon = CursorIcon.Pointer
        ctx.access(AccessRole.Link, links(i)._3.span.text, fs.reduce(_.union(_)), value = links(i)._3.target, focused = r.focused)
        if r.clicked then
          onLink(links(i)._3.target)
          result = result.copy(link = Some(links(i)._3.target), clicked = true, hovered = true)
        else if r.hovered then result = result.copy(hovered = true)
    }
    result
