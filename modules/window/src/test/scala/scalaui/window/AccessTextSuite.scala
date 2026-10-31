package scalaui.window

class AccessTextSuite extends scalaui.testkit.LoggedSuite:
  import TextSegments.*

  test("words keep trailing spaces, lines keep the break, sentences end at punctuation") {
    val t = "Hello world. Next one!\nSecond line"
    assertEquals(at(t, 1, Word), (0, 6))
    assertEquals(at(t, 8, Word), (6, 11))
    assertEquals(at(t, 11, Word), (11, 13))
    assertEquals(at(t, 3, Line), (0, 23))
    assertEquals(at(t, 25, Line), (23, t.length))
    assertEquals(at(t, 3, Sentence), (0, 13))
    assertEquals(after(t, 3, Sentence), (13, 22))
    assertEquals(before(t, 14, Sentence), (0, 13))
    assertEquals(at(t, t.length, Char), (t.length, t.length))
  }

  test("characters are code points, not UTF-16 units") {
    val t = "a😀b"
    assertEquals(split(t, Char), Vector((0, 1), (1, 3), (3, 4)))
    assertEquals(AtSpi.cpLen(t), 3)
    assertEquals(AtSpi.cpOffset(t, 3), 2)
  }
