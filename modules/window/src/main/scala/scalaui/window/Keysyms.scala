package scalaui.window

import scala.collection.mutable

/** X11 keysyms: name lookup, Unicode text and the mapping to [[Key]]. */
private[window] object Keysyms:
  private lazy val records: Vector[(String, Int, Int)] =
    KeysymTable.chunks.iterator.flatMap(_.split(';')).filter(_.nonEmpty).map { r =>
      val p = r.split(",", -1)
      (p(0), Integer.parseUnsignedInt(p(1), 16), if p(2).isEmpty then -1 else Integer.parseInt(p(2), 16))
    }.toVector

  private lazy val byName: Map[String, Int] = records.iterator.map(r => r._1 -> r._2).toMap
  private lazy val unicode: Map[Int, Int] = records.iterator.filter(_._3 >= 0).map(r => r._2 -> r._3).toMap

  /** Keysym for an XKB keysym name (`"a"`, `"eacute"`, `"Return"`, `"U20AC"`); 0 for NoSymbol or unknown names. */
  def parse(name: String): Int =
    if name == "NoSymbol" || name == "VoidSymbol" then 0
    else if name.length >= 5 && name.charAt(0) == 'U' && name.drop(1).forall(c => Character.digit(c, 16) >= 0) then 0x01000000 + Integer.parseInt(name.drop(1), 16)
    else byName.getOrElse(name, 0)

  /** The text a keysym produces, or "" for non-printing keysyms. */
  def text(ks: Int): String =
    if ks >= 0x01000100 && ks <= 0x0110ffff then new String(Character.toChars(ks - 0x01000000))
    else if ks == 0xff0d || ks == 0xff09 || ks == 0xff08 || ks == 0xffff || ks == 0xff1b then "" // keys, not text
    else unicode.get(ks).filter(cp => cp >= 0x20 && cp != 0x7f).map(cp => new String(Character.toChars(cp))).getOrElse("")

  def key(ks: Int): Key = ks match
    case 0xff51 => Key.Left; case 0xff53 => Key.Right; case 0xff52 => Key.Up; case 0xff54 => Key.Down
    case 0xff50 => Key.Home; case 0xff57 => Key.End; case 0xff55 => Key.PageUp; case 0xff56 => Key.PageDown
    case 0xff08 => Key.Backspace; case 0xffff => Key.Delete; case 0xff0d | 0xff8d => Key.Enter
    case 0xff09 | 0xfe20 => Key.Tab; case 0xff1b => Key.Escape; case 0x20 => Key.Space
    case other => Key.Other(other)

/** The subset of an XKB text keymap (as sent by Wayland compositors) needed for typing: keycode names, and the keysyms of
  * every key's first layout group per shift level. Compose sequences and dead keys are not interpreted.
  */
private[window] final class XkbKeymap(text: String):
  private val keycodes = mutable.HashMap.empty[String, Int]
  private val levels = mutable.HashMap.empty[Int, (Array[Int], String)] // keycode -> (keysyms by level, type name)

  parse()

  private def section(name: String): String =
    val i = text.indexOf(name)
    if i < 0 then ""
    else
      val open = text.indexOf('{', i)
      var depth = 0; var j = open
      while j < text.length && !(text.charAt(j) == '}' && depth == 1) do
        if text.charAt(j) == '{' then depth += 1 else if text.charAt(j) == '}' then depth -= 1
        j += 1
      text.substring(open + 1, j)

  private def parse(): Unit =
    val codeRx = """<([^>]+)>\s*=\s*(\d+)\s*;""".r
    val kc = section("xkb_keycodes")
    codeRx.findAllMatchIn(kc).foreach(m => keycodes(m.group(1)) = m.group(2).toInt)
    // aliases: alias <LatQ> = <AD01>;
    """alias\s+<([^>]+)>\s*=\s*<([^>]+)>""".r.findAllMatchIn(kc).foreach(m => keycodes.get(m.group(2)).foreach(keycodes(m.group(1)) = _))
    val symbols = section("xkb_symbols")
    // key <AD01> { type= "ALPHABETIC", symbols[Group1]= [ q, Q ], ... };  or  key <AD01> { [ q, Q ] };
    val keyRx = """key\s+<([^>]+)>\s*\{([^;]*?)\}\s*;""".r
    val typeRx = """type(?:\[[^\]]*\])?\s*=\s*"([^"]+)"""".r
    keyRx.findAllMatchIn(symbols).foreach { m =>
      keycodes.get(m.group(1)).foreach { code =>
        val body = m.group(2)
        val i = body.indexOf('[')
        if i >= 0 then
          val j = body.indexOf(']', i)
          val syms = body.substring(i + 1, j).split(',').map(_.trim).filter(_.nonEmpty).map(Keysyms.parse)
          levels(code) = (syms, typeRx.findFirstMatchIn(body).map(_.group(1)).getOrElse(if syms.length > 2 then "FOUR_LEVEL" else "TWO_LEVEL"))
      }
    }

  /** Keysym at the first level (no modifiers), used for the logical key. */
  def base(code: Int): Int = levels.get(code).flatMap(_._1.headOption).getOrElse(0)

  /** Keysym for `code` with the given XKB modifier mask: Shift 1, Lock 2, Mod2 (NumLock) 0x10, Mod5 (AltGr) 0x80. */
  def keysym(code: Int, mods: Int): Int =
    levels.get(code) match
      case None => 0
      case Some((syms, typ)) =>
        val shift = (mods & 1) != 0
        val caps = (mods & 2) != 0
        val altgr = (mods & 0x80) != 0
        val numlock = (mods & 0x10) != 0
        val alpha = typ.contains("ALPHABETIC")
        val keypad = typ.contains("KEYPAD")
        val upper = if keypad then shift != numlock else if alpha then shift != caps else shift
        var level = (if upper then 1 else 0) + (if altgr then 2 else 0)
        if level >= syms.length then level = if upper && syms.length > 1 then 1 else 0
        val ks = syms(level)
        if ks != 0 then ks else syms.headOption.getOrElse(0)
