package scalaui

import scala.scalanative.unsafe._
import scala.scalanative.runtime.Intrinsics._
import scala.scalanative.runtime.{fromRawPtr, toRawPtr}

private[scalaui] object PtrConverter {
  def toPtr[T](f: T): Ptr[Byte] = {
    val rawPtr = castObjectToRawPtr(f.asInstanceOf[Object])
    fromRawPtr[Byte](rawPtr)
  }
  def fromPtr[T](ptr: Ptr[Byte]): T = {
    val rawPtr = toRawPtr(ptr)
    castRawPtrToObject(rawPtr).asInstanceOf[T]
  }
}
