package scalaui.svg

import scala.io.Source
import scala.xml.{Elem, Node, Text, PCData}
import scala.xml.parsing.ConstructingParser

/** A node in element content: either character data or a child element. */
sealed trait XmlNode
final case class XmlText(text: String) extends XmlNode

/** A parsed XML element. `attrs` keys keep their prefix (`xlink:href`, `xml:space`); element names have the SVG prefix removed.
  * `nodes` interleaves text and elements (needed by `<text>`); `children` are only the elements.
  */
final class XmlElement(val name: String, val attrs: Map[String, String], val nodes: Vector[XmlNode | XmlElement]):
  lazy val children: Vector[XmlElement] = nodes.collect { case e: XmlElement => e }
  lazy val text: String = nodes.collect { case XmlText(t) => t }.mkString
  def withAttrs(a: Map[String, String]): XmlElement = new XmlElement(name, a, nodes)

private[svg] object Xml:
  private val SvgNs = "http://www.w3.org/2000/svg"
  /** Parsed with scala-xml's pure-Scala `ConstructingParser` (the SAX loader does not exist on Scala Native). */
  /** Expands the internal-subset `<!ENTITY name "value">` declarations (scala-xml's parser leaves them unresolved in attribute values). */
  private def expandEntities(src: String): String =
    val decl = "<!ENTITY\\s+([A-Za-z_][\\w.-]*)\\s+(?:\"([^\"]*)\"|'([^']*)')\\s*>".r
    val entities = decl.findAllMatchIn(src).map(m => m.group(1) -> Option(m.group(2)).getOrElse(m.group(3))).toMap
    if entities.isEmpty then src
    else
      val start = src.indexOf("<svg")
      if start < 0 then src
      else
        val body = entities.foldLeft(src.substring(start))((acc, kv) => acc.replace("&" + kv._1 + ";", kv._2))
        src.substring(0, start) + body

  def parse(src0: String): XmlElement =
    val src = expandEntities(src0)
    val doc = ConstructingParser.fromSource(Source.fromString(src), preserveWS = true).document()
    convert(doc.docElem.asInstanceOf[Elem])

  private def convert(e: Elem): XmlElement =
    val attrs = e.attributes.asAttrMap
    val kids = Vector.newBuilder[XmlNode | XmlElement]
    e.child.foreach {
      case c: Elem => kids += convert(c)
      case t: PCData => kids += XmlText(t.data)
      case t: Text => kids += XmlText(t.data)
      case n: Node if n.isAtom => kids += XmlText(n.text)
      case _ => ()
    }
    // elements outside the SVG namespace (inkscape/sodipodi metadata, foreign content) keep their prefix so they are never rendered
    val ns = e.getNamespace(e.prefix)
    val name = if ns == null || ns == SvgNs then e.label else "ns:" + e.label
    new XmlElement(name, attrs, kids.result())
