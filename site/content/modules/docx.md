---
title: docx
section: Modules
order: 20
description: Read and write the zip and XML Open Packaging Convention container behind .docx files.
---
# docx

`scalaui.docx` reads and writes the **package container** behind `.docx` files: a zip archive of XML and binary parts (the role of quick-xml, zip and cfb for this purpose). It is deliberately small: there is no document model, no styles and no layout. Depends on [geom](geom.md) and [deflate](deflate.md), plus `scala-xml` 2.4.0 and `jsoniter-scala-core`.

```scala
import scalaui.docx.Package
```

## API

```scala
final class Package(val parts: mutable.LinkedHashMap[String, Array[Byte]]):
  def xml(name: String): Option[scala.xml.Elem]   // parse a part as XML
  def toBytes: Array[Byte]                         // write the archive

object Package:
  def read(bytes: Array[Byte]): Package            // read a .docx / .xlsx / .pptx style archive
```

`parts` maps part names (such as `word/document.xml`) to their bytes, in archive order, and is mutable so you can replace or add parts before calling `toBytes`.

## Notes

- XML is parsed with scala-xml's pure-Scala `ConstructingParser`. The default SAX loader (`XML.load`) does not link on Scala Native. External entities are refused: the module ships a stub `java.net.URL` whose `openStream` throws.
- Zip containers are read and written by `Package` itself on top of [deflate](deflate.md): methods stored and deflate, UTF-8 names, CRC-32 checked on read, no Zip64, no encryption. No zlib is linked.

## Example

```scala
import scalaui.docx.Package
import java.nio.file.{Files, Paths}

val pkg = Package.read(Files.readAllBytes(Paths.get("letter.docx")))
println(pkg.parts.keys.mkString("\n"))

val doc = pkg.xml("word/document.xml").get
val text = (doc \\ "t").map(_.text).mkString

pkg.parts("docProps/app.xml") = "<Properties/>".getBytes("UTF-8")
Files.write(Paths.get("out.docx"), pkg.toBytes)
```
