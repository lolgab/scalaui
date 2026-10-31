package scalaui.window

import scala.scalanative.unsafe.*

@extern private object Libc:
  def system(command: CString): CInt = extern
  def popen(command: CString, mode: CString): Ptr[Byte] = extern
  def pclose(stream: Ptr[Byte]): CInt = extern
  def fgets(buf: CString, n: CInt, stream: Ptr[Byte]): CString = extern

/** Small OS services that need no window. */
object Platform:
  /** Opens `url` (or a file path) with the default application. Returns whether the launcher started. */
  def openUrl(url: String): Boolean =
    import scala.scalanative.meta.LinktimeInfo
    // the argument is single-quoted for POSIX shells and double-quoted for cmd, with embedded quotes neutralised
    val cmd =
      if LinktimeInfo.isWindows then "cmd /c start \"\" \"" + url.replace("\"", "") + "\""
      else
        val quoted = "'" + url.replace("'", "'\\''") + "'"
        (if LinktimeInfo.isMac then "open " else "xdg-open ") + quoted + " >/dev/null 2>&1 &"
    Zone { Libc.system(toCString(cmd)) == 0 }

  private def posixQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /** The shell command that sends `file` (PDF, PostScript, text, images) to a printer: CUPS `lp` on macOS and Linux
    * (`printer` defaults to the system default), the shell "print" verb through PowerShell on Windows. */
  private[window] def printCommand(file: String, printer: Option[String], copies: Int, windows: Boolean): String =
    if windows then
      val f = file.replace("'", "''")
      val verb = printer.fold("-Verb Print")(p => s"-Verb PrintTo -ArgumentList '${p.replace("'", "''")}'")
      s"""powershell -NoProfile -Command "Start-Process -FilePath '$f' $verb" """.trim
    else
      val dest = printer.fold("")(p => " -d " + posixQuote(p))
      s"lp$dest -n ${copies.max(1)} ${posixQuote(file)} >/dev/null 2>&1"

  /** Prints a document file. Returns whether the print command was accepted (not whether paper came out). */
  def print(file: String, printer: Option[String] = None, copies: Int = 1): Boolean =
    import scala.scalanative.meta.LinktimeInfo
    Zone { Libc.system(toCString(printCommand(file, printer, copies, LinktimeInfo.isWindows))) == 0 }

  /** Names of the installed printers (CUPS on macOS and Linux; empty on Windows and when none are configured). */
  def printers(): Seq[String] =
    import scala.scalanative.meta.LinktimeInfo
    if LinktimeInfo.isWindows then return Nil
    Zone {
      val pipe = Libc.popen(toCString("lpstat -e 2>/dev/null"), toCString("r"))
      if pipe == null then Nil
      else
        val buf = alloc[Byte](512)
        val out = scala.collection.mutable.ArrayBuffer.empty[String]
        while Libc.fgets(buf, 512, pipe) != null do out += fromCString(buf).trim
        Libc.pclose(pipe)
        out.filter(_.nonEmpty).toSeq
    }

