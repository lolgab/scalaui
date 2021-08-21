package scalaui

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.scalanative.libc.stdlib
import scalaui.ui._
import scalaui.uiOps._

// TODO Generalize control for font, fontfamily etc.
class Font private[scalaui] () extends Freeable {
  //TODO waiting for https://github.com/scala-native/scala-native/issues/367
  private[scalaui] var control: Ptr[uiFontDescriptor] = stdlib
    .malloc(sizeof[uiFontDescriptor])
    .asInstanceOf[Ptr[uiFontDescriptor]] //TODO Stop leaking memory
  control =
    stdlib.malloc(sizeof[uiFontDescriptor]).asInstanceOf[Ptr[uiFontDescriptor]]

  def this(
      family: String,
      size: Double,
      weight: Int = Weight.Normal,
      italic: Italic.Value = Italic.Normal,
      stretch: Stretch.Value = Stretch.Normal
  ) = {
    this()
    val z = new Zone {
      var ptr: Ptr[Byte] = _
      override def alloc(size: CSize): Ptr[Byte] = {
        ptr = stdlib.malloc(size)
        ptr
      }

      def close(): Unit = {
        stdlib.free(ptr)
        ptr = null
      }

      def isClosed: CBool = ptr == null
    }
    control.Family = toCString(family)(z)
    control.Size = size
    control.Weight = weight.toUInt
    control.Italic = italic.id.toUInt
    control.Stretch = stretch.id.toUInt
  }

  override def free(): Unit = {
    stdlib.free(control.Family.asInstanceOf[Ptr[Byte]])
    stdlib.free(control.asInstanceOf[Ptr[Byte]])
  }
}
