package scalaui.ui

import scalaui.color.Color
import scalaui.geom.Vec2

/** Font weights of the bundled UI family (Inter). */
enum Weight:
  case Regular, Medium, SemiBold

enum ToastKind:
  case Info, Success, Warning, Error

enum ButtonKind:
  case Default, Primary, Subtle, Danger

/** A rung of the type scale: size, letter-spacing (in em) and line-height factor. */
final case class TypeRung(size: Double, tracking: Double = 0.0, lineHeight: Double = 1.0, weight: Weight = Weight.Regular):
  def trackingPx: Double = tracking * size

/** Design tokens: every color, size and radius the widgets use; widgets never hard-code colors. The old flat fields
  * (`widget`, `accent`, ...) keep their names; `accent` is the primary color, `accentText` is for text/focus on surfaces.
  */
final case class Style(
    dark: Boolean = true,
    // surfaces
    background: Color = Color.rgb8(31, 31, 33),      // window / content background
    panel: Color = Color.rgb8(38, 38, 41),           // side panels, popups
    faint: Color = Color.rgb8(35, 35, 38),           // groups, zebra rows
    chrome: Color = Color.rgb8(41, 41, 44),          // toolbar / ribbon
    tabStrip: Color = Color.rgb8(31, 31, 33),        // strip behind tabs, title area
    canvas: Color = Color.rgb8(20, 20, 22),          // document / workspace behind pages
    statusBar: Color = Color.rgb8(31, 31, 33),
    menu: Color = Color.rgb8(43, 43, 46),            // menus and popups
    inputBackground: Color = Color.rgb8(30, 30, 32),
    // widget states
    widget: Color = Color.rgb8(58, 58, 62),
    widgetHover: Color = Color.rgb8(70, 70, 75),
    widgetActive: Color = Color.rgb8(84, 84, 90),
    checked: Color = Color.rgb8(46, 58, 102),        // toggled-on tool buttons, selected rows
    // lines
    border: Color = Color.rgb8(58, 58, 62),
    borderHover: Color = Color.rgb8(110, 110, 118),
    borderStrong: Color = Color.rgb8(85, 85, 90),
    inputBorder: Color = Color.rgb8(122, 122, 128),
    // accent and semantic colors
    accent: Color = Color.rgb8(122, 147, 240),       // primary: filled buttons, checked controls, slider fill
    accentBright: Color = Color.rgb8(156, 176, 245), // accent for text, focus rings
    onAccent: Color = Color.rgb8(17, 22, 46),        // text/icon on `accent`
    selection: Color = Color(0.478f, 0.576f, 0.941f, 0.33f),
    success: Color = Color.rgb8(91, 192, 138),
    warning: Color = Color.rgb8(240, 160, 80),
    danger: Color = Color.rgb8(238, 106, 106),
    info: Color = Color.rgb8(108, 155, 234),
    // text
    text: Color = Color.rgb8(210, 210, 214),
    textStrong: Color = Color.rgb8(244, 244, 246),
    textWeak: Color = Color.rgb8(150, 150, 156),
    textDisabled: Color = Color.rgb8(106, 106, 112),
    icon: Color = Color.rgb8(221, 221, 224),
    groupLabel: Color = Color.rgb8(160, 160, 166),
    // shadows
    shadow: Color = Color(0, 0, 0, 0.5f),
    // type scale
    fontSize: Double = 13,
    headingSize: Double = 18,
    monoSize: Double = 12.5,
    captionSize: Double = 11,
    // metrics
    itemSpacing: Vec2 = Vec2(8, 5),
    buttonPadding: Vec2 = Vec2(8, 3),
    minHeight: Double = 26,
    rounding: Double = 5,
    windowRounding: Double = 9,
    indent: Double = 18,
    sliderWidth: Double = 160,
    textEditWidth: Double = 200
):
  /** Heading rung: semibold, slightly tight. */
  def heading: TypeRung = TypeRung(headingSize, -0.012, 1.0, Weight.SemiBold)
  /** Caption / group-label rung: small and slightly open. */
  def caption: TypeRung = TypeRung(captionSize, 0.018, 1.0, Weight.Medium)
  /** `c` as a hover/press overlay on any surface: `text` at low alpha. */
  def overlay(alpha: Float): Color = text.withAlpha(alpha)

object Style:
  val Dark: Style = Style()
  val Light: Style = Style(
    dark = false,
    background = Color.rgb8(250, 250, 251), panel = Color.rgb8(247, 247, 248), faint = Color.rgb8(243, 243, 245),
    chrome = Color.rgb8(249, 249, 250), tabStrip = Color.rgb8(240, 240, 242), canvas = Color.rgb8(229, 229, 232),
    statusBar = Color.rgb8(240, 240, 242), menu = Color.White, inputBackground = Color.White,
    widget = Color.rgb8(238, 238, 241), widgetHover = Color.rgb8(228, 228, 232), widgetActive = Color.rgb8(214, 214, 220),
    checked = Color.rgb8(221, 227, 248),
    border = Color.rgb8(225, 225, 229), borderHover = Color.rgb8(160, 160, 168), borderStrong = Color.rgb8(200, 200, 206),
    inputBorder = Color.rgb8(138, 138, 146),
    accent = Color.rgb8(59, 91, 219), accentBright = Color.rgb8(43, 71, 181), onAccent = Color.White,
    selection = Color(0.231f, 0.357f, 0.859f, 0.28f),
    success = Color.rgb8(46, 139, 87), warning = Color.rgb8(224, 123, 31), danger = Color.rgb8(209, 59, 59), info = Color.rgb8(47, 111, 208),
    text = Color.rgb8(48, 48, 52), textStrong = Color.rgb8(20, 20, 22), textWeak = Color.rgb8(104, 104, 112),
    textDisabled = Color.rgb8(168, 168, 174), icon = Color.rgb8(66, 66, 72), groupLabel = Color.rgb8(112, 112, 120),
    shadow = Color(0, 0, 0, 0.22f)
  )

  /** Parses a `#rrggbb` accent into a full light or dark accent set (accent, accentBright, onAccent, selection, checked). */
  def withAccent(base: Style, hex: String): Style =
    Color.hex(hex).fold(base) { c =>
      val (h, s, v) = Color.toHsv(c)
      if base.dark then
        val bright = Color.fromHsv(h, s * 0.65f, math.min(1f, v + 0.12f))
        base.copy(accent = c, accentBright = bright, onAccent = Color.rgb8(17, 20, 32), selection = c.withAlpha(0.33f),
          checked = Color.fromHsv(h, math.min(1f, s * 0.7f), 0.38f))
      else
        base.copy(accent = c, accentBright = Color.fromHsv(h, s, v * 0.82f), onAccent = Color.White, selection = c.withAlpha(0.28f),
          checked = Color.fromHsv(h, s * 0.12f, 0.98f))
    }

  /** Mixes `a` towards `b` by `t` in 0..1 (straight RGBA lerp), used for hover/press transitions. */
  def mix(a: Color, b: Color, t: Double): Color =
    val k = t.toFloat.max(0f).min(1f)
    Color(a.r + (b.r - a.r) * k, a.g + (b.g - a.g) * k, a.b + (b.b - a.b) * k, a.a + (b.a - a.a) * k)
