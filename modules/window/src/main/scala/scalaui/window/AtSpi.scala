package scalaui.window

import DBus.*

/** Linux accessibility bridge: speaks the AT-SPI2 protocol over D-Bus (pure Scala, no libatspi/libdbus) so screen readers
  * such as Orca can read the widgets published with [[Window.setAccessibility]]. A background thread owns the bus
  * connection and answers queries from an immutable snapshot of the tree; actions (`DoAction`) are handed to `onPress`.
  *
  * Object paths: the application is `/org/a11y/atspi/accessible/root`, its single window `.../win`, and the
  * i-th published node `.../n<i>`.
  */
private[window] final class AtSpi(appName: String, windowTitle: () => String, scale: () => Double, origin: () => (Int, Int), onEvent: Event => Unit):
  import AtSpi.*

  @volatile private var nodes = Vector.empty[AccessNode]
  @volatile private var conn: Connection = null
  @volatile private var started = false
  @volatile private var lastFocused = -1

  /** Replaces the published tree and tells assistive technologies what changed. */
  private def dbg(m: String): Unit = if sys.env.contains("SCALAUI_DEBUG") then System.err.println("atspi: " + m)

  def update(next: Vector[AccessNode]): Unit =
    dbg(s"update ${next.length} nodes, started=$started conn=${conn != null}")
    val before = nodes
    nodes = next
    if !started then { started = true; val t = new Thread(() => run()); t.setDaemon(true); t.start(); dbg("thread started") }
    val c = conn
    if c != null then
      try
        if next.length != before.length then
          // simplified: report the tail as added/removed so cached child lists get refreshed
          val (kind, idx) = if next.length > before.length then ("add", next.length - 1) else ("remove", before.length - 1)
          val w = new Writer
          w.str(kind); w.i32(idx); w.i32(0); w.sig("(so)"); w.ref(c.uniqueName, WinPath); w.array(8)(())
          emit(c, WinPath, "org.a11y.atspi.Event.Object", "ChildrenChanged", w)
        val focus = next.indexWhere(_.focused)
        if focus != lastFocused then
          lastFocused = focus
          if focus >= 0 then
            val w = new Writer
            w.str("focused"); w.i32(1); w.i32(0); w.variantInt(0); w.array(8)(())
            emit(c, nodePath(focus), "org.a11y.atspi.Event.Object", "StateChanged", w)
        textSignals(c, before, next)
      catch case _: RuntimeException => ()

  /** text-changed (insert/delete), text-caret-moved and text-selection-changed for fields whose content moved between snapshots. */
  private def textSignals(c: Connection, before: Vector[AccessNode], next: Vector[AccessNode]): Unit =
    val n = math.min(before.length, next.length)
    var i = 0
    while i < n do
      val a = before(i); val b = next(i)
      if a.role == AccessRole.TextInput && b.role == AccessRole.TextInput && a.label == b.label then
        val path = nodePath(i)
        if a.value != b.value then
          var p = 0
          while p < a.value.length && p < b.value.length && a.value.charAt(p) == b.value.charAt(p) do p += 1
          var q = 0
          while q < a.value.length - p && q < b.value.length - p && a.value.charAt(a.value.length - 1 - q) == b.value.charAt(b.value.length - 1 - q) do q += 1
          if p > 0 && Character.isHighSurrogate(a.value.charAt(p - 1)) then p -= 1 // never split a surrogate pair
          val deleted = a.value.substring(p, a.value.length - q); val inserted = b.value.substring(p, b.value.length - q)
          val off = cpOffset(b.value, p)
          def send(kind: String, text: String): Unit =
            val w = new Writer
            w.str(kind); w.i32(off); w.i32(text.codePointCount(0, text.length)); w.variantStr(text); w.array(8)(())
            emit(c, path, "org.a11y.atspi.Event.Object", "TextChanged", w)
          if deleted.nonEmpty then send("delete", deleted)
          if inserted.nonEmpty then send("insert", inserted)
        val ca = a.text.map(_.caret); val cb = b.text.map(_.caret)
        if cb.isDefined && (ca != cb || a.value != b.value) then
          val w = new Writer
          w.str(""); w.i32(cpOffset(b.value, cb.get)); w.i32(0); w.variantInt(0); w.array(8)(())
          emit(c, path, "org.a11y.atspi.Event.Object", "TextCaretMoved", w)
        val sa = a.text.map(t => (t.selStart, t.selEnd)); val sb = b.text.map(t => (t.selStart, t.selEnd))
        if sb.isDefined && sa != sb then
          val w = new Writer
          w.str(""); w.i32(0); w.i32(0); w.variantInt(0); w.array(8)(())
          emit(c, path, "org.a11y.atspi.Event.Object", "TextSelectionChanged", w)
      i += 1

  private def emit(c: Connection, path: String, iface: String, member: String, body: Writer): Unit =
    c.send(signal(c.nextSerial(), path, iface, member, "siiva{sv}", body.buf.toArray))

  private def run(): Unit =
    try
      val addr = sys.env.getOrElse("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/" + sys.env.getOrElse("UID", "1000") + "/bus")
      val session = Connection.open(addr)
      val bus = session.call("org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus", "GetAddress").body.head.asInstanceOf[String]
      session.close()
      val c = Connection.open(bus)
      val w = new Writer
      w.ref(c.uniqueName, RootPath)
      c.call("org.a11y.atspi.Registry", RootPath, "org.a11y.atspi.Socket", "Embed", "(so)", w.buf.toArray)
      conn = c
      lastFocused = -1
      update(nodes) // announce the current tree to clients that connected first
      while true do
        val m = c.readMessage()
        if m.kind == 1 then handle(c, m)
    catch case e: RuntimeException =>
      if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"AT-SPI unavailable: ${e.getMessage}")

  // ---------------------------------------------------------------- object model
  private sealed trait Obj
  private case object Root extends Obj
  private case object Win extends Obj
  private final case class Item(i: Int) extends Obj

  private def resolve(path: String): Option[Obj] =
    if path == RootPath then Some(Root)
    else if path == WinPath then Some(Win)
    else if path.startsWith(ItemPrefix) then path.drop(ItemPrefix.length).toIntOption.filter(i => i >= 0 && i < nodes.length).map(Item(_))
    else None

  private def pathOf(o: Obj): String = o match
    case Root => RootPath
    case Win => WinPath
    case Item(i) => nodePath(i)

  private def role(o: Obj): Int = o match
    case Root => 75 // APPLICATION
    case Win => 23  // FRAME
    case Item(i) => if nodes(i).role == AccessRole.TextInput && nodes(i).text.exists(_.multiline) then 61 else roleOf(nodes(i).role)

  private def name(o: Obj): String = o match
    case Root => appName
    case Win => windowTitle()
    case Item(i) => nodes(i).label

  private def interfaces(o: Obj): Vector[String] = o match
    case Root => Vector("org.a11y.atspi.Accessible", "org.a11y.atspi.Application")
    case Win => Vector("org.a11y.atspi.Accessible", "org.a11y.atspi.Component")
    case Item(i) =>
      val n = nodes(i)
      Vector("org.a11y.atspi.Accessible", "org.a11y.atspi.Component") ++
        (if actionable(n.role) then Vector("org.a11y.atspi.Action") else Vector.empty) ++
        (if n.role == AccessRole.Slider || n.role == AccessRole.ProgressBar then Vector("org.a11y.atspi.Value") else Vector.empty) ++
        (if n.role == AccessRole.TextInput || n.text.isDefined then Vector("org.a11y.atspi.Text") else Vector.empty) ++
        (if n.text.exists(_.editable) then Vector("org.a11y.atspi.EditableText") else Vector.empty)

  private def childCount(o: Obj): Int = o match
    case Root => 1
    case Win => nodes.length
    case Item(_) => 0

  private def child(o: Obj, index: Int): Option[Obj] = o match
    case Root => if index == 0 then Some(Win) else None
    case Win => if index >= 0 && index < nodes.length then Some(Item(index)) else None
    case Item(_) => None

  private def parent(o: Obj): Option[Obj] = o match
    case Root => None
    case Win => Some(Root)
    case Item(_) => Some(Win)

  private def indexInParent(o: Obj): Int = o match
    case Root => 0
    case Win => 0
    case Item(i) => i

  private def states(o: Obj): Long =
    def bit(n: Int) = 1L << n
    val common = bit(State.Enabled) | bit(State.Sensitive) | bit(State.Showing) | bit(State.Visible)
    o match
      case Root => 0L
      case Win => common | bit(State.Active)
      case Item(i) =>
        val n = nodes(i)
        val base = if n.disabled then bit(State.Showing) | bit(State.Visible) else common | bit(State.Focusable)
        val textBits = n.text.fold(0L) { t =>
          (if t.editable then bit(State.Editable) else 0L) | bit(if t.multiline then State.MultiLine else State.SingleLine)
        }
        base | textBits | (if n.focused then bit(State.Focused) else 0L) | (if n.checked.contains(true) then bit(State.Checked) else 0L)

  /** Bounds in the requested coordinate space (0 screen, 1 window, 2 parent), in device pixels. */
  private def extents(o: Obj, coordType: Long): (Int, Int, Int, Int) =
    val s = scale()
    val (ox, oy) = if coordType == 0L then origin() else (0, 0)
    o match
      case Item(i) =>
        val b = nodes(i).bounds
        (ox + (b.x0 * s).toInt, oy + (b.y0 * s).toInt, (b.width * s).toInt, (b.height * s).toInt)
      case _ =>
        val w = nodes.map(_.bounds.x1).maxOption.getOrElse(0.0); val h = nodes.map(_.bounds.y1).maxOption.getOrElse(0.0)
        (ox, oy, (w * s).toInt, (h * s).toInt)

  private def valueOf(i: Int): Double = nodes(i).value.stripSuffix("%").toDoubleOption.getOrElse(0.0)

  // ---------------------------------------------------------------- dispatch
  private def reply(c: Connection, m: Message, sig: String)(f: Writer => Unit): Unit =
    val w = new Writer; f(w)
    if !m.noReply then c.send(methodReturn(c.nextSerial(), m.sender, m.serial, sig, w.buf.toArray))

  private def fail(c: Connection, m: Message, name: String, text: String): Unit =
    if !m.noReply then c.send(error(c.nextSerial(), m.sender, m.serial, name, text))

  private def intArg(m: Message, i: Int): Int = m.body.lift(i) match
    case Some(v: Int) => v
    case Some(v: Long) => v.toInt
    case _ => 0

  private def handle(c: Connection, m: Message): Unit =
    val obj = resolve(m.path)
    val me = c.uniqueName
    // the registry asks for the app's accessible cache; we are uncached, so clients query objects on demand
    if m.path == "/org/a11y/atspi/cache" && m.member == "GetItems" then { reply(c, m, "a((so)(so)(so)iiassusau)")(_.array(8)(())); return }
    if obj.isEmpty then { fail(c, m, "org.freedesktop.DBus.Error.UnknownObject", m.path); return }
    val o = obj.get
    try
      (m.iface, m.member) match
        case ("org.freedesktop.DBus.Properties", "Get") =>
          val iface = m.body(0).asInstanceOf[String]; val prop = m.body(1).asInstanceOf[String]
          property(o, iface, prop, me) match
            case Some(write) => reply(c, m, "v")(write)
            case None => fail(c, m, "org.freedesktop.DBus.Error.UnknownProperty", prop)
        case ("org.freedesktop.DBus.Properties", "GetAll") =>
          val iface = m.body(0).asInstanceOf[String]
          reply(c, m, "a{sv}") { w =>
            w.array(8) {
              propertyNames(iface).foreach { p =>
                property(o, iface, p, me).foreach { write => w.struct { w.str(p); write(w) } }
              }
            }
          }
        case ("org.freedesktop.DBus.Introspectable", "Introspect") =>
          reply(c, m, "s")(_.str("<node/>"))
        case ("org.a11y.atspi.Accessible", "GetChildAtIndex") =>
          child(o, intArg(m, 0)) match
            case Some(ch) => reply(c, m, "(so)")(_.ref(me, pathOf(ch)))
            case None => reply(c, m, "(so)")(_.ref("", NullPath))
        case ("org.a11y.atspi.Accessible", "GetChildren") =>
          reply(c, m, "a(so)")(w => w.array(8)((0 until childCount(o)).foreach(i => w.ref(me, pathOf(child(o, i).get)))))
        case ("org.a11y.atspi.Accessible", "GetIndexInParent") => reply(c, m, "i")(_.i32(indexInParent(o)))
        case ("org.a11y.atspi.Accessible", "GetRelationSet") => reply(c, m, "a(ua(so))")(_.array(8)(()))
        case ("org.a11y.atspi.Accessible", "GetRole") => reply(c, m, "u")(_.u32(role(o).toLong))
        case ("org.a11y.atspi.Accessible", "GetRoleName") => reply(c, m, "s")(_.str(roleName(role(o))))
        case ("org.a11y.atspi.Accessible", "GetLocalizedRoleName") => reply(c, m, "s")(_.str(roleName(role(o))))
        case ("org.a11y.atspi.Accessible", "GetState") =>
          val bits = states(o)
          reply(c, m, "au")(w => w.array(4) { w.u32(bits & 0xffffffffL); w.u32(bits >>> 32) })
        case ("org.a11y.atspi.Accessible", "GetAttributes") => reply(c, m, "a{ss}")(_.array(8)(()))
        case ("org.a11y.atspi.Accessible", "GetApplication") => reply(c, m, "(so)")(_.ref(me, RootPath))
        case ("org.a11y.atspi.Accessible", "GetInterfaces") =>
          reply(c, m, "as")(w => w.array(4)(interfaces(o).foreach(w.str)))
        case ("org.a11y.atspi.Component", "GetExtents") =>
          val (x, y, w0, h) = extents(o, intArg(m, 0).toLong)
          reply(c, m, "(iiii)")(w => w.struct { w.i32(x); w.i32(y); w.i32(w0); w.i32(h) })
        case ("org.a11y.atspi.Component", "GetPosition") =>
          val (x, y, _, _) = extents(o, intArg(m, 0).toLong)
          reply(c, m, "ii") { w => w.i32(x); w.i32(y) }
        case ("org.a11y.atspi.Component", "GetSize") =>
          val (_, _, w0, h) = extents(o, 1L)
          reply(c, m, "ii") { w => w.i32(w0); w.i32(h) }
        case ("org.a11y.atspi.Component", "Contains") =>
          val (x, y, w0, h) = extents(o, intArg(m, 2).toLong)
          val px = intArg(m, 0); val py = intArg(m, 1)
          reply(c, m, "b")(_.bool(px >= x && px < x + w0 && py >= y && py < y + h))
        case ("org.a11y.atspi.Component", "GetAccessibleAtPoint") =>
          val px = intArg(m, 0); val py = intArg(m, 1); val ct = intArg(m, 2).toLong
          val hit = nodes.indices.findLast { i => val (x, y, w, h) = extents(Item(i), ct); px >= x && px < x + w && py >= y && py < y + h }
          reply(c, m, "(so)")(w => hit.fold(w.ref("", NullPath))(i => w.ref(me, nodePath(i))))
        case ("org.a11y.atspi.Component", "GetLayer") => reply(c, m, "u")(_.u32(3)) // WIDGET
        case ("org.a11y.atspi.Component", "GetMDIZOrder") => reply(c, m, "n")(_.u16(0))
        case ("org.a11y.atspi.Component", "GetAlpha") => reply(c, m, "d")(_.double(1.0))
        case ("org.a11y.atspi.Component", "GrabFocus") => reply(c, m, "b")(_.bool(false))
        case ("org.a11y.atspi.Action", "GetActions") =>
          reply(c, m, "a(sss)")(w => w.array(8)(w.struct { w.str("click"); w.str(""); w.str("") }))
        case ("org.a11y.atspi.Action", "GetName") => reply(c, m, "s")(_.str(if intArg(m, 0) == 0 then "click" else ""))
        case ("org.a11y.atspi.Action", "GetDescription") => reply(c, m, "s")(_.str(""))
        case ("org.a11y.atspi.Action", "GetKeyBinding") => reply(c, m, "s")(_.str(""))
        case ("org.a11y.atspi.Action", "DoAction") =>
          o match
            case Item(i) if intArg(m, 0) == 0 =>
              if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"AT-SPI DoAction on node $i")
              onEvent(Event.AccessPress(i)); reply(c, m, "b")(_.bool(true))
            case _ => reply(c, m, "b")(_.bool(false))
        case ("org.a11y.atspi.Text", _) | ("org.a11y.atspi.EditableText", _) => textCall(c, m, o)
        case ("org.a11y.atspi.Application", "GetApplicationBusAddress") => reply(c, m, "s")(_.str(""))
        case _ => fail(c, m, "org.freedesktop.DBus.Error.UnknownMethod", s"${m.iface}.${m.member}")
    catch case e: RuntimeException => fail(c, m, "org.freedesktop.DBus.Error.Failed", String.valueOf(e.getMessage))

  // ---------------------------------------------------------------- Text / EditableText
  private def caretOf(n: AccessNode): Int = n.text.fold(n.value.length)(_.caret).max(0).min(n.value.length)
  private def selectionOf(n: AccessNode): (Int, Int) = n.text.fold((0, 0))(t => (t.selStart.max(0).min(n.value.length), t.selEnd.max(0).min(n.value.length)))

  /** Pixel rectangle (x, y, w, h) of UTF-16 unit `u` of node `i`: its published character box, else the whole field. */
  private def charRect(i: Int, u: Int, coord: Long): (Int, Int, Int, Int) =
    val n = nodes(i)
    val r = n.text.flatMap(_.charBounds.lift(u)).getOrElse(n.bounds)
    val s = scale(); val (ox, oy) = if coord == 0L then origin() else (0, 0)
    (ox + (r.x0 * s).toInt, oy + (r.y0 * s).toInt, (r.width * s).toInt, (r.height * s).toInt)

  private def textCall(c: Connection, m: Message, o: Obj): Unit =
    val i = o match { case Item(i) => i; case _ => -1 }
    if i < 0 then { fail(c, m, "org.freedesktop.DBus.Error.UnknownMethod", m.member); return }
    val n = nodes(i); val text = n.value
    val len = cpLen(text)
    def clampCp(v: Int) = v.max(0).min(len)
    def u(cp: Int) = text.offsetByCodePoints(0, clampCp(cp))
    def strArg(k: Int) = m.body.lift(k) match { case Some(s: String) => s; case _ => "" }
    def segReply(start: Int, end: Int): Unit = reply(c, m, "sii") { w => w.str(text.substring(start, end)); w.i32(cpOffset(text, start)); w.i32(cpOffset(text, end)) }
    def setSelection(anchor: Int, caret: Int): Unit = onEvent(Event.AccessSetSelection(i, u(anchor), u(caret)))
    def rangeRect(coord: Long): (Int, Int, Int, Int) =
      val a = u(intArg(m, 0)); val b = u(intArg(m, 1)).max(a)
      val rs = (a until b).map(k => charRect(i, k, coord))
      if rs.isEmpty then charRect(i, a, coord)
      else
        val x0 = rs.map(_._1).min; val y0 = rs.map(_._2).min
        (x0, y0, rs.map(r => r._1 + r._3).max - x0, rs.map(r => r._2 + r._4).max - y0)
    (m.iface, m.member) match
      case ("org.a11y.atspi.Text", "GetText") =>
        val a = clampCp(intArg(m, 0)); val b0 = intArg(m, 1)
        val b = (if b0 < 0 then len else clampCp(b0)).max(a)
        reply(c, m, "s")(_.str(text.substring(u(a), u(b))))
      case ("org.a11y.atspi.Text", "GetCaretOffset") => reply(c, m, "i")(_.i32(cpOffset(text, caretOf(n))))
      case ("org.a11y.atspi.Text", "SetCaretOffset") => setSelection(intArg(m, 0), intArg(m, 0)); reply(c, m, "b")(_.bool(true))
      case ("org.a11y.atspi.Text", "GetCharacterCount") => reply(c, m, "i")(_.i32(len))
      case ("org.a11y.atspi.Text", "GetCharacterAtOffset") =>
        val k = intArg(m, 0)
        reply(c, m, "i")(_.i32(if k < 0 || k >= len then 0 else text.codePointAt(u(k))))
      case ("org.a11y.atspi.Text", "GetStringAtOffset") =>
        val (s, e) = TextSegments.at(text, u(intArg(m, 0)), intArg(m, 1) match { case 0 => 0; case 1 => 1; case 2 => 2; case _ => 3 })
        segReply(s, e)
      case ("org.a11y.atspi.Text", "GetTextAtOffset") =>
        val (s, e) = TextSegments.at(text, u(intArg(m, 0)), TextSegments.fromBoundary(intArg(m, 1))); segReply(s, e)
      case ("org.a11y.atspi.Text", "GetTextBeforeOffset") =>
        val (s, e) = TextSegments.before(text, u(intArg(m, 0)), TextSegments.fromBoundary(intArg(m, 1))); segReply(s, e)
      case ("org.a11y.atspi.Text", "GetTextAfterOffset") =>
        val (s, e) = TextSegments.after(text, u(intArg(m, 0)), TextSegments.fromBoundary(intArg(m, 1))); segReply(s, e)
      case ("org.a11y.atspi.Text", "GetNSelections") =>
        val (a, b) = selectionOf(n); reply(c, m, "i")(_.i32(if a != b then 1 else 0))
      case ("org.a11y.atspi.Text", "GetSelection") =>
        val (a, b) = selectionOf(n)
        if intArg(m, 0) == 0 && a != b then reply(c, m, "ii") { w => w.i32(cpOffset(text, a)); w.i32(cpOffset(text, b)) }
        else fail(c, m, "org.freedesktop.DBus.Error.InvalidArgs", "no such selection")
      case ("org.a11y.atspi.Text", "AddSelection") =>
        val (a, b) = selectionOf(n)
        if a == b then { setSelection(intArg(m, 0), intArg(m, 1)); reply(c, m, "b")(_.bool(true)) } else reply(c, m, "b")(_.bool(false)) // single selection only
      case ("org.a11y.atspi.Text", "SetSelection") =>
        if intArg(m, 0) == 0 then { setSelection(intArg(m, 1), intArg(m, 2)); reply(c, m, "b")(_.bool(true)) } else reply(c, m, "b")(_.bool(false))
      case ("org.a11y.atspi.Text", "RemoveSelection") =>
        if intArg(m, 0) == 0 then { setSelection(cpOffset(text, caretOf(n)), cpOffset(text, caretOf(n))); reply(c, m, "b")(_.bool(true)) } else reply(c, m, "b")(_.bool(false))
      case ("org.a11y.atspi.Text", "GetCharacterExtents") =>
        val (x, y, w0, h) = charRect(i, u(intArg(m, 0)), intArg(m, 1).toLong)
        reply(c, m, "iiii") { w => w.i32(x); w.i32(y); w.i32(w0); w.i32(h) }
      case ("org.a11y.atspi.Text", "GetRangeExtents") =>
        val (x, y, w0, h) = rangeRect(intArg(m, 2).toLong)
        reply(c, m, "iiii") { w => w.i32(x); w.i32(y); w.i32(w0); w.i32(h) }
      case ("org.a11y.atspi.Text", "GetOffsetAtPoint") =>
        val px = intArg(m, 0); val py = intArg(m, 1); val ct = intArg(m, 2).toLong
        val hit = text.indices.find { k => val (x, y, w, h) = charRect(i, k, ct); px >= x && px < x + w && py >= y && py < y + h }
        reply(c, m, "i")(_.i32(hit.fold(-1)(cpOffset(text, _))))
      case ("org.a11y.atspi.Text", "GetBoundedRanges") => reply(c, m, "a(iisv)")(_.array(8)(()))
      case ("org.a11y.atspi.Text", "GetAttributes") | ("org.a11y.atspi.Text", "GetAttributeRun") =>
        reply(c, m, "a{ss}ii") { w => w.array(8)(()); w.i32(0); w.i32(len) }
      case ("org.a11y.atspi.Text", "GetDefaultAttributes") | ("org.a11y.atspi.Text", "GetDefaultAttributeSet") => reply(c, m, "a{ss}")(_.array(8)(()))
      case ("org.a11y.atspi.Text", "GetAttributeValue") => reply(c, m, "s")(_.str(""))
      case ("org.a11y.atspi.Text", "ScrollSubstringTo") | ("org.a11y.atspi.Text", "ScrollSubstringToPoint") => reply(c, m, "b")(_.bool(false))
      case ("org.a11y.atspi.EditableText", "SetTextContents") =>
        onEvent(Event.AccessSetText(i, strArg(0))); reply(c, m, "b")(_.bool(true))
      case ("org.a11y.atspi.EditableText", "InsertText") =>
        val at = u(intArg(m, 0)); val s = strArg(1)
        val ins = if intArg(m, 2) >= 0 && intArg(m, 2) < s.codePointCount(0, s.length) then s.substring(0, s.offsetByCodePoints(0, intArg(m, 2))) else s
        onEvent(Event.AccessSetText(i, text.substring(0, at) + ins + text.substring(at)))
        onEvent(Event.AccessSetSelection(i, at + ins.length, at + ins.length)); reply(c, m, "b")(_.bool(true))
      case ("org.a11y.atspi.EditableText", "DeleteText") =>
        val a = u(intArg(m, 0)); val b = u(intArg(m, 1)).max(a)
        onEvent(Event.AccessSetText(i, text.substring(0, a) + text.substring(b)))
        onEvent(Event.AccessSetSelection(i, a, a)); reply(c, m, "b")(_.bool(true))
      case ("org.a11y.atspi.EditableText", "CopyText") | ("org.a11y.atspi.EditableText", "CutText") | ("org.a11y.atspi.EditableText", "PasteText") =>
        reply(c, m, "b")(_.bool(false)) // the clipboard is owned by the application's own shortcuts
      case _ => fail(c, m, "org.freedesktop.DBus.Error.UnknownMethod", s"${m.iface}.${m.member}")

  private def propertyNames(iface: String): Seq[String] = iface match
    case "org.a11y.atspi.Accessible" => Seq("Name", "Description", "Parent", "ChildCount", "Locale", "AccessibleId")
    case "org.a11y.atspi.Application" => Seq("ToolkitName", "Version", "AtspiVersion", "Id")
    case "org.a11y.atspi.Action" => Seq("NActions")
    case "org.a11y.atspi.Text" => Seq("CharacterCount", "CaretOffset")
    case "org.a11y.atspi.Value" => Seq("MinimumValue", "MaximumValue", "MinimumIncrement", "CurrentValue")
    case _ => Nil

  /** A function that writes the property's variant (signature + value), or None if the object lacks it. */
  private def property(o: Obj, iface: String, p: String, me: String): Option[Writer => Unit] = (iface, p) match
    case ("org.a11y.atspi.Accessible", "Name") => Some(_.variantStr(name(o)))
    case ("org.a11y.atspi.Accessible", "Description") => Some(_.variantStr(o match
      case Item(i) if nodes(i).value.nonEmpty => nodes(i).value
      case _ => ""))
    case ("org.a11y.atspi.Accessible", "Parent") => Some(w => parent(o) match
      case Some(par) => w.variantRef(me, pathOf(par))
      case None => w.variantRef("", NullPath))
    case ("org.a11y.atspi.Accessible", "ChildCount") => Some(_.variantInt(childCount(o)))
    case ("org.a11y.atspi.Accessible", "Locale") => Some(_.variantStr("C"))
    case ("org.a11y.atspi.Accessible", "AccessibleId") => Some(_.variantStr(pathOf(o).split('/').last))
    case ("org.a11y.atspi.Application", "ToolkitName") if o == Root => Some(_.variantStr("scalaui"))
    case ("org.a11y.atspi.Application", "Version") if o == Root => Some(_.variantStr("0.1"))
    case ("org.a11y.atspi.Application", "AtspiVersion") if o == Root => Some(_.variantStr("2.1"))
    case ("org.a11y.atspi.Application", "Id") if o == Root => Some(_.variantInt(0))
    case ("org.a11y.atspi.Action", "NActions") => Some(_.variantInt(1))
    case ("org.a11y.atspi.Text", "CharacterCount") => Some(_.variantInt(o match { case Item(i) => cpLen(nodes(i).value); case _ => 0 }))
    case ("org.a11y.atspi.Text", "CaretOffset") => Some(_.variantInt(o match { case Item(i) => cpOffset(nodes(i).value, caretOf(nodes(i))); case _ => 0 }))
    case ("org.a11y.atspi.Value", n) => o match
      case Item(i) =>
        val v = valueOf(i)
        val d = n match
          case "MinimumValue" => 0.0
          case "MaximumValue" => if nodes(i).role == AccessRole.ProgressBar then 100.0 else 1.0
          case "MinimumIncrement" => 0.01
          case _ => v
        Some(w => { w.sig("d"); w.double(d) })
      case _ => None
    case _ => None

private[window] object AtSpi:
  /** AT-SPI text offsets count Unicode characters (code points); the published text uses UTF-16 indices. */
  def cpLen(s: String): Int = s.codePointCount(0, s.length)
  def cpOffset(s: String, utf16: Int): Int = s.codePointCount(0, utf16.max(0).min(s.length))
  val RootPath = "/org/a11y/atspi/accessible/root"
  val WinPath = "/org/a11y/atspi/accessible/win"
  val ItemPrefix = "/org/a11y/atspi/accessible/n"
  val NullPath = "/org/a11y/atspi/null"
  def nodePath(i: Int): String = ItemPrefix + i

  /** AtspiStateType bit positions. */
  object State:
    val Active = 1; val Checked = 4; val Editable = 7; val Enabled = 8; val MultiLine = 17; val SingleLine = 26; val Focusable = 11; val Focused = 12; val Sensitive = 24; val Showing = 25; val Visible = 30

  def actionable(r: AccessRole): Boolean = r match
    case AccessRole.Button | AccessRole.CheckBox | AccessRole.RadioButton | AccessRole.Switch | AccessRole.Link | AccessRole.Tab |
        AccessRole.ListItem | AccessRole.ComboBox => true
    case _ => false

  /** AtspiRole numbers. */
  def roleOf(r: AccessRole): Int = r match
    case AccessRole.Label => 29; case AccessRole.Heading => 83; case AccessRole.Button => 43; case AccessRole.CheckBox => 7
    case AccessRole.RadioButton => 44; case AccessRole.Switch => 62; case AccessRole.Slider => 51; case AccessRole.TextInput => 79
    case AccessRole.ComboBox => 11; case AccessRole.Link => 88; case AccessRole.ProgressBar => 42; case AccessRole.Tab => 37
    case AccessRole.ListItem => 32

  def roleName(role: Int): String = role match
    case 75 => "application"; case 23 => "frame"; case 29 => "label"; case 83 => "heading"; case 43 => "push button"
    case 7 => "check box"; case 44 => "radio button"; case 62 => "toggle button"; case 51 => "slider"; case 79 => "entry"
    case 11 => "combo box"; case 88 => "link"; case 42 => "progress bar"; case 37 => "page tab"; case 32 => "list item"
    case _ => "unknown"

/** Splits text into the character / word / sentence / line segments that AT-SPI `GetStringAtOffset` and friends return.
  * Words and sentences include their trailing whitespace and lines their line break, as GTK reports them. */
private[window] object TextSegments:
  val Char = 0; val Word = 1; val Sentence = 2; val Line = 3

  /** Old-style AtspiTextBoundaryType (char, word start/end, sentence start/end, line start/end) to a granularity. */
  def fromBoundary(b: Int): Int = b match { case 0 => Char; case 1 | 2 => Word; case 3 | 4 => Sentence; case _ => Line }

  /** Consecutive [start, end) segments (UTF-16) covering `text`. */
  def split(text: String, kind: Int): Vector[(Int, Int)] =
    val out = Vector.newBuilder[(Int, Int)]
    var i = 0
    val n = text.length
    def isWordChar(cp: Int) = Character.isLetterOrDigit(cp) || cp == '_'
    def isSpace(cp: Int) = Character.isWhitespace(cp) && cp != '\n'
    while i < n do
      var j = i
      kind match
        case Char => j = text.offsetByCodePoints(i, 1)
        case Line =>
          val nl = text.indexOf('\n', i)
          j = if nl < 0 then n else nl + 1
        case Word =>
          val cp = text.codePointAt(i)
          if text.charAt(i) == '\n' then j = i + 1
          else
            if isWordChar(cp) then while j < n && isWordChar(text.codePointAt(j)) do j = text.offsetByCodePoints(j, 1)
            else if !isSpace(cp) then j = text.offsetByCodePoints(i, 1)
            while j < n && isSpace(text.codePointAt(j)) do j = text.offsetByCodePoints(j, 1)
            if j == i then j = text.offsetByCodePoints(i, 1)
        case _ => // sentence: up to and including . ! ? (runs) and following spaces, or a line break
          var done = false
          while j < n && !done do
            val ch = text.charAt(j)
            if ch == '\n' then { j += 1; done = true }
            else if ch == '.' || ch == '!' || ch == '?' then
              while j < n && (text.charAt(j) == '.' || text.charAt(j) == '!' || text.charAt(j) == '?') do j += 1
              while j < n && isSpace(text.codePointAt(j)) do j += 1
              done = true
            else j += 1
      out += ((i, j)); i = j
    out.result()

  private def index(segs: Vector[(Int, Int)], offset: Int): Int = segs.indexWhere((s, e) => offset >= s && offset < e)

  def at(text: String, offset: Int, kind: Int): (Int, Int) =
    val segs = split(text, kind); val k = index(segs, offset)
    if k >= 0 then segs(k) else (text.length, text.length)
  def before(text: String, offset: Int, kind: Int): (Int, Int) =
    val segs = split(text, kind); val k = index(segs, offset)
    val at = if k >= 0 then k else segs.length
    if at > 0 then segs(at - 1) else (0, 0)
  def after(text: String, offset: Int, kind: Int): (Int, Int) =
    val segs = split(text, kind); val k = index(segs, offset)
    if k >= 0 && k + 1 < segs.length then segs(k + 1) else (text.length, text.length)
