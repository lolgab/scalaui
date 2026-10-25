package scalaui.ui

import scalaui.color.Color
import scalaui.geom.*
import scalaui.window.{AccessRole, CursorIcon, Key}

/** Application chrome: toolbars, ribbon, icon buttons, dropdown buttons, status bar, dialogs, segmented controls, badges, cards. */
extension (ui: Ui)

  // --- bars ---

  /** A full-width tool strip `height` tall; `body` lays widgets out left to right, vertically centered on 28-point controls. */
  def toolbar(height: Double = 40)(body: Ui => Unit): Unit =
    val ctx = ui.ctx; val st = ui.style
    val r = ui.allocate(ui.region.x1 - ui.region.x0 + ctx.padX * 2, height).translate(Vec2(-ctx.padX, 0))
    ctx.canvas.fillRect(r, st.chrome)
    ctx.canvas.fillRect(Rect(r.x0, r.y1 - 1, r.x1, r.y1), st.border)
    val c = ui.child(Rect(r.x0 + ctx.padX, r.y0 + (height - 28) / 2, r.x1 - ctx.padX, r.y1), false)
    body(c)
    ui.space(-st.itemSpacing.y)

  /** A tab strip over a band of grouped commands (Office-style ribbon). `body` receives the selected tab and the band's row;
    * fill it with [[ribbonGroup]]s. */
  def ribbon(selected: Var[Int], tabs: Seq[String], height: Double = 96)(body: (Int, Ui) => Unit): Unit =
    val ctx = ui.ctx; val st = ui.style
    val strip = ui.allocate(ui.region.x1 - ui.region.x0 + ctx.padX * 2, st.minHeight + 8).translate(Vec2(-ctx.padX, 0))
    ctx.canvas.fillRect(strip, st.tabStrip)
    val sc = ui.child(Rect(strip.x0 + ctx.padX - 4, strip.y0 + 1, strip.x1, strip.y1), true)
    sc.tabBar(selected, tabs)
    ui.space(-st.itemSpacing.y)
    val band = ui.allocate(strip.width, height).translate(Vec2(-ctx.padX, 0))
    ctx.canvas.fillRect(band, st.chrome)
    ctx.canvas.fillRect(Rect(band.x0, band.y1 - 1, band.x1, band.y1), st.border)
    val c = ui.child(Rect(band.x0 + ctx.padX, band.y0 + 6, band.x1, band.y1), false)
    body(selected.value, c)
    ui.space(-st.itemSpacing.y)

  /** A titled group of commands in a ribbon band: content on top, caption below, a divider on the right. Call on the band's row. */
  def ribbonGroup(title: String)(body: Ui => Unit): Unit =
    val ctx = ui.ctx; val st = ui.style
    val capH = 18.0
    val top = ui.region.y0; val bottom = ui.region.y1
    val x0 = ui.cursorPoint.x
    val c = ui.child(Rect(x0, top, ui.region.x1, bottom - capH - 6), false)
    body(c)
    val u = c.usedRect
    val cap = ctx.layout(title, st.caption)
    val w = math.max(u.width, cap.width) + 14
    ctx.paintText(cap, Point(x0 + (w - 14 - cap.width) / 2, bottom - capH - 4 + (capH - cap.height) / 2), st.groupLabel)
    ctx.canvas.fillRect(Rect(x0 + w - 7, top + 2, x0 + w - 6, bottom - 10), st.border)
    ui.allocate(w, bottom - top)

  /** A full-width bar at the bottom of the window (status bar); the remaining region ends above it. */
  def statusBar(height: Double = 26)(body: Ui => Unit): Unit = ui.bottomBar(height, ui.style.statusBar)(body)

  /** Fills the rest of the current region with `color` (e.g. the workspace behind documents) and lays `body` out inside with `padding`. */
  def workspace(color: Option[Color] = None, padding: Double = 16)(body: Ui => Unit): Unit =
    val ctx = ui.ctx
    val r = Rect(ui.region.x0 - ctx.padX, ui.cursorPoint.y, ui.region.x1 + ctx.padX, ui.region.y1)
    ctx.canvas.fillRect(r, color.getOrElse(ui.style.canvas))
    val c = ui.child(Rect(r.x0 + padding, r.y0 + padding, r.x1 - padding, r.y1 - padding), true)
    body(c)

  /** Lays `body` out inside the current region shrunk by `amount` on each side. */
  def padded(amount: Double)(body: Ui => Unit): Unit =
    val c = ui.child(Rect(ui.cursorPoint.x + amount, ui.cursorPoint.y + amount, ui.region.x1 - amount, ui.region.y1 - amount), true)
    body(c)
    val u = c.usedRect
    ui.allocate(u.width + 2 * amount, u.height + 2 * amount)

  // --- buttons ---

  /** A square tool button with an icon; `selected` shows it toggled on. Pass `tip` for a tooltip (also its accessible name). */
  def iconButton(icon: Icon, tip: String = "", selected: Boolean = false, size: Double = 28): Response =
    val ctx = ui.ctx; val st = ui.style
    val rect = ui.allocate(size, size)
    val id = ui.nextId()
    val r = ctx.interact(rect, id, focusable = true)
    val h = ctx.hoverAmount(id, r.hovered)
    val fill =
      if r.pressed then st.widgetActive
      else if selected then Style.mix(st.checked, st.widgetHover, h * 0.5)
      else if h > 0.01 then st.widgetHover.withAlpha((h * st.widgetHover.a).toFloat)
      else Color.Transparent
    if fill.a > 0 then ctx.canvas.fillRoundedRect(rect, st.rounding, fill)
    val ic = rect.inflate(-(size - 16) / 2, -(size - 16) / 2)
    ctx.paintIcon(icon, ic, if selected then st.accentBright else Style.mix(st.icon, st.textStrong, h))
    if tip.nonEmpty then ui.tooltip(r, tip)
    ctx.access(AccessRole.Button, tip, rect, checked = if selected then Some(true) else None)
    r

  /** A large command button: icon over a caption (ribbon style). `menu` adds a dropdown arrow to the caption. */
  def toolButton(icon: Icon, label: String, selected: Boolean = false, menu: Boolean = false): Response =
    val ctx = ui.ctx; val st = ui.style
    val l = ctx.layout(label, st.captionSize + 1, weight = Weight.Medium)
    val extra = if menu then 12.0 else 0.0
    val rect = ui.allocate(math.max(52, l.width + extra + 16), 64)
    val id = ui.nextId()
    val r = ctx.interact(rect, id, focusable = true)
    val h = ctx.hoverAmount(id, r.hovered)
    val fill =
      if r.pressed then st.widgetActive
      else if selected then Style.mix(st.checked, st.widgetHover, h * 0.5)
      else st.widgetHover.withAlpha((h * st.widgetHover.a).toFloat)
    if fill.a > 0.01 then ctx.canvas.fillRoundedRect(rect, st.rounding + 1, fill)
    val col = if selected then st.accentBright else Style.mix(st.icon, st.textStrong, h)
    ctx.paintIcon(icon, Rect(rect.center.x - 12, rect.y0 + 7, rect.center.x + 12, rect.y0 + 31), col)
    val tx = rect.x0 + (rect.width - l.width - extra) / 2
    ctx.paintText(l, Point(tx, rect.y0 + 36), Style.mix(st.text, st.textStrong, h))
    if menu then ctx.paintIcon(Icons.chevronDown, Rect(tx + l.width + 1, rect.y0 + 36 + (l.height - 12) / 2, tx + l.width + 13, rect.y0 + 36 + (l.height + 12) / 2), st.textWeak)
    ctx.access(AccessRole.Button, label, rect, checked = if selected then Some(true) else None)
    r

  /** A button that opens a drop-down; `body` runs while open (menu items, separators, anything). */
  def menuButton(label: String, icon: Option[Icon] = None, kind: ButtonKind = ButtonKind.Default)(body: Ui => Unit): Response =
    val ctx = ui.ctx; val st = ui.style
    val l = ctx.layout(label, st.fontSize, weight = Weight.Medium)
    val iconW = if icon.isDefined then 22.0 else 0.0
    val rect = ui.allocate(l.width + iconW + 40, math.max(l.height + 2 * st.buttonPadding.y, st.minHeight))
    val id = ui.nextId()
    val open = ctx.popupOwner == id
    val r = ctx.interact(rect, id, focusable = true)
    if r.clicked then ctx.popupOwner = if open then 0L else id
    val h = ctx.hoverAmount(id, r.hovered)
    val (fill, border, fg) = kind match
      case ButtonKind.Primary => (Style.mix(st.accent, if st.dark then Color.White else Color.Black, 0.1 * h), Color.Transparent, st.onAccent)
      case ButtonKind.Subtle => (if open then st.widgetActive else Style.mix(st.background, st.widgetHover, h), Color.Transparent, st.textStrong)
      case _ => (if open then st.widgetActive else Style.mix(st.widget, st.widgetHover, h), Style.mix(st.border, st.borderHover, h), st.textStrong)
    ctx.box(rect, st.rounding, fill, border)
    var x = rect.x0 + 10
    icon.foreach { i => ctx.paintIcon(i, Rect(x, rect.center.y - 8, x + 16, rect.center.y + 8), fg); x += iconW }
    ctx.paintText(l, Point(x, rect.y0 + (rect.height - l.height) / 2), fg)
    ctx.paintIcon(Icons.chevronDown, Rect(rect.x1 - 22, rect.center.y - 7, rect.x1 - 8, rect.center.y + 7), fg.withAlpha(0.8f))
    ctx.access(AccessRole.Button, label, rect)
    ui.dropdown(id, rect)(body)
    r

  /** An icon button with a drop-down (e.g. alignment, colors): the popup opens beneath it. */
  def iconMenuButton(icon: Icon, tip: String)(body: Ui => Unit): Response =
    val ctx = ui.ctx; val st = ui.style
    val rect = ui.allocate(40, 28)
    val id = ui.nextId()
    val open = ctx.popupOwner == id
    val r = ctx.interact(rect, id, focusable = true)
    if r.clicked then ctx.popupOwner = if open then 0L else id
    val h = ctx.hoverAmount(id, r.hovered)
    val fill = if open then st.widgetActive else st.widgetHover.withAlpha((h * st.widgetHover.a).toFloat)
    if fill.a > 0.01 then ctx.canvas.fillRoundedRect(rect, st.rounding, fill)
    ctx.paintIcon(icon, Rect(rect.x0 + 6, rect.center.y - 8, rect.x0 + 22, rect.center.y + 8), Style.mix(st.icon, st.textStrong, h))
    ctx.paintIcon(Icons.chevronDown, Rect(rect.x1 - 17, rect.center.y - 6, rect.x1 - 5, rect.center.y + 6), st.textWeak)
    if !open then ui.tooltip(r, tip)
    ctx.access(AccessRole.Button, tip, rect)
    ui.dropdown(id, rect, 170)(body)
    r

  /** The popup used by drop-down buttons: below `anchor`, closed by clicking elsewhere. */
  private[ui] def dropdown(id: Long, anchor: Rect, minWidth: Double = 190)(body: Ui => Unit): Unit =
    val ctx = ui.ctx
    if ctx.popupOwner == id then
      ctx.defer { () =>
        ctx.focusTrap = true
        val last = ctx.lastSizes.getOrElse(id, Size(minWidth, 10))
        val w = math.max(minWidth, last.width)
        val x0 = math.max(4, math.min(anchor.x0, ctx.canvas.width - w - 16))
        val pop = Rect(x0, anchor.y1 + 3, x0 + w + 8, anchor.y1 + 3 + last.height + 8)
        ui.popupFrame(pop)
        val c = new Ui(ctx, Rect(pop.x0 + 4, pop.y0 + 4, pop.x1 - 4, pop.y1 + 1000), true, id)
        body(c)
        val u = c.usedRect
        ctx.lastSizes(id) = Size(math.max(u.width, w), u.height)
        if ctx.input.pressed && !ctx.input.mouse.exists(p => pop.contains(p) || anchor.contains(p)) then ctx.popupOwner = 0L
        if ctx.input.keys.exists(_.key == Key.Escape) then ctx.popupOwner = 0L
        if ctx.takeKey(Key.Down) then ctx.pendingFocusMove = 1 else if ctx.takeKey(Key.Up) then ctx.pendingFocusMove = -1
      }

  /** A row of mutually exclusive options drawn as one pill (e.g. view modes). */
  def segmented(selected: Var[Int], labels: Seq[String], icons: Seq[Icon] = Nil): Response =
    val ctx = ui.ctx; val st = ui.style
    val ls = labels.zipWithIndex.map { (t, i) => (t, ctx.layout(t, st.fontSize - 0.5, weight = Weight.Medium), icons.lift(i)) }
    val widths = ls.map((t, l, ic) => l.width + (if ic.isDefined then (if t.isEmpty then 16 else 22) else 0) + 22)
    val rect = ui.allocate(widths.sum + 4, st.minHeight + 4)
    ctx.box(rect, st.rounding + 1, if st.dark then st.inputBackground else st.widget, st.border)
    var x = rect.x0 + 2
    var changed = false
    ls.zipWithIndex.foreach { case ((t, l, ic), i) =>
      val seg = Rect(x, rect.y0 + 2, x + widths(i), rect.y1 - 2)
      val id = ui.nextId()
      val r = ctx.interact(seg, id, focusable = true)
      if r.clicked && selected.value != i then { selected.value = i; changed = true }
      val on = selected.value == i
      val a = ctx.animate(id, if on then 1.0 else 0.0, 0.12)
      if a > 0.01 then ctx.box(seg, st.rounding, Style.mix(if st.dark then st.inputBackground else st.widget, if st.dark then st.widgetActive else st.menu, a), if st.dark then Color.Transparent else Style.mix(st.widget, st.border, a))
      else if r.hovered then ctx.canvas.fillRoundedRect(seg, st.rounding, st.widgetHover.withAlpha(0.5f))
      val fg = if on then st.textStrong else st.textWeak
      var tx = seg.x0 + 11
      ic.foreach { icn => ctx.paintIcon(icn, Rect(tx, seg.center.y - 8, tx + 16, seg.center.y + 8), fg); tx += (if t.isEmpty then 16 else 22) }
      if t.nonEmpty then ctx.paintText(l, Point(tx, seg.y0 + (seg.height - l.height) / 2), fg)
      ctx.access(AccessRole.Tab, t, seg, checked = Some(on))
      x += widths(i)
    }
    Response(rect, changed = changed)

  /** A navigation row for sidebars: icon, label, optional trailing badge text. */
  def navItem(icon: Icon, label: String, selected: Boolean, badge: String = ""): Response =
    val ctx = ui.ctx; val st = ui.style
    val rect = ui.allocate(math.max(ui.availableWidth, 80), st.minHeight + 4)
    val id = ui.nextId()
    val r = ctx.interact(rect, id, focusable = true)
    val h = ctx.hoverAmount(id, r.hovered)
    if selected then ctx.canvas.fillRoundedRect(rect, st.rounding, st.checked)
    else if h > 0.01 then ctx.canvas.fillRoundedRect(rect, st.rounding, st.widgetHover.withAlpha((h * 0.8).toFloat))
    val fg = if selected then st.textStrong else Style.mix(st.text, st.textStrong, h)
    ctx.paintIcon(icon, Rect(rect.x0 + 8, rect.center.y - 8, rect.x0 + 24, rect.center.y + 8), if selected then st.accentBright else Style.mix(st.icon, st.textStrong, h))
    val l = ctx.layout(label, st.fontSize, weight = if selected then Weight.Medium else Weight.Regular)
    ctx.paintText(l, Point(rect.x0 + 32, rect.y0 + (rect.height - l.height) / 2), fg)
    if badge.nonEmpty then
      val bl = ctx.layout(badge, st.captionSize, weight = Weight.Medium)
      val br = Rect(rect.x1 - bl.width - 18, rect.center.y - 9, rect.x1 - 6, rect.center.y + 9)
      ctx.canvas.fillRoundedRect(br, 9, if selected then st.accent else st.widgetActive)
      ctx.paintText(bl, Point(br.x0 + 6, br.y0 + (br.height - bl.height) / 2), if selected then st.onAccent else st.text)
    ctx.access(AccessRole.ListItem, label, rect, checked = Some(selected))
    r

  // --- small displays ---

  /** A small rounded label, e.g. a status. */
  def badge(text: String, color: Option[Color] = None): Response =
    val ctx = ui.ctx; val st = ui.style
    val c = color.getOrElse(st.accent)
    val l = ctx.layout(text, st.captionSize + 0.5, weight = Weight.Medium)
    val rect = ui.allocate(l.width + 14, l.height + 4)
    ctx.canvas.fillRoundedRect(rect, rect.height / 2, c.withAlpha(if st.dark then 0.22f else 0.14f))
    ctx.paintText(l, Point(rect.x0 + 7, rect.y0 + 2), if st.dark then Style.mix(c, Color.White, 0.35) else Style.mix(c, Color.Black, 0.25))
    ctx.access(AccessRole.Label, text, rect)
    Response(rect)

  /** A keyboard shortcut cap such as `Ctrl+S`. */
  def keycap(text: String): Response =
    val ctx = ui.ctx; val st = ui.style
    val l = ctx.layout(text, st.captionSize + 0.5, monospace = true)
    val rect = ui.allocate(l.width + 10, l.height + 4)
    ctx.box(rect, 4, st.widget, st.border)
    ctx.paintText(l, Point(rect.x0 + 5, rect.y0 + 2), st.textWeak)
    Response(rect)

  /** A framed panel with an optional title, sized from the previous frame. */
  def card(title: String = "")(body: Ui => Unit): Unit =
    ui.group { g =>
      if title.nonEmpty then { g.strong(title); g.space(2) }
      body(g)
    }

  /** A text field with a search icon and hint. */
  def searchBox(value: Var[String], hint: String = "Search", width: Double = 220): Response =
    val ctx = ui.ctx; val st = ui.style
    val r = ui.textEdit(value, width = Some(width), hint = hint)
    ctx.paintIcon(Icons.search, Rect(r.rect.x1 - 22, r.rect.center.y - 7, r.rect.x1 - 8, r.rect.center.y + 7), st.textWeak)
    r

  // --- dialogs ---

  /** A modal dialog with a dimmed backdrop; shown while `open` is true and closed by its X button or Escape.
    * `body` lays out the content; call [[dialogButtons]] at the end for the action row. */
  def modal(open: Var[Boolean], title: String, width: Double = 420)(body: Ui => Unit): Unit =
    if open.value then
      val ctx = ui.ctx; val st = ui.style
      val id = ui.nextId()
      ctx.defer { () =>
        ctx.focusTrap = true
        val last = ctx.lastSizes.getOrElse(id, Size(width - 40, 60))
        val full = Rect(0, 0, ctx.canvas.width, ctx.canvas.height)
        val a = ctx.animate(id, 1.0, 0.12)
        ctx.canvas.fillRect(full, Color(0, 0, 0, (if st.dark then 0.55 else 0.35).toFloat * a.toFloat))
        ctx.cover(full)
        val th = 44.0
        val h = th + last.height + 28
        val x = (ctx.canvas.width - width) / 2; val y = math.max(24, (ctx.canvas.height - h) / 2.5)
        val dlg = Rect(x, y, x + width, y + h)
        ctx.shadow(dlg, st.windowRounding, 36, st.shadow, Vec2(0, 12))
        ctx.box(dlg, st.windowRounding, st.menu, st.borderStrong)
        val tl = ctx.layout(title, st.heading)
        ctx.paintText(tl, Point(x + 20, y + (th - tl.height) / 2 + 2), st.textStrong)
        val cb = Rect(dlg.x1 - 38, y + 8, dlg.x1 - 10, y + 36)
        val cr = ctx.interact(cb, id + 1)
        if cr.hovered then ctx.canvas.fillRoundedRect(cb, st.rounding, st.widgetHover)
        ctx.paintIcon(Icons.close, cb.inflate(-6, -6), if cr.hovered then st.textStrong else st.textWeak)
        if cr.clicked || ctx.input.keys.exists(_.key == Key.Escape) then open.value = false
        val c = new Ui(ctx, Rect(x + 20, y + th, x + width - 20, y + th + 4000), true, id + 2)
        body(c)
        ctx.lastSizes(id) = Size(width - 40, c.usedRect.height)
        ctx.requestRepaint()
      }

  /** A right-aligned row of dialog actions, `primary` last. Returns which button was pressed (0 = cancel/secondary, 1 = primary). */
  def dialogButtons(secondary: String, primary: String, danger: Boolean = false): Option[Int] =
    var res: Option[Int] = None
    ui.space(6)
    ui.horizontal { h =>
      val ctx = h.ctx
      val sl = ctx.layout(secondary, h.style.fontSize, weight = Weight.Medium)
      val pl = ctx.layout(primary, h.style.fontSize, weight = Weight.Medium)
      val total = sl.width + pl.width + 2 * (2 * h.style.buttonPadding.x + 8) + h.style.itemSpacing.x
      h.space(math.max(0, ui.availableWidth - total))
      if h.button(secondary, ButtonKind.Default).clicked then res = Some(0)
      if h.button(primary, if danger then ButtonKind.Danger else ButtonKind.Primary).clicked then res = Some(1)
    }
    res

  /** Hint for hosts: a pointer cursor over clickable custom areas. */
  def pointerOnHover(r: Response): Unit = if r.hovered then ui.ctx.cursorIcon = CursorIcon.Pointer
