package scalaui.svg

import scala.collection.mutable

/** CSS for SVG: `<style>` sheets with type/universal/class/id/attribute selectors, descendant, child and adjacent combinators,
  * `:first-child`, specificity ordering and `!important`; plus `style=""` declarations. Rules are folded into each element's
  * attribute map by [[Css.apply]], so the renderer only ever sees a flat presentation-attribute map.
  */
private[svg] object Css:
  final case class Decl(name: String, value: String, important: Boolean)

  private final case class Attr(name: String, op: String, value: String)
  private final case class Compound(tag: Option[String], id: Option[String], classes: List[String], attrs: List[Attr], pseudos: List[String])
  /** `parts` in source order: (combinator before the compound, compound); the first combinator is ' '. */
  private final case class Selector(parts: Vector[(Char, Compound)]):
    def specificity: Int =
      var a = 0; var b = 0; var c = 0
      parts.foreach { (_, k) =>
        if k.id.nonEmpty then a += 1
        b += k.classes.size + k.attrs.size + k.pseudos.size
        if k.tag.nonEmpty then c += 1
      }
      a * 10000 + b * 100 + c
  private final case class Rule(sel: Selector, decls: Vector[Decl], order: Int)

  private def stripComments(s: String): String =
    val sb = new StringBuilder; var i = 0
    while i < s.length do
      if s.startsWith("/*", i) then { val e = s.indexOf("*/", i + 2); i = if e < 0 then s.length else e + 2 }
      else { sb += s.charAt(i); i += 1 }
    sb.toString

  def parseDecls(style: String): Vector[Decl] =
    val out = Vector.newBuilder[Decl]
    // split on ';' outside parentheses/quotes (data URIs and url() contain ';')
    val s = stripComments(style)
    var depth = 0; var quote = 0.toChar; var start = 0
    def emit(end: Int): Unit =
      val kv = s.substring(start, end); val i = kv.indexOf(':')
      if i > 0 then
        var v = kv.substring(i + 1).trim; var imp = false
        if v.toLowerCase.endsWith("!important") then { imp = true; v = v.dropRight(10).trim }
        out += Decl(kv.substring(0, i).trim.toLowerCase, v, imp)
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if quote != 0 then { if c == quote then quote = 0 }
      else if c == '"' || c == '\'' then quote = c
      else if c == '(' then depth += 1
      else if c == ')' then depth -= 1
      else if c == ';' && depth == 0 then { emit(i); start = i + 1 }
      i += 1
    emit(s.length)
    out.result()

  private def parseCompound(t: String): Option[Compound] =
    var i = 0; var tag: Option[String] = None; var id: Option[String] = None
    val classes = List.newBuilder[String]; val attrs = List.newBuilder[Attr]; val pseudos = List.newBuilder[String]
    def ident(): String =
      val s = i
      while i < t.length && (t.charAt(i).isLetterOrDigit || t.charAt(i) == '-' || t.charAt(i) == '_') do i += 1
      t.substring(s, i)
    if i < t.length && t.charAt(i) == '*' then i += 1
    else if i < t.length && (t.charAt(i).isLetter || t.charAt(i) == '_') then tag = Some(ident())
    var ok = true
    while ok && i < t.length do
      t.charAt(i) match
        case '#' => i += 1; id = Some(ident())
        case '.' => i += 1; classes += ident()
        case '[' =>
          val e = t.indexOf(']', i)
          if e < 0 then ok = false
          else
            val body = t.substring(i + 1, e); i = e + 1
            val m = "^\\s*([^\\s~|^$*=]+)\\s*(?:([~|^$*]?=)\\s*(.*?))?\\s*$".r.findFirstMatchIn(body)
            m match
              case Some(mm) => attrs += Attr(mm.group(1), Option(mm.group(2)).getOrElse(""), Option(mm.group(3)).getOrElse("").stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'"))
              case None => ok = false
        case ':' =>
          i += 1; if i < t.length && t.charAt(i) == ':' then i += 1
          val n = ident()
          if i < t.length && t.charAt(i) == '(' then { val e = t.indexOf(')', i); i = if e < 0 then t.length else e + 1; pseudos += n + "()" }
          else pseudos += n
        case _ => ok = false
    if ok then Some(Compound(tag, id, classes.result(), attrs.result(), pseudos.result())) else None

  private def parseSelector(text: String): Option[Selector] =
    val t = text.trim
    if t.isEmpty then return None
    val parts = Vector.newBuilder[(Char, Compound)]
    var comb = ' '; var i = 0; var ok = true
    while ok && i < t.length do
      while i < t.length && t.charAt(i).isWhitespace do i += 1
      if i < t.length && (t.charAt(i) == '>' || t.charAt(i) == '+' || t.charAt(i) == '~') then
        comb = t.charAt(i); i += 1
        while i < t.length && t.charAt(i).isWhitespace do i += 1
      val s = i
      var depth = 0
      while i < t.length && (depth > 0 || !(t.charAt(i).isWhitespace || t.charAt(i) == '>' || t.charAt(i) == '+' || t.charAt(i) == '~')) do
        if t.charAt(i) == '[' || t.charAt(i) == '(' then depth += 1 else if t.charAt(i) == ']' || t.charAt(i) == ')' then depth -= 1
        i += 1
      if i > s then
        parseCompound(t.substring(s, i)) match
          case Some(c) => parts += ((comb, c)); comb = ' '
          case None => ok = false
    if ok then { val p = parts.result(); if p.isEmpty then None else Some(Selector(p)) } else None

  private def parseSheet(text: String, firstOrder: Int): Vector[Rule] =
    val s = stripComments(text)
    val out = Vector.newBuilder[Rule]; var order = firstOrder; var i = 0
    while i < s.length do
      while i < s.length && s.charAt(i).isWhitespace do i += 1
      if i < s.length then
        if s.charAt(i) == '@' then
          // skip at-rules: either `@x ...;` or a block
          var j = i; while j < s.length && s.charAt(j) != ';' && s.charAt(j) != '{' do j += 1
          if j < s.length && s.charAt(j) == '{' then
            var d = 0; while j < s.length && !(s.charAt(j) == '}' && d == 1) do { if s.charAt(j) == '{' then d += 1 else if s.charAt(j) == '}' then d -= 1; j += 1 }
          i = j + 1
        else
          val b = s.indexOf('{', i)
          if b < 0 then i = s.length
          else
            val e0 = s.indexOf('}', b)
            val e = if e0 < 0 then s.length else e0
            val decls = parseDecls(s.substring(b + 1, e))
            s.substring(i, b).split(',').foreach { sel => parseSelector(sel).foreach { se => out += Rule(se, decls, order); order += 1 } }
            i = e + 1
    out.result()

  /** An element with its position among its siblings, linked to its parent chain. */
  private final class Ctx(val e: XmlElement, val parent: Ctx | Null, val index: Int, val siblings: Vector[XmlElement])

  private def matchCompound(c: Compound, x: Ctx): Boolean =
    val e = x.e
    c.tag.forall(_ == e.name) && c.id.forall(i => e.attrs.get("id").contains(i)) &&
      c.classes.forall(k => e.attrs.get("class").exists(_.split("\\s+").contains(k))) &&
      c.attrs.forall { a =>
        e.attrs.get(a.name) match
          case None => false
          case Some(v) => a.op match
            case "" => true; case "=" => v == a.value
            case "~=" => v.split("\\s+").contains(a.value); case "|=" => v == a.value || v.startsWith(a.value + "-")
            case "^=" => v.startsWith(a.value); case "$=" => v.endsWith(a.value); case "*=" => v.contains(a.value)
            case _ => false
      } &&
      c.pseudos.forall {
        case "first-child" => x.index == 0
        case "last-child" => x.index == x.siblings.size - 1
        case _ => false // unsupported pseudo-classes never match (as in usvg)
      }

  private def matches(sel: Selector, x: Ctx): Boolean =
    def go(i: Int, at: Ctx): Boolean =
      val (comb, c) = sel.parts(i)
      if !matchCompound(c, at) then false
      else if i == 0 then true
      else comb match
        case '>' => at.parent != null && go(i - 1, at.parent.nn)
        case '+' => at.index > 0 && go(i - 1, new Ctx(at.siblings(at.index - 1), at.parent, at.index - 1, at.siblings))
        case '~' =>
          (0 until at.index).reverse.exists(k => go(i - 1, new Ctx(at.siblings(k), at.parent, k, at.siblings)))
        case _ =>
          var p = at.parent; var found = false
          while !found && p != null do { found = go(i - 1, p.nn); p = p.nn.parent }
          found
    go(sel.parts.size - 1, x)

  /** Properties that may be set from CSS (SVG 1.1 presentation attributes plus the SVG 2 / CSS ones usvg supports); geometry attributes may not. */
  private val Presentation: Set[String] = Set(
    "alignment-baseline", "baseline-shift", "clip", "clip-path", "clip-rule", "color", "color-interpolation", "color-interpolation-filters", "color-rendering",
    "cursor", "direction", "display", "dominant-baseline", "enable-background", "fill", "fill-opacity", "fill-rule", "filter", "flood-color", "flood-opacity",
    "font", "font-family", "font-size", "font-size-adjust", "font-stretch", "font-style", "font-variant", "font-weight", "font-kerning", "font-feature-settings",
    "font-variation-settings", "glyph-orientation-horizontal", "glyph-orientation-vertical", "image-rendering", "isolation", "kerning", "letter-spacing",
    "lighting-color", "marker-start", "marker-mid", "marker-end", "mask", "mask-type", "mix-blend-mode", "opacity", "overflow", "paint-order", "pointer-events",
    "shape-rendering", "stop-color", "stop-opacity", "stroke", "stroke-dasharray", "stroke-dashoffset", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit",
    "stroke-opacity", "stroke-width", "text-anchor", "text-decoration", "text-orientation", "text-overflow", "text-rendering", "transform", "transform-box", "transform-origin",
    "unicode-bidi", "vector-effect", "visibility", "white-space", "word-spacing", "writing-mode", "background-color", "mask-mode", "-inkscape-font-specification")

  private def expand(d: Decl): Seq[Decl] = d.name match
    case "marker" => Seq("marker-start", "marker-mid", "marker-end").map(n => d.copy(name = n))
    case n if Presentation.contains(n) => Seq(d)
    case _ => Seq.empty

  /** Returns a copy of the tree with stylesheet rules and `style` attributes folded into `attrs`. */
  def apply(root: XmlElement): XmlElement =
    val rules = mutable.ArrayBuffer.empty[Rule]
    def collect(e: XmlElement): Unit =
      if e.name == "style" && e.attrs.get("type").forall(t => t.isEmpty || t == "text/css") then rules ++= parseSheet(e.text, rules.size)
      e.children.foreach(collect)
    collect(root)
    val sorted = rules.toVector.sortBy(r => (r.sel.specificity, r.order))
    def go(e: XmlElement, parent: Ctx | Null, index: Int, siblings: Vector[XmlElement]): XmlElement =
      val ctx = new Ctx(e, parent, index, siblings)
      // `mix-blend-mode` and `isolation` are not presentation attributes: only CSS (stylesheet or `style`) may set them
      var attrs = e.attrs - "mix-blend-mode" - "isolation"
      val important = mutable.Set.empty[String]
      sorted.foreach { r =>
        if matches(r.sel, ctx) then r.decls.foreach(d => expand(d).foreach { x =>
          if x.important then { attrs += x.name -> x.value; important += x.name }
          else if !important.contains(x.name) then attrs += x.name -> x.value
        })
      }
      e.attrs.get("style").foreach(st => parseDecls(st).foreach(d => expand(d).foreach { x =>
        if x.important || !important.contains(x.name) then attrs += x.name -> x.value
      }))
      val kids = e.children
      var ci = 0
      val nodes = e.nodes.map {
        case k: XmlElement => val r = go(k, ctx, ci, kids); ci += 1; r
        case t => t
      }
      new XmlElement(e.name, attrs, nodes)
    go(root, null, 0, Vector(root))
