package it.lolgab.libui.examples
import scala.scalanative.native.Ptr
import it.lolgab.libui.ui._
import scalanative.native._
import stdlib.malloc
import string._

object Hello {
  def onClosing(w: Ptr[uiWindow], data: Ptr[Byte]): CInt = {
    uiControlDestroy(w)
    uiQuit()
    0
  }

  def onClick(b: Ptr[uiButton], data: Ptr[Byte]): Unit = {
    val array = data.cast[Ptr[CArray[ULong, Nat._2]]]
    val label = (!array._1).cast[Ptr[uiLabel]]
    val entry = (!array._2).cast[Ptr[uiEntry]]
    val str: CString = stackalloc[CChar](100)
    strcpy(str, uiLabelText(label))
    strcat(str, uiEntryText(entry))
    uiLabelSetText(label, str)
  }

  def main(args: Array[String]): Unit = {

    val options: Ptr[uiInitOptions] = malloc(sizeof[uiInitOptions])
      .cast[Ptr[uiInitOptions]]
    uiInit(options)
    val w =
      uiNewWindow(c"Hi from Scala Native :-)", 200, 200, 0)
    uiWindowOnClosing(w, CFunctionPtr.fromFunction2(onClosing), null)

    val buttonBox = uiNewHorizontalBox()

    val entry: Ptr[uiEntry] = uiNewEntry()
    uiBoxAppend(buttonBox, entry, 0)

    val button = uiNewButton(c"Click me!")
    uiBoxAppend(buttonBox, button, 0)

    val label = uiNewLabel(c"Hi ")
    
    val mainBox: Ptr[uiBox] = uiNewVerticalBox()
    uiBoxAppend(mainBox, buttonBox, 0)
    uiBoxAppend(mainBox, label, 0)

    val data: Ptr[CArray[ULong, Nat._2]] = stackalloc[CArray[ULong, Nat._2]]
    !data._1 = label.cast[ULong]
    !data._2 = entry.cast[ULong]

    uiButtonOnClicked(button,
                      CFunctionPtr.fromFunction2(onClick),
                      data.cast[Ptr[Byte]])
    uiWindowSetChild(w, mainBox)
    uiControlShow(w)

    uiMain()
    uiUninit()
  }
}
