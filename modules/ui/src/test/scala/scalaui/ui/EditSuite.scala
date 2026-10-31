package scalaui.ui

class EditSuite extends scalaui.testkit.LoggedSuite:
  test("insert replaces selection") {
    val e = Edit("hello world", 5, 0).insert("HEY")
    assertEquals(e, Edit("HEY world", 3, 3))
  }
  test("backspace and delete, by char and word") {
    assertEquals(Edit("abc", 2, 2).backspace(), Edit("ac", 1, 1))
    assertEquals(Edit("abc", 1, 1).delete(), Edit("ac", 1, 1))
    assertEquals(Edit("foo bar", 7, 7).backspace(word = true), Edit("foo ", 4, 4))
    assertEquals(Edit("foo bar", 0, 0).delete(word = true), Edit("bar", 0, 0))
    assertEquals(Edit("x", 0, 0).backspace(), Edit("x", 0, 0))
  }
  test("surrogate pairs move as a unit") {
    val t = "a😀b"
    assertEquals(Edit(t, 3, 3).left(false, false).cursor, 1)
    assertEquals(Edit(t, 1, 1).right(false, false).cursor, 3)
    assertEquals(Edit(t, 3, 3).backspace().text, "ab")
  }
  test("shift extends selection, plain move collapses to the edge") {
    val e = Edit("hello", 0, 0).right(true, false).right(true, false)
    assertEquals((e.selStart, e.selEnd), (0, 2))
    assertEquals(e.right(false, false).cursor, 2)
    assertEquals(e.left(false, false).cursor, 0)
  }
  test("line start/end in multiline text") {
    val e = Edit("ab\ncde\nf", 5, 5)
    assertEquals(e.lineStart(false).cursor, 3)
    assertEquals(e.lineEnd(false).cursor, 6)
  }
  test("word selection and word navigation") {
    assertEquals(Edit("foo bar", 0, 0).selectWordAt(5).selected, "bar")
    assertEquals(Edit("foo, bar", 0, 0).right(false, true).cursor, 3)
    assertEquals(Edit("foo bar", 7, 7).left(false, true).cursor, 4)
  }
  test("selectAll") { assertEquals(Edit("abc", 0, 0).selectAll.selected, "abc") }
  test("cursor moves and deletes by grapheme cluster") {
    val flag = "🇮🇹" // regional indicators IT
    val fam = "👨‍👩‍👧" // man ZWJ woman ZWJ girl
    val e = Edit(s"a${flag}é$fam", 0, 0)
    val steps = Iterator.iterate(e)(_.right(false, false)).take(5).map(_.cursor).toList
    assertEquals(steps, List(0, 1, 5, 7, e.text.length))
    assertEquals(Edit(e.text).backspace().text, s"a${flag}é")
    assertEquals(Edit(s"a${flag}", 5, 5).backspace().text, "a")
    assertEquals(Edit("xन्न", 4, 4).left(false, false).cursor, 1) // Devanagari conjunct is one cluster
  }
  test("word navigation uses Unicode word boundaries") {
    assertEquals(Edit("don't stop", 0, 0).right(false, true).cursor, 6)
    assertEquals(Edit("3.14 pi", 0, 0).right(false, true).cursor, 5)
    assertEquals(Edit("one  two", 8, 8).left(false, true).cursor, 5)
    assertEquals(Edit("ab\ncd", 2, 2).right(false, true).cursor, 3)
  }
