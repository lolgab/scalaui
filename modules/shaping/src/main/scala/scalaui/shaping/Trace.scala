package scalaui.shaping

import java.nio.file.{Files, Paths, StandardOpenOption}

/** Debug aid: while the file /tmp/shape-trace-on exists, shaping steps append their state to /tmp/shape-trace.txt. */
private[shaping] object Trace:
  private val path: Option[String] = if Files.exists(Paths.get("/tmp/shape-trace-on")) then Some("/tmp/shape-trace.txt") else None
  def enabled: Boolean = path.isDefined
  def log(s: => String): Unit =
    path.foreach(p => Files.writeString(Paths.get(p), s + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND))
