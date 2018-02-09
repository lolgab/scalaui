package scalaui

import scala.scalanative.native.{CStruct2, Ptr, sizeof, stdlib}

object collection {
  type PointerList[T] = CStruct2[Ptr[T], Ptr[Byte]]

  implicit class PointerListOps[T](val ptr: Ptr[PointerList[T]]) {
    def head: Ptr[T] = !ptr._1

    def tail: Ptr[PointerList[T]] = (!ptr._2).cast[Ptr[PointerList[T]]]

    def head_=(v: Ptr[T]): Unit = !ptr._1 = v

    def tail_=(v: Ptr[PointerList[T]]): Unit = !ptr._2 = v.cast[Ptr[Byte]]

    def ::(v: Ptr[T]): Ptr[PointerList[T]] = {
      val l = stdlib.malloc(sizeof[PointerList[T]]).cast[Ptr[PointerList[T]]]
      l.head = v
      l.tail = ptr
      l
    }

//    TODO Scala Native doesn't like it!
//    def foreach(f: CFunctionPtr1[Ptr[T], Unit]): Unit = {
//      var l = ptr
//      while (l != null) {
//        f(l.head)
//        l = l.tail
//      }
//    }

    def free(): Unit = {
      var l = ptr
      while (l != null) {
        val l1 = l.tail
        stdlib.free(l.cast[Ptr[Byte]])
        l = l1
      }
    }
  }
}
