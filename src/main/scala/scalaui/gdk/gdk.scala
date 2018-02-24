package scalaui.gdk

import scala.scalanative.native._
import scalaui.ui.uiModifiers
import scalaui.uiOps.uiModifiers

@link("gdk-3")
@extern
object gdk {
  type GdkKeymap = extern

  type GdkKeymapKey = CStruct3[
    CUnsignedInt,
    CInt,
    CInt
  ]

  type GdkModifierType = CUnsignedInt

  def gdk_keymap_get_default(): Ptr[GdkKeymap]                      = extern
  def gdk_keyval_to_upper(keyValue: CUnsignedInt): CUnsignedInt     = extern
  def gdk_keymap_get_caps_lock_state(map: Ptr[GdkKeymapKey]): CBool = extern

  def gdk_keymap_translate_keyboard_state(keymap: Ptr[GdkKeymap],
                                          hardware_keycode: CInt,
                                          state: GdkModifierType,
                                          group: CInt,
                                          keyval: Ptr[CInt],
                                          effective_group: Ptr[CInt],
                                          level: Ptr[CInt],
                                          consumed_modifiers: Ptr[GdkModifierType]): CBool = extern
  def gdk_keymap_get_entries_for_keyval(keymap: Ptr[GdkKeymap],
                                        keyval: CInt,
                                        keys: Ptr[Ptr[GdkKeymapKey]],
                                        n_keys: Ptr[CInt]): CBool = extern
}

object gdkOps {
  import gdk._

  implicit class GdkKeymapKeyOps(val ptr: Ptr[GdkKeymapKey]) {
    def keycode: CUnsignedInt = !ptr._1
    def group: CInt           = !ptr._2
    def level: CInt           = !ptr._3
  }

  val GDK_SHIFT_MASK: UInt   = 1.toUInt
  val GDK_CONTROL_MASK: UInt = 1.toUInt << 2
  val GDK_MOD1_MASK: UInt    = 1.toUInt << 3
  val GDK_SUPER_MASK: UInt   = 1.toUInt << 26
  val GDK_META_MASK: UInt    = 1.toUInt << 28

}