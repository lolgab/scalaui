package java.net

// Scala Native's javalib has no java.net.URL, but scala-xml's ConstructingParser links against it (only for external
// DTD resolution, which is never used here). This stub only satisfies the linker.
class URL(spec: String):
  def openStream(): java.io.InputStream = throw new java.io.IOException("external entities are not supported")
  override def toString: String = spec
