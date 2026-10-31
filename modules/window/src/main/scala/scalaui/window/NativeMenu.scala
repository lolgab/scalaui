package scalaui.window

/** An entry of a native menu bar. `shortcut` is written like `"mod+shift+s"`: `mod` is Cmd on macOS and Ctrl elsewhere,
  * `cmd`, `ctrl`, `alt` and `shift` are literal; the key is a single character or one of `enter`, `tab`, `esc`,
  * `delete`, `backspace`, `space`, `left`, `right`, `up`, `down`, `f1`..`f12`.
  */
enum MenuEntry:
  case Item(title: String, id: Int, shortcut: String = "", enabled: Boolean = true, checked: Boolean = false)
  case Separator
  case Submenu(title: String, entries: Seq[MenuEntry])

/** Parsed `"mod+shift+s"` shortcut. */
final case class Shortcut(shift: Boolean, control: Boolean, alt: Boolean, command: Boolean, key: String)

object Shortcut:
  def parse(s: String, isMac: Boolean): Option[Shortcut] =
    val parts = s.split('+').map(_.trim.toLowerCase).filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val mods = parts.init
      Some(Shortcut(
        shift = mods.contains("shift"),
        control = mods.contains("ctrl") || (mods.contains("mod") && !isMac),
        alt = mods.contains("alt") || mods.contains("option"),
        command = mods.contains("cmd") || (mods.contains("mod") && isMac),
        key = parts.last))
