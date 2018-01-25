package scalaui

import scala.scalanative.native
import scala.scalanative.native.{
  CChar,
  CDouble,
  CFunctionPtr0,
  CFunctionPtr1,
  CFunctionPtr2,
  CFunctionPtr3,
  CInt,
  CSize,
  CString,
  CStruct1,
  CStruct12,
  CStruct5,
  CStruct6,
  CStruct7,
  CStruct9,
  CUnsignedInt,
  Ptr,
  UInt,
  ULong,
  extern
}

@native.link("ui")
@native.extern
object ui {

  type uiInitOptions = CStruct1[CSize]

  def uiInit(options: Ptr[uiInitOptions]): CString = extern
  def uiUninit(): Unit = extern
  def uiFreeInitError(err: CString): Unit = extern

  def uiMain(): Unit = extern
  def uiMainSteps(): Unit = extern
  def uiMainStep(wait: CInt): CInt = extern
  def uiQuit(): Unit = extern

  def uiQueueMain(f: CFunctionPtr1[Ptr[Byte], Unit], data: Ptr[Byte]): Unit =
    extern

  def uiOnShouldQuit(f: CFunctionPtr1[Ptr[Byte], Unit], data: Ptr[Byte]): Unit =
    extern

  def uiFreeText(text: CString): Unit = extern

  type uiControl = extern
  /*
  type uiControl = CStruct14[
    UInt,
    UInt,
    UInt,
    CFunctionPtr1[Ptr[uiControl], Unit],
    CFunctionPtr1[Ptr[uiControl], ULong],
    CFunctionPtr1[Ptr[uiControl], Ptr[uiControl]],
    CFunctionPtr2[Ptr[uiControl], Ptr[uiControl], Unit],
    CFunctionPtr1[Ptr[uiControl], CInt],
    CFunctionPtr1[Ptr[uiControl], CInt],
    CFunctionPtr1[Ptr[uiControl], Unit],
    CFunctionPtr1[Ptr[uiControl], Unit],
    CFunctionPtr1[Ptr[uiControl], CInt],
    CFunctionPtr1[Ptr[uiControl], Unit],
    CFunctionPtr1[Ptr[uiControl], Unit]
  ]
   */
  def uiControlDestroy(uc: Ptr[uiControl]): Unit = extern
  def uiControlHandle(uc: Ptr[uiControl]): ULong = extern
  def uiControlParent(uc: Ptr[uiControl]): Ptr[uiControl] = extern
  def uiControlSetParent(uc: Ptr[uiControl], parent: Ptr[uiControl]): Unit =
    extern
  def uiControlToplevel(uc: Ptr[uiControl]): CInt = extern
  def uiControlVisible(uc: Ptr[uiControl]): CInt = extern
  def uiControlShow(uc: Ptr[uiControl]): Unit = extern
  def uiControlHide(uc: Ptr[uiControl]): Unit = extern
  def uiControlEnabled(uc: Ptr[uiControl]): CInt = extern
  def uiControlEnable(uc: Ptr[uiControl]): Unit = extern
  def uiControlDisable(uc: Ptr[uiControl]): Unit = extern

  def uiAllocControl(n: CSize,
                     OSsig: UInt,
                     typesig: UInt,
                     typenamestr: CString): Ptr[uiControl] = extern
  def uiFreeControl(uc: Ptr[uiControl]): Unit = extern

  def uiControlVerifySetParent(uc: Ptr[uiControl],
                               parent: Ptr[uiControl]): Unit = extern
  def uiControlEnabledToUser(uc: Ptr[uiControl]): CInt = extern

  def uiUserBugCannotSetParentOnToplevel(`type`: CString): Unit = extern

  type uiWindow = extern
  def uiWindowTitle(w: Ptr[uiWindow]): CString = extern
  def uiWindowSetTitle(w: Ptr[uiWindow], title: CString): Unit = extern
  def uiWindowContentSize(w: Ptr[uiWindow],
                          width: Ptr[CInt],
                          height: Ptr[CInt]): Unit = extern
  def uiWindowSetContentSize(w: Ptr[uiWindow],
                             width: CInt,
                             height: CInt): Unit = extern
  def uiWindowFullscreen(w: Ptr[uiWindow]): CInt = extern
  def uiWindowSetFullscreen(w: Ptr[uiWindow], fullscreen: CInt): Unit = extern
  def uiWindowOnContentSizeChanged(
      w: Ptr[uiWindow],
      f: CFunctionPtr2[Ptr[uiWindow], Ptr[Byte], Unit],
      data: Ptr[Byte]): Unit = extern
  def uiWindowOnClosing(w: Ptr[uiWindow],
                        f: CFunctionPtr2[Ptr[uiWindow], Ptr[Byte], CInt],
                        data: Ptr[Byte]): Unit = extern
  def uiWindowBorderless(w: Ptr[uiWindow]): CInt = extern
  def uiWindowSetBorderless(w: Ptr[uiWindow], borderless: CInt): Unit = extern
  def uiWindowSetChild(w: Ptr[uiWindow], child: Ptr[uiControl]): Unit = extern
  def uiWindowMargined(w: Ptr[uiWindow]): CInt = extern
  def uiWindowSetMargined(w: Ptr[uiWindow], margined: CInt): Unit = extern
  def uiNewWindow(title: CString,
                  width: CInt,
                  height: CInt,
                  hasMenubar: CInt): Ptr[uiWindow] = extern

  type uiButton = extern
  def uiButtonText(b: Ptr[uiButton]): CString = extern
  def uiButtonSetText(b: Ptr[uiButton], text: CString): Unit = extern
  def uiButtonOnClicked(b: Ptr[uiButton],
                        f: CFunctionPtr0[Unit],
                        data: Ptr[Byte]): Unit = extern
//def uiButtonOnClicked(b: Ptr[uiButton], f: CFunctionPtr2[Ptr[uiButton], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern

  def uiNewButton(text: CString): Ptr[uiButton] = extern

  type uiBox = extern
  def uiBoxAppend(b: Ptr[uiBox], child: Ptr[uiControl], stretchy: CInt): Unit =
    extern
  def uiBoxDelete(b: Ptr[uiBox], index: CInt): Unit = extern
  def uiBoxPadded(b: Ptr[uiBox]): CInt = extern
  def uiBoxSetPadded(b: Ptr[uiBox], padded: CInt): Unit = extern
  def uiNewHorizontalBox(): Ptr[uiBox] = extern
  def uiNewVerticalBox(): Ptr[uiBox] = extern

  type uiCheckbox = extern
  def uiCheckboxText(c: Ptr[uiCheckbox]): CString = extern
  def uiCheckboxSetText(c: Ptr[uiCheckbox], text: CString): Unit = extern
  def uiCheckboxOnToggled(c: Ptr[uiCheckbox],
                          f: CFunctionPtr2[uiCheckbox, Ptr[Byte], Unit],
                          data: Ptr[Byte]): Unit = extern
//def uiCheckboxOnToggled(c: Ptr[uiCheckbox], f: CFunctionPtr2[uiCheckbox,Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern

  def uiCheckboxChecked(c: Ptr[uiCheckbox]): CInt = extern
  def uiCheckboxSetChecked(c: Ptr[uiCheckbox], checked: CInt): Unit = extern
  def uiNewCheckbox(text: CString): Ptr[uiCheckbox] = extern

  type uiEntry = extern
  def uiEntryText(e: Ptr[uiEntry]): CString = extern
  def uiEntrySetText(e: Ptr[uiEntry], text: CString): Unit = extern
  def uiEntryOnChanged(e: Ptr[uiEntry],
                       f: CFunctionPtr2[Ptr[uiEntry], Ptr[Byte], Unit],
                       data: Ptr[Byte]): Unit = extern
  def uiEntryReadOnly(e: Ptr[uiEntry]): CInt = extern
  def uiEntrySetReadOnly(e: Ptr[uiEntry], readonly: CInt): Unit = extern
  def uiNewEntry(): Ptr[uiEntry] = extern
  def uiNewPasswordEntry(): Ptr[uiEntry] = extern
  def uiNewSearchEntry(): Ptr[uiEntry] = extern

  type uiLabel = extern
  def uiLabelText(l: Ptr[uiLabel]): CString = extern
  def uiLabelSetText(l: Ptr[uiLabel], text: CString): Unit = extern
  def uiNewLabel(text: CString): Ptr[uiLabel] = extern

  type uiTab = extern
  def uiTabAppend(t: Ptr[uiTab], name: CString, c: Ptr[uiControl]): Unit =
    extern
  def uiTabInsertAt(t: Ptr[uiTab],
                    name: CString,
                    before: CInt,
                    c: Ptr[uiControl]): Unit = extern
  def uiTabDelete(t: Ptr[uiTab], index: CInt): Unit = extern
  def uiTabNumPages(t: Ptr[uiTab]): CInt = extern
  def uiTabMargined(t: Ptr[uiTab], page: CInt): CInt = extern
  def uiTabSetMargined(t: Ptr[uiTab], page: CInt, margined: CInt): Unit = extern
  def uiNewTab(): Ptr[uiTab] = extern

  type uiGroup = extern
  def uiGroupTitle(g: Ptr[uiGroup]): CString = extern
  def uiGroupSetTitle(g: Ptr[uiGroup], title: CString): Unit = extern
  def uiGroupSetChild(g: Ptr[uiGroup], c: Ptr[uiControl]): Unit = extern
  def uiGroupMargined(g: Ptr[uiGroup]): CInt = extern
  def uiGroupSetMargined(g: Ptr[uiGroup], margined: CInt): Unit = extern
  def uiNewGroup(title: CString): Ptr[uiGroup] = extern

  type uiSpinbox = extern
  def uiSpinboxValue(s: Ptr[uiSpinbox]): CInt = extern
  def uiSpinboxSetValue(s: Ptr[uiSpinbox], value: CInt): Unit = extern
  def uiSpinboxOnChanged(s: Ptr[uiSpinbox],
//  f: CFunctionPtr2[Ptr[uiSpinbox], Ptr[Byte], Unit],
                         f: CFunctionPtr0[Unit],
                         data: Ptr[Byte]): Unit = extern
  def uiNewSpinbox(min: CInt, max: CInt): Ptr[uiSpinbox] = extern

  type uiSlider = extern
  def uiSliderValue(s: Ptr[uiSlider]): CInt = extern
  def uiSliderSetValue(s: Ptr[uiSlider], value: CInt): Unit = extern
  def uiSliderOnChanged(s: Ptr[uiSlider],
                        //f: CFunctionPtr2[Ptr[uiSlider], Ptr[Byte], Unit],
                        f: CFunctionPtr0[Unit],
                        data: Ptr[Byte]): Unit = extern
  def uiNewSlider(min: CInt, max: CInt): Ptr[uiSlider] = extern

  type uiProgressBar = extern
  def uiProgressBarValue(p: Ptr[uiProgressBar]): CInt = extern
  def uiProgressBarSetValue(p: Ptr[uiProgressBar], n: CInt): Unit = extern
  def uiNewProgressBar(): Ptr[uiProgressBar] = extern

  type uiSeparator = extern
  def uiNewHorizontalSeparator(): Ptr[uiSeparator] = extern
  def uiNewVerticalSeparator(): Ptr[uiSeparator] = extern

  type uiCombobox = extern
  def uiComboboxAppend(c: Ptr[uiCombobox], text: CString): Unit = extern
  def uiComboboxSelected(c: Ptr[uiCombobox]): CInt = extern
  def uiComboboxSetSelected(c: Ptr[uiCombobox], n: CInt): Unit = extern
  def uiComboboxOnSelected(c: Ptr[uiCombobox],
                           f: CFunctionPtr2[Ptr[uiCombobox], Ptr[Byte], Unit],
                           data: Ptr[Byte]): Unit = extern
  def uiNewCombobox(): Ptr[uiCombobox] = extern

  type uiEditableCombobox = extern
  def uiEditableComboboxAppend(c: Ptr[uiEditableCombobox],
                               text: CString): Unit = extern
  def uiEditableComboboxText(c: Ptr[uiEditableCombobox]): CString = extern
  def uiEditableComboboxSetText(c: Ptr[uiEditableCombobox],
                                text: CString): Unit = extern
  def uiEditableComboboxOnChanged(
      c: Ptr[uiEditableCombobox],
      f: CFunctionPtr2[Ptr[uiEditableCombobox], Ptr[Byte], Unit],
      data: Ptr[Byte]): Unit = extern
  def uiNewEditableCombobox(): Ptr[uiEditableCombobox] = extern

  type uiRadioButtons = extern
  def uiRadioButtonsAppend(r: Ptr[uiRadioButtons], text: CString): Unit = extern
  def uiRadioButtonsSelected(r: Ptr[uiRadioButtons]): CInt = extern
  def uiRadioButtonsSetSelected(r: Ptr[uiRadioButtons], n: CInt): Unit = extern
  def uiRadioButtonsOnSelected(r: Ptr[uiRadioButtons],
                               f: CFunctionPtr0[Unit],
                               //f: CFunctionPtr2[Ptr[uiRadioButtons], Ptr[Byte], Unit],
                               data: Ptr[Byte]): Unit = extern
  def uiNewRadioButtons(): Ptr[uiRadioButtons] = extern

  type uiDateTimePicker = extern
  def uiNewDateTimePicker(): Ptr[uiDateTimePicker] = extern
  def uiNewDatePicker(): Ptr[uiDateTimePicker] = extern
  def uiNewTimePicker(): Ptr[uiDateTimePicker] = extern

  type uiMultilineEntry = extern
  def uiMultilineEntryText(e: Ptr[uiMultilineEntry]): CString = extern
  def uiMultilineEntrySetText(e: Ptr[uiMultilineEntry], text: CString): Unit =
    extern
  def uiMultilineEntryAppend(e: Ptr[uiMultilineEntry], text: CString): Unit =
    extern
  def uiMultilineEntryOnChanged(
      e: Ptr[uiMultilineEntry],
      f: CFunctionPtr0[Unit],
      //f: CFunctionPtr2[Ptr[uiMultilineEntry], Ptr[Byte], Unit],
      data: Ptr[Byte]): Unit = extern
  def uiMultilineEntryReadOnly(e: Ptr[uiMultilineEntry]): CInt = extern
  def uiMultilineEntrySetReadOnly(e: Ptr[uiMultilineEntry],
                                  readonly: CInt): Unit = extern
  def uiNewMultilineEntry(): Ptr[uiMultilineEntry] = extern
  def uiNewNonWrappingMultilineEntry(): Ptr[uiMultilineEntry] = extern

  type uiMenuItem = extern
  def uiMenuItemEnable(m: Ptr[uiMenuItem]): Unit = extern
  def uiMenuItemDisable(m: Ptr[uiMenuItem]): Unit = extern
  def uiMenuItemOnClicked(m: Ptr[uiMenuItem],
//      f: CFunctionPtr3[Ptr[uiMenuItem], Ptr[uiWindow], Ptr[Byte], Unit],
                          f: CFunctionPtr0[Unit],
                          data: Ptr[Byte]): Unit = extern
  def uiMenuItemChecked(m: Ptr[uiMenuItem]): CInt = extern
  def uiMenuItemSetChecked(m: Ptr[uiMenuItem], checked: CInt): Unit = extern

  type uiMenu = extern
  def uiMenuAppendItem(m: Ptr[uiMenu], name: CString): Ptr[uiMenuItem] = extern
  def uiMenuAppendCheckItem(m: Ptr[uiMenu], name: CString): Ptr[uiMenuItem] =
    extern
  def uiMenuAppendQuitItem(m: Ptr[uiMenu]): Ptr[uiMenuItem] = extern
  def uiMenuAppendPreferencesItem(m: Ptr[uiMenu]): Ptr[uiMenuItem] = extern
  def uiMenuAppendAboutItem(m: Ptr[uiMenu]): Ptr[uiMenuItem] = extern
  def uiMenuAppendSeparator(m: Ptr[uiMenu]): Unit = extern
  def uiNewMenu(name: CString): Ptr[uiMenu] = extern

  def uiOpenFile(parent: Ptr[uiWindow]): CString = extern
  def uiSaveFile(parent: Ptr[uiWindow]): CString = extern
  def uiMsgBox(parent: Ptr[uiWindow],
               title: CString,
               description: CString): Unit = extern
  def uiMsgBoxError(parent: Ptr[uiWindow],
                    title: CString,
                    description: CString): Unit = extern

  type uiWindowResizeEdge = CUnsignedInt

  type uiArea = extern
  type uiAreaHandler =
    CStruct5[ //Ptr[Byte] need cast[Ptr[uiAreaHandler]]
             CFunctionPtr3[Ptr[Byte], Ptr[uiArea], Ptr[uiAreaDrawParams], Unit],
             CFunctionPtr3[Ptr[Byte], Ptr[uiArea], Ptr[uiAreaMouseEvent], Unit],
             CFunctionPtr3[Ptr[Byte], Ptr[uiArea], CInt, Unit],
             CFunctionPtr1[Ptr[Byte], Unit],
             CFunctionPtr3[Ptr[Byte], Ptr[uiArea], Ptr[uiAreaKeyEvent], Unit]]

  def uiAreaSetSize(a: Ptr[uiArea], width: CInt, height: CInt): Unit = extern
  def uiAreaQueueRedrawAll(a: Ptr[uiArea]): Unit = extern
  def uiAreaScrollTo(a: Ptr[uiArea],
                     x: CDouble,
                     y: CDouble,
                     width: CDouble,
                     height: CDouble): Unit = extern
  //TODO
  def uiAreaBeginUserWindowMove(a: Ptr[uiArea]): Unit = extern
  def uiAreaBeginUserWindowResize(a: Ptr[uiArea],
                                  edge: uiWindowResizeEdge): Unit = extern
  def uiNewArea(ah: Ptr[uiAreaHandler]): Ptr[uiArea] = extern
  def uiNewScrollingArea(ah: Ptr[uiAreaHandler],
                         width: Int,
                         height: Int): Ptr[uiArea] = extern

  type uiDrawContext = extern

  type uiAreaDrawParams = CStruct7[Ptr[uiDrawContext],
                                   CDouble,
                                   CDouble,
                                   CDouble,
                                   CDouble,
                                   CDouble,
                                   CDouble]

  type uiDrawPath = extern

  type uiDrawBrushType = CUnsignedInt
  type uiDrawLineCap = CUnsignedInt
  type uiDrawLineJoin = CUnsignedInt

  type uiDrawFillMode = CUnsignedInt

  type uiDrawMatrix = CStruct6[
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble
  ]

  type uiDrawBrush = CStruct12[
    uiDrawBrushType,
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    Ptr[uiDrawBrushGradientStop],
    CSize
  ]

  type uiDrawBrushGradientStop = CStruct5[
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble
  ]

  type uiDrawStrokeParams = CStruct7[
    uiDrawLineCap,
    uiDrawLineJoin,
    CDouble,
    CDouble,
    Ptr[CDouble],
    CSize,
    CDouble
  ]

  def uiDrawNewPath(fillMode: uiDrawFillMode): Ptr[uiDrawPath] = extern
  def uiDrawFreePath(p: Ptr[uiDrawPath]): Unit = extern
  def uiDrawPathNewFigure(p: Ptr[uiDrawPath], x: CDouble, y: CDouble): Unit =
    extern
  def uiDrawPathNewFigureWithArc(p: Ptr[uiDrawPath],
                                 xCenter: CDouble,
                                 yCenter: CDouble,
                                 radius: CDouble,
                                 startAngle: CDouble,
                                 sweep: CDouble,
                                 negative: CInt): Unit =
    extern
  def uiDrawPathLineTo(p: Ptr[uiDrawPath], x: CDouble, y: CDouble): Unit =
    extern
  def uiDrawPathArcTo(p: Ptr[uiDrawPath],
                      xCenter: CDouble,
                      yCenter: CDouble,
                      radius: CDouble,
                      startAngle: CDouble,
                      sweep: CDouble,
                      negative: CInt): Unit =
    extern
  def uiDrawPathBezierTo(p: Ptr[uiDrawPath],
                         c1x: CDouble,
                         c1y: CDouble,
                         c2x: CDouble,
                         c2y: CDouble,
                         endX: CDouble,
                         endY: CDouble): Unit = extern

  def uiDrawPathCloseFigure(p: Ptr[uiDrawPath]): Unit = extern

  def uiDrawPathAddRectangle(p: Ptr[uiDrawPath],
                             x: CDouble,
                             y: CDouble,
                             width: CDouble,
                             height: CDouble): Unit = extern
  def uiDrawPathEnd(p: Ptr[uiDrawPath]): Unit = extern

  def uiDrawStroke(c: Ptr[uiDrawContext],
                   path: Ptr[uiDrawPath],
                   b: Ptr[uiDrawBrush],
                   p: Ptr[uiDrawStrokeParams]): Unit = extern
  def uiDrawFill(c: Ptr[uiDrawContext],
                 path: Ptr[uiDrawPath],
                 b: Ptr[uiDrawBrush]): Unit = extern
  def uiDrawMatrixSetIdentity(m: Ptr[uiDrawMatrix]): Unit = extern
  def uiDrawMatrixTranslate(m: Ptr[uiDrawMatrix],
                            x: CDouble,
                            y: CDouble): Unit = extern
  def uiDrawMatrixScale(m: Ptr[uiDrawMatrix],
                        xCenter: CDouble,
                        yCenter: CDouble,
                        x: CDouble,
                        y: CDouble): Unit = extern
  def uiDrawMatrixTranslate(m: Ptr[uiDrawMatrix],
                            x: CDouble,
                            y: CDouble,
                            amount: CDouble): Unit = extern
  def uiDrawMatrixSkew(m: Ptr[uiDrawMatrix],
                       x: CDouble,
                       y: CDouble,
                       xamount: CDouble,
                       yamount: CDouble): Unit = extern
  def uiDrawMatrixMultiply(dest: Ptr[uiDrawMatrix],
                           src: Ptr[uiDrawMatrix]): Unit = extern
  def uiDrawMatrixInvertible(m: Ptr[uiDrawMatrix]): CInt = extern
  def uiDrawMatrixInvert(m: Ptr[uiDrawMatrix]): CInt = extern
  def uiDrawMatrixTransformPoint(m: Ptr[uiDrawMatrix],
                                 x: Ptr[CDouble],
                                 y: Ptr[CDouble]): Unit = extern
  def uiDrawMatrixTransformSize(m: Ptr[uiDrawMatrix],
                                x: Ptr[CDouble],
                                y: Ptr[CDouble]): Unit = extern

  def uiDrawTransform(c: Ptr[uiDrawContext], m: Ptr[uiDrawMatrix]): Unit =
    extern

  def uiDrawClip(c: Ptr[uiDrawContext], path: Ptr[uiDrawPath]): Unit = extern

  def uiDrawSave(c: Ptr[uiDrawContext]): Unit = extern
  def uiDrawRestore(c: Ptr[uiDrawContext]): Unit = extern

  type uiDrawFontFamilies = extern

  def uiDrawListFontFamilies(): Ptr[uiDrawFontFamilies] = extern
  def uiDrawFontFamiliesNumFamilies(ff: Ptr[uiDrawFontFamilies]): CInt = extern
  def uiDrawFontFamiliesFamily(ff: Ptr[uiDrawFontFamilies], n: CInt): CString =
    extern
  def uiDrawFreeFontFamilies(ff: Ptr[uiDrawFontFamilies]): Unit = extern

  type uiDrawTextLayout = extern
  type uiDrawTextFont = extern

  type uiDrawTextWeight = CUnsignedInt

  type uiDrawTextItalic = CUnsignedInt

  type uiDrawTextStretch = CUnsignedInt

  type uiDrawTextFontDescriptor = CStruct5[
    CString,
    CDouble,
    uiDrawTextWeight,
    uiDrawTextItalic,
    uiDrawTextStretch
  ]

  type uiDrawTextFontMetrics = CStruct5[
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CDouble
  ]

  def uiDrawLoadClosestFont(
      desc: Ptr[uiDrawTextFontDescriptor]): uiDrawTextFont = extern
  def uiDrawFreeTextFont(font: Ptr[uiDrawTextFont]): Unit = extern
  def uiDrawTextFontHandle(font: Ptr[uiDrawTextFont]): ULong = extern
  def uiDrawTextFontDescribe(font: Ptr[uiDrawTextFont],
                             desc: Ptr[uiDrawTextFontDescriptor]): Unit = extern
  def uiDrawTextFontGetMetrics(font: Ptr[uiDrawTextFont],
                               metrics: Ptr[uiDrawTextFontMetrics]): Unit =
    extern
  def uiDrawNewTextLayout(text: CString,
                          defaultFont: Ptr[uiDrawTextFont],
                          width: CDouble): Ptr[uiDrawTextLayout] = extern
  def uiDrawFreeTextLayout(layout: Ptr[uiDrawTextLayout]): Unit = extern
  def uiDrawTextLayoutSetWidth(layout: Ptr[uiDrawTextLayout],
                               width: CDouble): Unit = extern
  def uiDrawTextLayoutExtents(layout: Ptr[uiDrawTextLayout],
                              width: Ptr[CDouble],
                              height: Ptr[CDouble]): Unit = extern

  def uiDrawTextLayoutSetColor(layout: Ptr[uiDrawTextLayout],
                               startChar: CInt,
                               endChar: CInt,
                               r: CDouble,
                               g: CDouble,
                               b: CDouble,
                               a: CDouble): Unit = extern
  def uiDrawText(c: Ptr[uiDrawContext],
                 x: CDouble,
                 y: CDouble,
                 layout: Ptr[uiDrawTextLayout]): Unit = extern
  type uiModifiers = CUnsignedInt

  type uiAreaMouseEvent = CStruct9[
    CDouble,
    CDouble,
    CDouble,
    CDouble,
    CInt,
    CInt,
    CInt,
    uiModifiers,
    ULong
  ]

  type uiExtKey = CUnsignedInt

  type uiAreaKeyEvent = CStruct5[
    CChar,
    uiExtKey,
    uiModifiers,
    uiModifiers,
    CInt
  ]

  type uiFontButton = extern
  def uiFontButtonFont(b: Ptr[uiFontButton]): uiDrawTextFont = extern
  def uiFontButtonOnChanged(b: Ptr[uiFontButton],
                            f: CFunctionPtr1[Ptr[uiFontButton], Unit],
                            data: Ptr[Byte]): Unit = extern
  def uiNewFontButton(): Ptr[uiFontButton] = extern

  type uiColorButton = extern
  def uiColorButtonColor(b: Ptr[uiColorButton],
                         r: Ptr[CDouble],
                         g: Ptr[CDouble],
                         bl: Ptr[CDouble],
                         a: Ptr[CDouble]): Unit = extern
  def uiColorButtonSetColor(b: Ptr[uiColorButton],
                            r: CDouble,
                            g: CDouble,
                            bl: CDouble,
                            a: CDouble): Unit = extern
  def uiColorButtonOnChanged(b: Ptr[uiColorButton],
                             f: CFunctionPtr1[Ptr[uiColorButton], Unit],
                             data: Ptr[Byte]): Unit = extern
  def uiNewColorButton(): Ptr[uiColorButton] = extern

  type uiForm = extern

  def uiFormAppend(f: Ptr[uiForm],
                   label: CString,
                   c: Ptr[uiControl],
                   stretchy: CInt): Unit = extern
  def uiFormDelete(f: Ptr[uiForm], index: CInt): Unit = extern
  def uiFormPadded(f: Ptr[uiForm]): CInt = extern
  def uiFormSetPadded(f: Ptr[uiForm], padded: CInt): Unit = extern
  def uiNewForm(): Ptr[uiForm] = extern

  type uiAlign = CUnsignedInt

  type uiAt = CUnsignedInt

  type uiGrid = extern

  def uiGridAppend(
      g: Ptr[uiGrid],
      c: Ptr[uiControl],
      left: CInt,
      top: CInt,
      xspan: CInt,
      yspan: CInt,
      hexpand: CInt,
      halign: uiAlign,
      vexpand: CInt,
      valign: uiAlign
  ): Unit = extern
  def uiGridInsertAt(g: Ptr[uiGrid],
                     c: Ptr[uiControl],
                     existing: Ptr[uiControl],
                     at: uiAt,
                     xspan: CInt,
                     yspan: CInt,
                     hexpand: CInt,
                     halign: uiAlign,
                     vexpand: CInt,
                     valign: uiAlign): Unit = extern
  def uiGridPadded(g: Ptr[uiGrid]): CInt = extern
  def uiGridSetPadded(g: Ptr[uiGrid], padded: CInt): Unit = extern
  def uiNewGrid(): Ptr[uiGrid] = extern
}
object uiWindowResizeEdge {
  val uiWindowResizeEdgeLeft = 0
  val uiWindowResizeEdgeTop = 1
  val uiWindowResizeEdgeRight = 2
  val uiWindowResizeEdgeBottom = 3
  val uiWindowResizeEdgeTopLeft = 4
  val uiWindowResizeEdgeTopRight = 5
  val uiWindowResizeEdgeBottomLeft = 6
  val uiWindowResizeEdgeBottomRigh = 7
}

object uiAt {
  val uiAtLeading = 0
  val uiAtTop = 1
  val uiAtTrailing = 2
  val uiAtBottom = 3
}

object uiDrawBrushType {
  val uiDrawBrushTypeSolid = 0
  val uiDrawBrushTypeLinearGradient = 1
  val uiDrawBrushTypeRadialGradient = 2
  val uiDrawBrushTypeImage = 3
}

object uiDrawLineCap {
  val uiDrawLineCapFlat = 0
  val uiDrawLineCapRound = 1
  val uiDrawLineCapSquare = 2
}

object uiDrawLineJoin {
  val uiDrawLineJoinMiter = 0
  val uiDrawLineJoinRound = 1
  val uiDrawLineJoinBevel = 2
}

//val uiDrawDefaultMiterLimit = 10.0

object uiDrawFillMode {
  val uiDrawFillModeWinding = 0
  val uiDrawFillModeAlternate = 1
}

object uiDrawTextWeight {
  val uiDrawTextWeightThin = 0
  val uiDrawTextWeightUltraLight = 1
  val uiDrawTextWeightLight = 2
  val uiDrawTextWeightBook = 3
  val uiDrawTextWeightNormal = 4
  val uiDrawTextWeightMedium = 5
  val uiDrawTextWeightSemiBold = 6
  val uiDrawTextWeightBold = 7
  val uiDrawTextWeightUltraBold = 8
  val uiDrawTextWeightHeavy = 9
  val uiDrawTextWeightUltraHeavy = 10
}
object uiDrawTextItalic {
  val uiDrawTextItalicNormal = 0
  val uiDrawTextItalicOblique = 1
  val uiDrawTextItalicItalic = 2
}

object uiDrawTextStretch {
  val uiDrawTextStretchUltraCondensed = 0
  val uiDrawTextStretchExtraCondensed = 1
  val uiDrawTextStretchCondensed = 2
  val uiDrawTextStretchSemiCondensed = 3
  val uiDrawTextStretchNormal = 4
  val uiDrawTextStretchSemiExpanded = 5
  val uiDrawTextStretchExpanded = 6
  val uiDrawTextStretchExtraExpanded = 7
  val uiDrawTextStretchUltraExpanded = 8
}

object uiExtKey {
  private var i = 1
  private def index: CInt = { i += 1; i }

  val uiExtKeyEscape = index
  val uiExtKeyInsert = index // equivalent to "Help" on Apple keyboards
  val uiExtKeyDelete = index
  val uiExtKeyHome = index
  val uiExtKeyEnd = index
  val uiExtKeyPageUp = index
  val uiExtKeyPageDown = index
  val uiExtKeyUp = index
  val uiExtKeyDown = index
  val uiExtKeyLeft = index
  val uiExtKeyRight = index
  val uiExtKeyFindex = index // Findex..Findex2 are guaranteed to be consecutive
  val uiExtKeyF2 = index
  val uiExtKeyF3 = index
  val uiExtKeyF4 = index
  val uiExtKeyF5 = index
  val uiExtKeyF6 = index
  val uiExtKeyF7 = index
  val uiExtKeyF8 = index
  val uiExtKeyF9 = index
  val uiExtKeyF10 = index
  val uiExtKeyF11 = index
  val uiExtKeyF12 = index
  val uiExtKeyN0 = index // numpad keys; independent of Num Lock state
  val uiExtKeyN1 = index // N0..N9 are guaranteed to be consecutive
  val uiExtKeyN2 = index
  val uiExtKeyN3 = index
  val uiExtKeyN4 = index
  val uiExtKeyN5 = index
  val uiExtKeyN6 = index
  val uiExtKeyN7 = index
  val uiExtKeyN8 = index
  val uiExtKeyN9 = index
  val uiExtKeyNDot = index
  val uiExtKeyNEnter = index
  val uiExtKeyNAdd = index
  val uiExtKeyNSubtract = index
  val uiExtKeyNMultiply = index
  val uiExtKeyNDivide = index
}

object uiModifiers {
  val uiModifierCtrl = 1 << 0
  val uiModifierAlt = 1 << 1
  val uiModifierShift = 1 << 2
  val uiModifierSuper = 1 << 3
}

object uiAlign {
  val uiAlignFill = 0
  val uiAlignStart = 1
  val uiAlignCenter = 2
  val uiAlignEnd = 3
}

object uiAreaHandler {

}
