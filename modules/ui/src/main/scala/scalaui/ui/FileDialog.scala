package scalaui.ui

import scalaui.geom.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

enum FileDialogMode:
  case OpenFile, SaveFile, OpenFolder

/** Entry in a directory listing. */
final case class FileEntry(name: String, isDir: Boolean, size: Long)

/** In-app file picker for platforms without a native dialog (Linux). Keep one instance per dialog and call
  * [[update]] every frame; it returns the chosen path once the user confirms.
  *
  * {{{
  * val dlg = new FileDialog("Open image", "/home/me", FileDialogMode.OpenFile, Seq("png", "jpg"))
  * if button.clicked then dlg.show()
  * dlg.update(ui).foreach(path => load(path))
  * }}}
  */
final class FileDialog(val title: String, startDir: String, val mode: FileDialogMode, val extensions: Seq[String] = Nil):
  private val isOpen = Var(false)
  private val dir = Var(normalize(startDir))
  private val fileName = Var("")
  private var entries = Vector.empty[FileEntry]
  private var listed = ""
  private var error = ""
  /** Screen rects of the listed rows from the last frame (used by tests). */
  private[ui] var rowRects = Vector.empty[(String, Rect)]

  def show(): Unit = { isOpen.value = true; listed = ""; error = "" }
  def isShown: Boolean = isOpen.value
  def currentDir: String = dir.value

  private def normalize(p: String): String =
    try Paths.get(if p.isEmpty then "/" else p).toAbsolutePath.normalize.toString catch case _: Exception => "/"

  private def refresh(): Unit =
    listed = dir.value
    entries = FileDialog.list(dir.value, extensions, mode == FileDialogMode.OpenFolder)
    error = if entries.isEmpty && !Files.isDirectory(Paths.get(dir.value)) then s"Not a directory: ${dir.value}" else ""

  // window bodies run in the deferred overlay pass, i.e. after `update` returned: results surface on the next call
  private var pending: Option[String] = None

  def update(ui: Ui): Option[String] =
    val ready = pending; pending = None
    if isOpen.value then
      if listed != dir.value then refresh()
      ui.window(title, Point(120, 80), 520, Some(isOpen)) { w =>
        val ctx = w.ctx
        // path bar: parent button + editable path
        w.horizontal { h =>
          if h.button("↑ Up").clicked then
            Option(Paths.get(dir.value).getParent).foreach(p => dir.value = p.toString)
          val pathVar = Var(dir.value)
          if h.textEdit(pathVar, width = Some(400)).changed && Files.isDirectory(Paths.get(pathVar.value)) then dir.value = normalize(pathVar.value)
        }
        if error.nonEmpty then w.label(error, Some(w.style.danger))
        rowRects = Vector.empty
        w.scrollArea(220, Some(496)) { s =>
          entries.foreach { e =>
            val label = (if e.isDir then "▸ " else "   ") + e.name
            val l = ctx.layout(label, w.style.fontSize)
            val rect = s.allocate(math.max(s.availableWidth, 100), math.max(l.height, 20))
            rowRects :+= (e.name -> rect)
            val r = ctx.interact(rect, s.nextId())
            val selected = fileName.value == e.name && !e.isDir
            if r.hovered || selected then ctx.canvas.fillRect(rect, if selected then w.style.checked else w.style.widgetHover)
            ctx.paintText(l, Point(rect.x0 + 4, rect.y0 + (rect.height - l.height) / 2), if e.isDir then w.style.accentBright else w.style.text)
            if r.clicked then
              if e.isDir then
                if r.doubleClicked then dir.value = normalize(Paths.get(dir.value, e.name).toString)
                else if mode == FileDialogMode.OpenFolder then fileName.value = e.name
              else
                fileName.value = e.name
                if r.doubleClicked && mode == FileDialogMode.OpenFile then { pending = Some(Paths.get(dir.value, e.name).toString); isOpen.value = false }
          }
        }
        if mode != FileDialogMode.OpenFolder then w.horizontal { h => h.label("Name:"); h.textEdit(fileName, width = Some(440)) }
        w.horizontal { h =>
          val confirmLabel = mode match { case FileDialogMode.OpenFile => "Open"; case FileDialogMode.SaveFile => "Save"; case FileDialogMode.OpenFolder => "Choose this folder" }
          if h.button(confirmLabel).clicked then
            mode match
              case FileDialogMode.OpenFolder => pending = Some(if fileName.value.nonEmpty then Paths.get(dir.value, fileName.value).toString else dir.value)
              case _ if fileName.value.nonEmpty => pending = Some(Paths.get(dir.value, fileName.value).toString)
              case _ => ()
            if pending.isDefined then isOpen.value = false
          if h.button("Cancel").clicked then isOpen.value = false
        }
      }
    ready

object FileDialog:
  /** Directory listing: folders first, then files (filtered by extension), each sorted case-insensitively; hidden entries skipped. */
  def list(dir: String, extensions: Seq[String], foldersOnly: Boolean = false): Vector[FileEntry] =
    try
      val stream = Files.newDirectoryStream(Paths.get(dir))
      try
        val all = stream.iterator.asScala.toVector.flatMap { p =>
          val name = p.getFileName.toString
          if name.startsWith(".") then None
          else
            val isDir = Files.isDirectory(p)
            if isDir then Some(FileEntry(name, true, 0L))
            else if foldersOnly then None
            else if extensions.isEmpty || extensions.exists(e => name.toLowerCase.endsWith("." + e.toLowerCase)) then
              Some(FileEntry(name, false, try Files.size(p) catch case _: Exception => 0L))
            else None
        }
        all.sortBy(e => (!e.isDir, e.name.toLowerCase))
      finally stream.close()
    catch case _: Exception => Vector.empty
