package scalaui.ui

import scalaui.geom.*
import scalaui.window.{AccessRole, Key}
import scala.collection.mutable

/** Row heights of a variable-height list: measured or computed heights for some rows, an `estimate` for the rest. A Fenwick tree
  * over the differences makes the offset of a row and the row at an offset O(log n) for millions of rows. */
final class RowHeights:
  var estimate = 24.0
  private var n = 0
  private var tree = new Array[Double](1)
  private val known = mutable.HashMap.empty[Int, Double]

  private def add(i0: Int, d: Double): Unit =
    var i = i0 + 1
    while i <= n do { tree(i) += d; i += i & -i }
  /** Prepares for `count` rows with the given `estimate` (rebuilds the tree when either changed). */
  def reset(count: Int, est: Double): Unit =
    if count != n || est != estimate then
      estimate = est; n = count; tree = new Array[Double](n + 1)
      known.filterInPlace((i, _) => i < n)
      known.foreach((i, h) => add(i, h - estimate))
  def isKnown(i: Int): Boolean = known.contains(i)
  def height(i: Int): Double = known.getOrElse(i, estimate)
  /** Records the real height of row `i`; returns true if it changed noticeably. */
  def set(i: Int, h: Double): Boolean =
    val old = height(i)
    if known.contains(i) && math.abs(old - h) <= 0.5 then false
    else { known(i) = h; add(i, h - old); math.abs(old - h) > 0.5 }
  /** Top of row `i` (0 <= i <= count: `offset(count)` is the total height). */
  def offset(i: Int): Double =
    var s = 0.0; var k = i
    while k > 0 do { s += tree(k); k -= k & -k }
    i * estimate + s
  def total: Double = offset(n)
  /** The row containing y (clamped to the valid rows). */
  def rowAt(y: Double): Int =
    var lo = 0; var hi = n - 1
    while lo < hi do
      val mid = (lo + hi + 1) >>> 1
      if offset(mid) <= y then lo = mid else hi = mid - 1
    lo
  def clear(): Unit = { known.clear(); tree = new Array[Double](n + 1) }
  def knownCount: Int = known.size

private[ui] object VariableList:
  def run(ui: Ui, state: VirtualListState, count: Int, estimate: Double, height: Double, selectable: Boolean, heightOf: Option[Int => Double])(row: (Int, Ui) => Unit): Response =
    val ctx = ui.ctx; val st = ui.style
    val id = ui.nextId()
    val hs = state.heights
    hs.reset(count, math.max(1.0, estimate))
    val outer = ui.allocate(math.max(ui.availableWidth, 60.0), height)
    ctx.canvas.fillRoundedRect(outer, st.rounding, st.inputBackground)
    val viewH = outer.height
    // starting offset: keep the anchor row where it was even if heights above it were refined since
    var off =
      if state.anchorRow >= 0 && state.anchorRow < count && state.scroll == state.lastScroll then hs.offset(state.anchorRow) + state.anchorIntra
      else state.scroll
    var total = hs.total
    var maxScroll = math.max(0.0, total - viewH)
    off = off.max(0).min(maxScroll)
    val barRect = Rect(outer.x1 - 10, outer.y0, outer.x1, outer.y1)
    var barHot = false
    if maxScroll > 0 then
      val br = ctx.interact(barRect, id + 2, drag = true)
      barHot = br.hovered || br.pressed
      if br.dragged then ctx.input.mouse.foreach(m => off = ((m.y - outer.y0) / viewH * total - viewH / 2).max(0).min(maxScroll))
    val r = ctx.interact(outer, id, focusable = selectable)
    if r.hovered && !ctx.wheelClaimed(ctx.input.scroll.y) then off = (off - r.scroll.y).max(0).min(maxScroll)

    var changed = false
    var activated = r.clicked && !ctx.input.released // Enter / Space
    def select(i: Int): Unit =
      val c = i.max(0).min(count - 1)
      if count > 0 && !state.selected.contains(c) then { state.selected = Some(c); changed = true }
      if count > 0 then state.scrollTo(c)
    if selectable && r.focused && count > 0 then
      val page = math.max(1, (viewH / hs.estimate).toInt - 1)
      val cur = state.selected.getOrElse(-1)
      if ctx.takeKey(Key.Down) then select(cur + 1)
      else if ctx.takeKey(Key.Up) then select(if cur < 0 then 0 else cur - 1)
      else if ctx.takeKey(Key.PageDown) then select(cur + page)
      else if ctx.takeKey(Key.PageUp) then select(cur - page)
      else if ctx.takeKey(Key.Home) then select(0)
      else if ctx.takeKey(Key.End) then select(count - 1)

    // scroll-to-index: applied every frame until the row's height is known and the layout has settled
    if state.revealRow >= 0 && state.revealRow < count then
      val target = state.revealRow
      heightOf.foreach { f => var i = 0; while i <= target do { if !hs.isKnown(i) then hs.set(i, f(i)); i += 1 } } // exact offsets for the prefix
      val top = hs.offset(target); val bot = top + hs.height(target)
      if state.revealTop then off = top
      else if top < off then off = top else if bot > off + viewH then off = bot - viewH
      total = hs.total; maxScroll = math.max(0.0, total - viewH); off = off.max(0).min(maxScroll)
      if hs.isKnown(target) && !state.dirtyPrev then { state.revealRow = -1; state.revealTop = false }
    else state.revealRow = -1

    // height function: compute the rows in view before painting; refinements above the anchor shift the offset so content stays put
    val anchorRow = if state.anchorRow >= 0 && state.anchorRow < count then state.anchorRow else -1
    var dirty = false
    // measured rows are measured by building them once into an empty clip (nothing paints, nothing reacts), so heights are known before painting
    def dry(i: Int): Double =
      ctx.pushClip(Rect(outer.x0, outer.y0, outer.x0, outer.y0))
      val c = new Ui(ctx, Rect(outer.x0 + 8, outer.y0 + 1, outer.x1 - 18, outer.y0 + 20001), true, id + 5000 + i)
      row(i, c)
      ctx.popClip()
      math.max(1.0, c.usedRect.height + 2)
    val always = heightOf.isDefined
    Some(heightOf.getOrElse(dry)).foreach { f =>
      var pass = 0; var again = true
      while again && pass < 4 && count > 0 do
        again = false; pass += 1
        var i = hs.rowAt(off); var shift = 0.0
        while i < count && hs.offset(i) < off + viewH do
          val old = hs.height(i)
          if (always || !hs.isKnown(i)) && hs.set(i, f(i)) then
            dirty = true; again = true
            if anchorRow >= 0 && i < anchorRow then shift += hs.height(i) - old
          i += 1
        off += shift
        total = hs.total; maxScroll = math.max(0.0, total - viewH); off = off.max(0).min(maxScroll)
    }

    val first = if count > 0 then hs.rowAt(off) else 0
    val anchorIntra = off - (if count > 0 then hs.offset(first) else 0.0)
    ctx.pushClip(outer)
    val rowRight = outer.x1 - (if maxScroll > 0 then 10 else 0)
    var i = first
    var y = if count > 0 then outer.y0 + hs.offset(first) - off else outer.y1
    var lastBuilt = first - 1
    while i < count && y < outer.y1 do
      val h0 = hs.height(i)
      val rr = Rect(outer.x0, y, rowRight, y + h0)
      val sel = state.selected.contains(i)
      val hov = selectable && ctx.hoverable(rr.intersect(outer), id + 1000 + i)
      if sel then ctx.canvas.fillRect(rr, st.checked)
      else if hov then ctx.canvas.fillRect(rr, st.widgetHover.withAlpha(0.5f))
      if sel && ctx.keyboardNav && r.focused then ctx.canvas.strokeRect(rr.inflate(-1, -1), 1.5, st.accent)
      if hov && ctx.input.pressed then
        select(i); ctx.focusId = id; ctx.focusClaimed = true
        if ctx.input.doubleClicked then activated = true
      ctx.pushClip(rr)
      val c = new Ui(ctx, Rect(rr.x0 + 8, rr.y0 + 1, rr.x1 - 8, rr.y0 + 1 + (if heightOf.isDefined then h0 else 20000.0)), true, id + 5000 + i)
      row(i, c)
      ctx.popClip()
      if heightOf.isEmpty then
        if hs.set(i, math.max(1.0, c.usedRect.height + 2)) then dirty = true
      lastBuilt = i
      y += hs.height(i)
      i += 1
    ctx.popClip()
    state.visible = if lastBuilt >= first then first to lastBuilt else 0 until 0
    total = hs.total; maxScroll = math.max(0.0, total - viewH)
    off = off.min(maxScroll)
    state.anchorRow = if count > 0 then first else -1
    state.anchorIntra = anchorIntra.min(if count > 0 then hs.height(first) else 0.0)
    state.scroll = off; state.lastScroll = off
    state.dirtyPrev = dirty
    if dirty then ctx.requestRepaint()
    if maxScroll > 0 then
      val thumbH = math.max(24, viewH * viewH / total)
      val ty = outer.y0 + (off / maxScroll) * (viewH - thumbH)
      val tw = if barHot then 6.0 else 4.0
      ctx.canvas.fillRoundedRect(Rect(outer.x1 - 2 - tw, ty, outer.x1 - 2, ty + thumbH), tw / 2, st.text.withAlpha(if barHot then 0.5f else 0.28f))
    ctx.outline(outer, st.rounding, if r.focused then st.accent else st.border)
    ctx.access(AccessRole.ListItem, "", outer, value = state.selected.fold("")(_.toString), focused = r.focused)
    Response(outer, hovered = r.hovered, changed = changed, focused = r.focused, doubleClicked = activated)

extension (ui: Ui)

  /** A virtual list whose rows have different heights given by `rowHeight(index)`. Heights are requested lazily for the rows in view
    * (and, for [[VirtualListState.scrollTo]], the rows before the target); unrequested rows count as `estimate` tall for the scroll bar.
    * The scroll position stays anchored to the row at the top, so refining heights never makes content jump. Selection, keyboard and
    * activation behave as in [[Ui.virtualList]]; call [[VirtualListState.invalidateHeights]] if heights change.
    */
  def variableList(state: VirtualListState, count: Int, rowHeight: Int => Double, height: Double, estimate: Double = 28.0, selectable: Boolean = true)(row: (Int, Ui) => Unit): Response =
    VariableList.run(ui, state, count, estimate, height, selectable, Some(rowHeight))(row)

  /** Like [[variableList]] but rows size themselves: each row's content height is measured when it is first built and cached
    * (`estimate` is used for rows not yet seen; a row built for the first time may refine the layout one frame later). */
  def measuredList(state: VirtualListState, count: Int, height: Double, estimate: Double = 28.0, selectable: Boolean = true)(row: (Int, Ui) => Unit): Response =
    VariableList.run(ui, state, count, estimate, height, selectable, None)(row)
