package scalaui.docx

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import scala.collection.mutable
import scala.io.Source
import scala.xml.Elem
import scala.xml.parsing.ConstructingParser

/** An OPC package (the zip container behind .docx): part name -> bytes, in archive order. */
final class Package(val parts: mutable.LinkedHashMap[String, Array[Byte]]):
  /** Parsed with scala-xml's pure-Scala `ConstructingParser`; the default SAX loader does not exist on Scala Native. */
  def xml(name: String): Option[Elem] =
    parts.get(name).map(b => ConstructingParser.fromSource(Source.fromBytes(b, "UTF-8"), preserveWS = true).document().docElem.asInstanceOf[Elem])

  def toBytes: Array[Byte] =
    val out = new ByteArrayOutputStream
    val zip = new ZipOutputStream(out)
    for (name, bytes) <- parts do
      zip.putNextEntry(new ZipEntry(name))
      zip.write(bytes)
      zip.closeEntry()
    zip.close()
    out.toByteArray

object Package:
  def read(bytes: Array[Byte]): Package =
    val zip = new ZipInputStream(new ByteArrayInputStream(bytes))
    val parts = mutable.LinkedHashMap.empty[String, Array[Byte]]
    var e = zip.getNextEntry
    while e != null do
      if !e.isDirectory then parts(e.getName) = zip.readAllBytes()
      e = zip.getNextEntry
    new Package(parts)
