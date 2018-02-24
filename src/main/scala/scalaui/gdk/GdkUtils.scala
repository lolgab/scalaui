package scalaui.gdk

import scala.scalanative.native._
import scalaui.ui.uiModifiers
import scalaui.uiOps.uiModifiers

object GdkUtils {
  import gdk._
  import gdkOps._

  private lazy val gdkKeyMap: Ptr[GdkKeymap] = gdk_keymap_get_default()

  def keyValue(key: Char, modifiers: uiModifiers): Char = {
    val keyval             = stackalloc[CInt]
    val effective_group    = stackalloc[CInt]
    val level              = stackalloc[CInt]
    val consumed_modifiers = stackalloc[GdkModifierType]
    val keys               = stackalloc[Ptr[GdkKeymapKey]]
    val n_keys             = stackalloc[CInt]

    val gdkModifiers: GdkModifierType = {
      var res = 0.toUInt
      if ((modifiers & uiModifiers.uiModifierCtrl) != 0.toUInt) res |= GDK_CONTROL_MASK
      if ((modifiers & uiModifiers.uiModifierAlt) != 0.toUInt) res |= GDK_MOD1_MASK | GDK_META_MASK
      if ((modifiers & uiModifiers.uiModifierShift) != 0.toUInt) res |= GDK_SHIFT_MASK
      if ((modifiers & uiModifiers.uiModifierSuper) != 0.toUInt) res |= GDK_SUPER_MASK
      res
    }

    gdk_keymap_get_entries_for_keyval(gdkKeyMap, key.toInt, keys, n_keys)

    if (!n_keys > 0) {
      gdk_keymap_translate_keyboard_state(
        gdkKeyMap,
        (!keys).keycode.toInt,
        gdkModifiers,
        0,
        keyval,
        effective_group,
        level,
        consumed_modifiers
      )
      (!keyval).toChar
    } else
      key
  }
}
