package scalaui.ui

import scalaui.geom.*
import scalaui.text.Span
import scalaui.window.*

class GapsSnapshotSuite extends scalaui.testkit.LoggedSuite:
  final case class N(name: String, kids: Seq[N] = Nil)
  private val data = Seq(
    N("src", Seq(N("alpha.scala"), N("beta.scala"), N("util", Seq(N("strings.scala"), N("numbers.scala"))))),
    N("docs", Seq(N("guide.md"))),
    N("README"))

  test("tree with multi selection and checkboxes") {
    val h = new SnapshotHarness(300, 230)
    val st = new TreeState
    st.expand("src"); st.expand("util")
    st.selected += "beta.scala"; st.selected += "strings.scala"; st.cursor = Some("strings.scala")
    st.checked += "alpha.scala"; st.checked += "docs"
    def f(ui: Ui): Unit =
      ui.label("Plain")
      ui.tree[N](st, data, _.kids, _.name, 100, key = _.name, multiSelect = true)
      ui.tree[N](st, data, _.kids, _.name, 80, key = _.name, checkboxes = true)
    h.render(f)
    Snapshot.check("tree", h.render(f))
  }

  test("rich text links wrap, underline and highlight on hover") {
    val h = new SnapshotHarness(300, 150)
    var origin = Point.Zero
    def f(ui: Ui): Unit =
      ui.richLinks(Seq(ui.span("See the "), ui.link("getting started guide with a long title", "guide"), ui.span(" or the "),
        ui.link("API reference", "api"), ui.span(" for everything else; both open in your browser.")))()
    h.render(f)
    val api = {
      // hover the second link: find it through the accessibility tree
      h.ctx.accessNodes.filter(_.role == AccessRole.Link).last.bounds.center
    }
    h.move(api)
    Snapshot.check("links", h.render(f))
  }

  test("variable height lists") {
    val h = new SnapshotHarness(360, 330)
    val a = new VirtualListState; a.selected = Some(1)
    val b = new VirtualListState; b.selected = Some(2)
    def hOf(i: Int): Double = if i % 3 == 0 then 52.0 else 24.0
    def f(ui: Ui): Unit =
      ui.variableList(a, 1000, hOf, 140)((i, u) => { u.label(s"Row $i"); if i % 3 == 0 then u.weak("with a second line") })
      ui.space(6)
      ui.measuredList(b, 1000, 130)((i, u) => { u.label(s"Measured $i"); (0 until i % 3).foreach(k => u.weak(s"detail $k")) })
    for _ <- 0 until 4 do h.render(f)
    Snapshot.check("variable-lists", h.render(f))
  }
