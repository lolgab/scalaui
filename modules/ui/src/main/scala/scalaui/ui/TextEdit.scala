package scalaui.ui

import scalaui.unicode.{Graphemes, Words}

/** Pure text-editing model: `cursor` is where typing happens, `anchor` the other end of the selection (equal = no selection).
  * Indices are UTF-16 offsets into `text`.
  */
final case class Edit(text: String, cursor: Int, anchor: Int):
  def hasSelection: Boolean = cursor != anchor
  def selStart: Int = math.min(cursor, anchor)
  def selEnd: Int = math.max(cursor, anchor)
  def selected: String = text.substring(selStart, selEnd)

  def clamp: Edit = copy(cursor = cursor.max(0).min(text.length), anchor = anchor.max(0).min(text.length))

  /** Replaces the selection (or inserts at the cursor) with `s`. */
  def insert(s: String): Edit =
    val t = text.substring(0, selStart) + s + text.substring(selEnd)
    val c = selStart + s.length
    Edit(t, c, c)

  def deleteSelection: Edit = insert("")

  def backspace(word: Boolean = false): Edit =
    if hasSelection then deleteSelection
    else if cursor == 0 then this
    else
      val from = if word then Edit.wordLeft(text, cursor) else Edit.prev(text, cursor)
      Edit(text.substring(0, from) + text.substring(cursor), from, from)

  def delete(word: Boolean = false): Edit =
    if hasSelection then deleteSelection
    else if cursor >= text.length then this
    else
      val to = if word then Edit.wordRight(text, cursor) else Edit.next(text, cursor)
      Edit(text.substring(0, cursor) + text.substring(to), cursor, cursor)

  /** Moves the cursor to `to`; `extend` keeps the anchor (shift-select). */
  def moveTo(to: Int, extend: Boolean): Edit =
    val c = to.max(0).min(text.length)
    if extend then copy(cursor = c) else Edit(text, c, c)

  def left(extend: Boolean, word: Boolean): Edit =
    if hasSelection && !extend then moveTo(selStart, false)
    else moveTo(if word then Edit.wordLeft(text, cursor) else Edit.prev(text, cursor), extend)
  def right(extend: Boolean, word: Boolean): Edit =
    if hasSelection && !extend then moveTo(selEnd, false)
    else moveTo(if word then Edit.wordRight(text, cursor) else Edit.next(text, cursor), extend)
  def selectAll: Edit = Edit(text, text.length, 0)
  def lineStart(extend: Boolean): Edit = moveTo(text.lastIndexOf('\n', cursor - 1) + 1, extend)
  def lineEnd(extend: Boolean): Edit =
    val i = text.indexOf('\n', cursor)
    moveTo(if i < 0 then text.length else i, extend)
  /** Start offsets of the logical lines touched by the selection (a selection ending at a line start does not touch that line). */
  private def touchedLineStarts: Vector[Int] =
    val first = text.lastIndexOf('\n', selStart - 1) + 1
    val starts = Vector.newBuilder[Int]; starts += first
    var i = text.indexOf('\n', first)
    while i >= 0 && i + 1 <= selEnd && !(i + 1 == selEnd && selEnd > selStart) do { starts += i + 1; i = text.indexOf('\n', i + 1) }
    starts.result()

  /** Enter with auto-indent: a newline followed by the leading whitespace of the current line. */
  def newlineIndent: Edit =
    val ls = text.lastIndexOf('\n', selStart - 1) + 1
    insert("\n" + text.substring(ls, selStart).takeWhile(c => c == ' ' || c == '\t'))

  /** Tab: replaces the selection by `tab`, or when the selection spans several lines prefixes each of its lines with `tab`. */
  def indent(tab: String): Edit =
    val starts = touchedLineStarts
    if starts.length < 2 then insert(tab)
    else
      val sb = new StringBuilder; var prev = 0
      starts.foreach { s => sb ++= text.substring(prev, s); sb ++= tab; prev = s }
      sb ++= text.substring(prev)
      def map(p: Int) = p + tab.length * starts.count(_ <= p)
      Edit(sb.toString, map(cursor), map(anchor))

  /** Shift-Tab: removes up to `tab.length` leading blanks from the current line or each selected line. */
  def dedent(tab: String): Edit =
    val starts = touchedLineStarts
    val removed = starts.map(s => text.drop(s).take(tab.length).takeWhile(c => c == ' ' || c == '\t').length)
    val sb = new StringBuilder; var prev = 0
    starts.zip(removed).foreach { (s, n) => sb ++= text.substring(prev, s); prev = s + n }
    sb ++= text.substring(prev)
    def map(p: Int) = p - starts.zip(removed).map((s, n) => math.min(n, math.max(0, p - s))).sum
    Edit(sb.toString, map(cursor), map(anchor))

  def selectWordAt(i: Int): Edit =
    val c = i.max(0).min(text.length)
    if text.isEmpty then Edit(text, 0, 0)
    else
      val (lo, bounds) = Edit.boundaries(text, c, word = true)
      val at = if c == text.length then c - 1 else c
      val s = bounds.filter(_ + lo <= at).lastOption.getOrElse(0) + lo
      val e = bounds.find(_ + lo > at).map(_ + lo).getOrElse(text.length)
      Edit(text, e, s)

object Edit:
  def apply(text: String): Edit = Edit(text, text.length, text.length)

  /** Boundaries (grapheme clusters, or words) of the line around `i`, as offsets relative to the returned line start. */
  private[ui] def boundaries(t: String, i: Int, word: Boolean): (Int, Array[Int]) =
    val lo = t.lastIndexOf('\n', i - 1) + 1
    val hiNl = t.indexOf('\n', i)
    val hi = if hiNl < 0 then t.length else hiNl
    val seg = t.substring(lo, hi)
    (lo, if word then Words.offsets(seg) else Graphemes.offsets(seg))

  /** Start of the previous / end of the next grapheme cluster (newlines are their own step). */
  def prev(t: String, i: Int): Int =
    if i <= 0 then 0
    else if t.charAt(i - 1) == '\n' then i - 1
    else
      val (lo, b) = boundaries(t, i, word = false)
      b.filter(_ + lo < i).lastOption.map(_ + lo).getOrElse(lo)
  def next(t: String, i: Int): Int =
    if i >= t.length then t.length
    else if t.charAt(i) == '\n' then i + 1
    else
      val (lo, b) = boundaries(t, i, word = false)
      b.find(_ + lo > i).map(_ + lo).getOrElse(t.length)

  private def isSpace(t: String, from: Int, to: Int) = from < to && t.substring(from, to).forall(c => c.isWhitespace && c != '\n')
  private def nextWord(t: String, i: Int): Int =
    if i >= t.length then t.length
    else if t.charAt(i) == '\n' then i + 1
    else
      val (lo, b) = boundaries(t, i, word = true)
      b.find(_ + lo > i).map(_ + lo).getOrElse(t.length)
  private def prevWord(t: String, i: Int): Int =
    if i <= 0 then 0
    else if t.charAt(i - 1) == '\n' then i - 1
    else
      val (lo, b) = boundaries(t, i, word = true)
      b.filter(_ + lo < i).lastOption.map(_ + lo).getOrElse(lo)

  /** Previous word start: skips whitespace, then one word/punctuation segment (UAX #29 word boundaries). */
  def wordLeft(t: String, i: Int): Int =
    var p = i
    while p > 0 && isSpace(t, prevWord(t, p), p) do p = prevWord(t, p)
    if p > 0 then prevWord(t, p) else p
  /** End of the current word followed by the whitespace after it. */
  def wordRight(t: String, i: Int): Int =
    var p = nextWord(t, i)
    while p < t.length && isSpace(t, p, nextWord(t, p)) do p = nextWord(t, p)
    p
