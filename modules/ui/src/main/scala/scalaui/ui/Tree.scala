package scalaui.ui

import scalaui.geom.*
import scalaui.window.{AccessRole, Key}
import scala.collection.mutable

/** State of a [[Ui.tree]]: which nodes are expanded, selected and checked (all by node key), the keyboard cursor and scroll offset.
  * Keep one per tree; expansion, selection and scrolling persist across frames. */
final class TreeState:
  val expanded: mutable.HashSet[Any] = mutable.HashSet.empty
  /** Selected keys in selection order. */
  val selected: mutable.LinkedHashSet[Any] = mutable.LinkedHashSet.empty
  val checked: mutable.HashSet[Any] = mutable.HashSet.empty
  /** The row the keyboard acts on (the last clicked or navigated-to row). */
  var cursor: Option[Any] = None
  /** Start of shift-click / shift-arrow ranges. */
  var anchor: Option[Any] = None
  var scroll = 0.0
  /** Key of the node activated this frame (Enter or double click). */
  var activated: Option[Any] = None
  /** `(node key, payload)` of a drag released over a node this frame (needs `canDrop`). */
  var dropped: Option[(Any, Any)] = None
  /** Key whose checkbox was toggled this frame. */
  var toggledCheck: Option[Any] = None
  private[ui] val seen: mutable.HashSet[Any] = mutable.HashSet.empty
  private[ui] var typed = ""
  private[ui] var typedAt = -10.0
  private[ui] var reveal: Option[Any] = None

  def isExpanded(key: Any): Boolean = expanded.contains(key)
  def expand(key: Any): Unit = { expanded += key; seen += key }
  def collapse(key: Any): Unit = { expanded -= key; seen += key }
  def toggle(key: Any): Unit = if isExpanded(key) then collapse(key) else expand(key)
  def isSelected(key: Any): Boolean = selected.contains(key)
  /** Selects only `key` and scrolls it into view on the next frame. */
  def select(key: Any): Unit = { selected.clear(); selected += key; cursor = Some(key); anchor = Some(key); reveal = Some(key) }
  def clearSelection(): Unit = selected.clear()
  /** Scrolls the row for `key` into view on the next frame (its ancestors must be expanded). */
  def scrollTo(key: Any): Unit = reveal = Some(key)

private final case class TreeRow[T](node: T, key: Any, depth: Int, parent: Int, hasChildren: Boolean, label: String)

extension (ui: Ui)

  /** A data-driven tree view with its own selection state in a fixed-height, scrollable, virtualised viewport.
    *
    * Click selects; with `multiSelect` Ctrl/Cmd-click toggles and Shift-click extends from the anchor row. Keyboard (when focused):
    * Up/Down/Home/End/PageUp/PageDown move (Shift extends in multi-select), Right expands or enters the first child, Left collapses or
    * goes to the parent, Enter activates, Space toggles the checkbox (or the selection in multi-select) and typing jumps to the next row whose
    * label starts with the typed text. Double click or Enter reports `doubleClicked` on the response and sets [[TreeState.activated]];
    * double click also toggles expansion. A selection or check change reports `changed`.
    *
    * `dragPayload` makes rows drag sources (reusing [[Ui.draggable]]); `canDrop(node, payload)` makes them drop targets reusing
    * [[Ui.dropTarget]], reporting the release in [[TreeState.dropped]]. `initiallyExpanded` is consulted the first time a node is shown.
    */
  def tree[T](state: TreeState, roots: Seq[T], children: T => Seq[T], label: T => String, height: Double,
      key: T => Any = (t: T) => t, multiSelect: Boolean = false, checkboxes: Boolean = false, initiallyExpanded: T => Boolean = (_: T) => false,
      dragPayload: Option[T => Any] = None, canDrop: Option[(T, Any) => Boolean] = None): Response =
    val ctx = ui.ctx; val st = ui.style
    val id = ui.nextId()
    val rowH = st.minHeight
    val indentW = 18.0
    val outer = ui.allocate(math.max(ui.availableWidth, 80.0), height)
    state.activated = None; state.dropped = None; state.toggledCheck = None
    // visible rows in display order
    val rowsB = Vector.newBuilder[TreeRow[T]]
    def walk(nodes: Seq[T], depth: Int, parent: Int, count: () => Int): Unit =
      nodes.foreach { n =>
        val k = key(n); val kids = children(n)
        if state.seen.add(k) && initiallyExpanded(n) && kids.nonEmpty then state.expanded += k
        val idx = count()
        rowsB += TreeRow(n, k, depth, parent, kids.nonEmpty, label(n))
        if kids.nonEmpty && state.expanded.contains(k) then walk(kids, depth + 1, idx, count)
      }
    var counter = 0
    walk(roots, 0, -1, () => { val c = counter; counter += 1; c })
    val rows = rowsB.result()
    val n = rows.length
    val indexOf = rows.zipWithIndex.map((r, i) => r.key -> i).toMap

    ctx.canvas.fillRoundedRect(outer, st.rounding, st.inputBackground)
    val contentH = n * rowH
    val maxScroll = math.max(0.0, contentH - outer.height)
    var off = state.scroll.max(0).min(maxScroll)
    val barRect = Rect(outer.x1 - 10, outer.y0, outer.x1, outer.y1)
    var barHot = false
    if maxScroll > 0 then
      val br = ctx.interact(barRect, id + 2, drag = true)
      barHot = br.hovered || br.pressed
      if br.dragged then ctx.input.mouse.foreach(m => off = ((m.y - outer.y0) / outer.height * contentH - outer.height / 2).max(0).min(maxScroll))
    ctx.registerFocus(id)
    val hoveredTree = ctx.hoverable(outer, id)
    if hoveredTree && !ctx.wheelClaimed(ctx.input.scroll.y) then off = (off - ctx.input.scroll.y).max(0).min(maxScroll)
    val focused = ctx.focusId == id
    var changed = false
    var activatedNow = false

    // --- selection helpers ---
    def selectRange(a: Int, b: Int, additive: Boolean): Unit =
      if !additive then state.selected.clear()
      (a.min(b) to a.max(b)).foreach(i => state.selected += rows(i).key)
    def moveTo(i: Int): Unit =
      if n > 0 then
        val c = i.max(0).min(n - 1)
        val shift = ctx.input.mods.shift
        val before = state.selected.toSeq
        if multiSelect && shift then
          val a = state.anchor.flatMap(indexOf.get).getOrElse(c)
          selectRange(a, c, additive = false)
        else { state.selected.clear(); state.selected += rows(c).key; state.anchor = Some(rows(c).key) }
        state.cursor = Some(rows(c).key); state.reveal = Some(rows(c).key)
        if state.selected.toSeq != before then changed = true
    def toggleCheck(k: Any): Unit =
      if !state.checked.remove(k) then state.checked += k
      state.toggledCheck = Some(k); changed = true

    // --- keyboard ---
    if focused && n > 0 then
      val cur = state.cursor.flatMap(indexOf.get).getOrElse(-1)
      val page = math.max(1, (outer.height / rowH).toInt - 1)
      if ctx.takeKey(Key.Down) then moveTo(if cur < 0 then 0 else cur + 1)
      else if ctx.takeKey(Key.Up) then moveTo(if cur < 0 then 0 else cur - 1)
      else if ctx.takeKey(Key.PageDown) then moveTo(cur + page)
      else if ctx.takeKey(Key.PageUp) then moveTo(cur - page)
      else if ctx.takeKey(Key.Home) then moveTo(0)
      else if ctx.takeKey(Key.End) then moveTo(n - 1)
      else if ctx.takeKey(Key.Right) && cur >= 0 then
        val r = rows(cur)
        if r.hasChildren && !state.isExpanded(r.key) then state.expand(r.key)
        else if r.hasChildren then moveTo(cur + 1)
      else if ctx.takeKey(Key.Left) && cur >= 0 then
        val r = rows(cur)
        if r.hasChildren && state.isExpanded(r.key) then state.collapse(r.key)
        else if r.parent >= 0 then moveTo(r.parent)
      else if ctx.takeKey(Key.Enter) && cur >= 0 then { state.activated = Some(rows(cur).key); activatedNow = true }
      else if ctx.takeKey(Key.Space) && cur >= 0 then
        if checkboxes then toggleCheck(rows(cur).key)
        else if multiSelect then
          val k = rows(cur).key
          if !state.selected.remove(k) then state.selected += k
          changed = true
      else
        // type-ahead
        val i = ctx.input.keys.indexWhere(k => k.text.nonEmpty && !k.mods.shortcut && !k.mods.alt && !k.text.head.isControl && k.key != Key.Tab)
        if i >= 0 then
          val kp = ctx.input.keys.remove(i)
          val now = ctx.time
          if now - state.typedAt > 1.0 then state.typed = ""
          state.typedAt = now
          state.typed += kp.text.toLowerCase
          val cycle = state.typed.length > 1 && state.typed.forall(_ == state.typed.head)
          val prefix = if cycle then state.typed.take(1) else state.typed
          val start = if state.typed.length == 1 || cycle then cur + 1 else math.max(cur, 0)
          val hit = (0 until n).map(d => (start + d) % n).find(j => rows(j).label.toLowerCase.startsWith(prefix))
          hit.foreach(moveTo)
    // keep a revealed row inside the viewport
    state.reveal.foreach { k =>
      indexOf.get(k).foreach { i =>
        val top = i * rowH; val bot = top + rowH
        if top < off then off = top else if bot > off + outer.height then off = bot - outer.height
        off = off.max(0).min(maxScroll)
      }
      state.reveal = None
    }
    state.scroll = off

    // --- rows ---
    val first = math.max(0, (off / rowH).toInt)
    val last = math.min(n - 1, ((off + outer.height) / rowH).toInt)
    ctx.pushClip(outer)
    val rowRight = outer.x1 - (if maxScroll > 0 then 10 else 0)
    var i = first
    while i <= last do
      val r = rows(i)
      val ry = outer.y0 + i * rowH - off
      val rr = Rect(outer.x0, ry, rowRight, ry + rowH)
      val sel = state.selected.contains(r.key)
      val hov = ctx.hoverable(rr.intersect(outer), id + 1000 + i) && !ctx.input.mouse.exists(m => barRect.contains(m) && maxScroll > 0)
      val x0 = outer.x0 + 4 + r.depth * indentW
      val arrow = Rect(x0, ry, x0 + 16, ry + rowH)
      val boxX = x0 + 18
      val box = Rect(boxX, ry + (rowH - 14) / 2, boxX + 14, ry + (rowH + 14) / 2)
      val textX = if checkboxes then box.x1 + 6 else x0 + 20
      if sel then ctx.canvas.fillRect(rr, if focused then st.checked else Style.mix(st.checked, st.inputBackground, 0.4))
      else if hov then ctx.canvas.fillRect(rr, st.widgetHover.withAlpha(0.5f))
      if state.cursor.contains(r.key) && focused && ctx.keyboardNav then ctx.canvas.strokeRect(rr.inflate(-1, -1), 1.5, st.accent)
      // drag source and drop target hooks
      val rowUi = new Ui(ctx, rr, false, id + 5000 + (r.key.hashCode & 0xFFFFF))
      dragPayload.foreach(f => rowUi.draggable(rr.intersect(outer), f(r.node), r.label))
      canDrop.foreach { accept =>
        rowUi.dropTarget(rr.intersect(outer))(p => accept(r.node, p)).foreach(p => state.dropped = Some((r.key, p)))
      }
      if hov && ctx.input.pressed then
        ctx.focusId = id; ctx.focusClaimed = true
        val m = ctx.input.mouse.get
        if r.hasChildren && arrow.contains(m) then state.toggle(r.key)
        else if checkboxes && box.inflate(2, 2).contains(m) then { toggleCheck(r.key); state.cursor = Some(r.key) }
        else
          val before = state.selected.toSeq
          val mods = ctx.input.mods
          if multiSelect && mods.shift then
            val a = state.anchor.flatMap(indexOf.get).getOrElse(i)
            selectRange(a, i, additive = mods.shortcut)
          else if multiSelect && mods.shortcut then
            if !state.selected.remove(r.key) then state.selected += r.key
            state.anchor = Some(r.key)
          else { state.selected.clear(); state.selected += r.key; state.anchor = Some(r.key) }
          state.cursor = Some(r.key)
          if state.selected.toSeq != before then changed = true
          if ctx.input.doubleClicked then
            state.activated = Some(r.key); activatedNow = true
            if r.hasChildren then state.toggle(r.key)
      if r.hasChildren then
        ctx.paintIcon(if state.isExpanded(r.key) then Icons.chevronDown else Icons.chevronRight, arrow.inflate(-1, -(rowH - 14) / 2),
          if hov || sel then st.textStrong else st.textWeak)
      if checkboxes then
        val on = state.checked.contains(r.key)
        ctx.box(box, 3, if on then st.accent else st.inputBackground, if on then st.accent else st.borderStrong)
        if on then ctx.paintIcon(Icons.check, box.inflate(-2, -2), st.onAccent)
      val l = ctx.layout(r.label, st.fontSize)
      ctx.pushClip(Rect(textX, ry, rowRight, ry + rowH))
      ctx.paintText(l, Point(textX, ry + (rowH - l.height) / 2), if sel || hov then st.textStrong else st.text)
      ctx.popClip()
      ctx.access(AccessRole.ListItem, r.label, rr, value = if r.hasChildren then (if state.isExpanded(r.key) then "expanded" else "collapsed") else "",
        checked = if checkboxes then Some(state.checked.contains(r.key)) else Some(sel))
      i += 1
    ctx.popClip()
    if maxScroll > 0 then
      val thumbH = math.max(24, outer.height * outer.height / contentH)
      val ty = outer.y0 + (off / maxScroll) * (outer.height - thumbH)
      val tw = if barHot then 6.0 else 4.0
      ctx.canvas.fillRoundedRect(Rect(outer.x1 - 2 - tw, ty, outer.x1 - 2, ty + thumbH), tw / 2, st.text.withAlpha(if barHot then 0.5f else 0.28f))
    ctx.outline(outer, st.rounding, if focused then st.accent else st.border)
    Response(outer, hovered = hoveredTree, changed = changed, focused = focused, doubleClicked = activatedNow)
