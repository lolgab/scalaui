package scalaui

import scala.scalanative.unsafe.{
  CChar,
  CDouble,
  CFuncPtr0,
  CFuncPtr1,
  CFuncPtr2,
  CFuncPtr3,
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
  extern,
  _
}
import scala.scalanative.unsigned._

@link("ui")
@extern
object ui {
  type uiForEach = CUnsignedInt
  type uiInitOptions = CStruct1[CSize]

  def uiInit(options: Ptr[uiInitOptions]): CString = extern
  def uiUninit(): Unit = extern
  def uiFreeInitError(err: CString): Unit = extern

  def uiMain(): Unit = extern
  def uiMainSteps(): Unit = extern
  def uiMainStep(wait: CInt): CInt = extern
  def uiQuit(): Unit = extern

  def uiQueueMain(f: CFuncPtr1[Ptr[Byte], Unit], data: Ptr[Byte]): Unit =
    extern

  def uiOnShouldQuit(f: CFuncPtr1[Ptr[Byte], CInt], data: Ptr[Byte]): Unit =
    extern

  def uiFreeText(text: CString): Unit = extern

  type uiControl = extern
  /*
  type uiControl = CStruct14[
    UInt,
    UInt,
    UInt,
    CFuncPtr1[Ptr[uiControl], Unit],
    CFuncPtr1[Ptr[uiControl], ULong],
    CFuncPtr1[Ptr[uiControl], Ptr[uiControl]],
    CFuncPtr2[Ptr[uiControl], Ptr[uiControl], Unit],
    CFuncPtr1[Ptr[uiControl], CInt],
    CFuncPtr1[Ptr[uiControl], CInt],
    CFuncPtr1[Ptr[uiControl], Unit],
    CFuncPtr1[Ptr[uiControl], Unit],
    CFuncPtr1[Ptr[uiControl], CInt],
    CFuncPtr1[Ptr[uiControl], Unit],
    CFuncPtr1[Ptr[uiControl], Unit]
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

  def uiAllocControl(
      n: CSize,
      OSsig: UInt,
      typesig: UInt,
      typenamestr: CString
  ): Ptr[uiControl] =
    extern
  def uiFreeControl(uc: Ptr[uiControl]): Unit = extern

  def uiControlVerifySetParent(
      uc: Ptr[uiControl],
      parent: Ptr[uiControl]
  ): Unit = extern
  def uiControlEnabledToUser(uc: Ptr[uiControl]): CInt = extern

  def uiUserBugCannotSetParentOnToplevel(`type`: CString): Unit = extern

  type uiWindow = extern
  def uiWindowTitle(w: Ptr[uiWindow]): CString = extern
  def uiWindowSetTitle(w: Ptr[uiWindow], title: CString): Unit = extern
  def uiWindowContentSize(
      w: Ptr[uiWindow],
      width: Ptr[CInt],
      height: Ptr[CInt]
  ): Unit = extern
  def uiWindowSetContentSize(
      w: Ptr[uiWindow],
      width: CInt,
      height: CInt
  ): Unit = extern
  def uiWindowFullscreen(w: Ptr[uiWindow]): CInt = extern
  def uiWindowSetFullscreen(w: Ptr[uiWindow], fullscreen: CInt): Unit = extern
  def uiWindowOnContentSizeChanged(
      w: Ptr[uiWindow],
      f: CFuncPtr2[Ptr[uiWindow], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
  def uiWindowOnClosing(
      w: Ptr[uiWindow],
      f: CFuncPtr2[Ptr[uiWindow], Ptr[Byte], CInt],
      data: Ptr[Byte]
  ): Unit =
    extern
  def uiWindowBorderless(w: Ptr[uiWindow]): CInt = extern
  def uiWindowSetBorderless(w: Ptr[uiWindow], borderless: CInt): Unit = extern
  def uiWindowSetChild(w: Ptr[uiWindow], child: Ptr[uiControl]): Unit = extern
  def uiWindowMargined(w: Ptr[uiWindow]): CInt = extern
  def uiWindowSetMargined(w: Ptr[uiWindow], margined: CInt): Unit = extern
  def uiNewWindow(
      title: CString,
      width: CInt,
      height: CInt,
      hasMenubar: CInt
  ): Ptr[uiWindow] =
    extern

  type uiButton = extern
  def uiButtonText(b: Ptr[uiButton]): CString = extern
  def uiButtonSetText(b: Ptr[uiButton], text: CString): Unit = extern
  def uiButtonOnClicked(
      b: Ptr[uiButton],
      f: CFuncPtr2[Ptr[uiButton], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern

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
  def uiCheckboxOnToggled(
      c: Ptr[uiCheckbox],
      f: CFuncPtr2[uiCheckbox, Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern

  def uiCheckboxChecked(c: Ptr[uiCheckbox]): CInt = extern
  def uiCheckboxSetChecked(c: Ptr[uiCheckbox], checked: CInt): Unit = extern
  def uiNewCheckbox(text: CString): Ptr[uiCheckbox] = extern

  type uiEntry = extern
  def uiEntryText(e: Ptr[uiEntry]): CString = extern
  def uiEntrySetText(e: Ptr[uiEntry], text: CString): Unit = extern
  def uiEntryOnChanged(
      e: Ptr[uiEntry],
      f: CFuncPtr2[Ptr[uiEntry], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
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
  def uiTabInsertAt(
      t: Ptr[uiTab],
      name: CString,
      before: CInt,
      c: Ptr[uiControl]
  ): Unit = extern
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
  def uiSpinboxOnChanged(
      s: Ptr[uiSpinbox],
      f: CFuncPtr2[Ptr[uiSpinbox], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
  def uiNewSpinbox(min: CInt, max: CInt): Ptr[uiSpinbox] = extern

  type uiSlider = extern
  def uiSliderValue(s: Ptr[uiSlider]): CInt = extern
  def uiSliderSetValue(s: Ptr[uiSlider], value: CInt): Unit = extern
  def uiSliderOnChanged(
      s: Ptr[uiSlider],
      f: CFuncPtr2[Ptr[uiSlider], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
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
  def uiComboboxOnSelected(
      c: Ptr[uiCombobox],
      f: CFuncPtr2[Ptr[uiCombobox], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
  def uiNewCombobox(): Ptr[uiCombobox] = extern

  type uiEditableCombobox = extern
  def uiEditableComboboxAppend(
      c: Ptr[uiEditableCombobox],
      text: CString
  ): Unit = extern
  def uiEditableComboboxText(c: Ptr[uiEditableCombobox]): CString = extern
  def uiEditableComboboxSetText(
      c: Ptr[uiEditableCombobox],
      text: CString
  ): Unit = extern
  def uiEditableComboboxOnChanged(
      c: Ptr[uiEditableCombobox],
      f: CFuncPtr2[Ptr[uiEditableCombobox], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
  def uiNewEditableCombobox(): Ptr[uiEditableCombobox] = extern

  type uiRadioButtons = extern
  def uiRadioButtonsAppend(r: Ptr[uiRadioButtons], text: CString): Unit = extern
  def uiRadioButtonsSelected(r: Ptr[uiRadioButtons]): CInt = extern
  def uiRadioButtonsSetSelected(r: Ptr[uiRadioButtons], n: CInt): Unit = extern
  def uiRadioButtonsOnSelected(
      r: Ptr[uiRadioButtons],
      f: CFuncPtr2[Ptr[uiRadioButtons], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
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
      f: CFuncPtr2[Ptr[uiMultilineEntry], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
  def uiMultilineEntryReadOnly(e: Ptr[uiMultilineEntry]): CInt = extern
  def uiMultilineEntrySetReadOnly(
      e: Ptr[uiMultilineEntry],
      readonly: CInt
  ): Unit = extern
  def uiNewMultilineEntry(): Ptr[uiMultilineEntry] = extern
  def uiNewNonWrappingMultilineEntry(): Ptr[uiMultilineEntry] = extern

  type uiMenuItem = extern
  def uiMenuItemEnable(m: Ptr[uiMenuItem]): Unit = extern
  def uiMenuItemDisable(m: Ptr[uiMenuItem]): Unit = extern
  def uiMenuItemOnClicked(
      m: Ptr[uiMenuItem],
      f: CFuncPtr3[Ptr[uiMenuItem], Ptr[uiWindow], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit = extern
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
  def uiMsgBox(
      parent: Ptr[uiWindow],
      title: CString,
      description: CString
  ): Unit = extern
  def uiMsgBoxError(
      parent: Ptr[uiWindow],
      title: CString,
      description: CString
  ): Unit = extern

  type uiWindowResizeEdge = CUnsignedInt

  type uiArea = extern

  type _uiDrawCallback =
    CFuncPtr3[Ptr[Byte], Ptr[uiArea], Ptr[uiAreaDrawParams], Unit]
  type _uiMouseEventCallback =
    CFuncPtr3[Ptr[Byte], Ptr[uiArea], Ptr[uiAreaMouseEvent], Unit]
  type _uiMouseCrossedCallback = CFuncPtr3[Ptr[Byte], Ptr[uiArea], CInt, Unit] //unsafe Int boolean conversion
  type _uiDragBrokenCallback = CFuncPtr2[Ptr[Byte], Ptr[uiArea], Unit]
  type _uiKeyEventCallback =
    CFuncPtr3[Ptr[Byte], Ptr[uiArea], Ptr[uiAreaKeyEvent], CInt]

  type uiAreaHandler =
    CStruct5[ //Ptr[Byte] need cast[Ptr[uiAreaHandler]]
      _uiDrawCallback,
      _uiMouseEventCallback,
      _uiMouseCrossedCallback,
      _uiDragBrokenCallback,
      _uiKeyEventCallback
    ]

  def uiAreaSetSize(a: Ptr[uiArea], width: CInt, height: CInt): Unit = extern
  def uiAreaQueueRedrawAll(a: Ptr[uiArea]): Unit = extern
  def uiAreaScrollTo(
      a: Ptr[uiArea],
      x: CDouble,
      y: CDouble,
      width: CDouble,
      height: CDouble
  ): Unit = extern
  //TODO
  def uiAreaBeginUserWindowMove(a: Ptr[uiArea]): Unit = extern
  def uiAreaBeginUserWindowResize(
      a: Ptr[uiArea],
      edge: uiWindowResizeEdge
  ): Unit = extern
  def uiNewArea(ah: Ptr[uiAreaHandler]): Ptr[uiArea] = extern
  def uiNewScrollingArea(
      ah: Ptr[uiAreaHandler],
      width: Int,
      height: Int
  ): Ptr[uiArea] = extern

  type uiDrawContext = extern

  type uiAreaDrawParams =
    CStruct7[Ptr[uiDrawContext], CDouble, CDouble, CDouble, CDouble, CDouble, CDouble]

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
  def uiDrawPathNewFigureWithArc(
      p: Ptr[uiDrawPath],
      xCenter: CDouble,
      yCenter: CDouble,
      radius: CDouble,
      startAngle: CDouble,
      sweep: CDouble,
      negative: CInt
  ): Unit =
    extern
  def uiDrawPathLineTo(p: Ptr[uiDrawPath], x: CDouble, y: CDouble): Unit =
    extern
  def uiDrawPathArcTo(
      p: Ptr[uiDrawPath],
      xCenter: CDouble,
      yCenter: CDouble,
      radius: CDouble,
      startAngle: CDouble,
      sweep: CDouble,
      negative: CInt
  ): Unit =
    extern
  def uiDrawPathBezierTo(
      p: Ptr[uiDrawPath],
      c1x: CDouble,
      c1y: CDouble,
      c2x: CDouble,
      c2y: CDouble,
      endX: CDouble,
      endY: CDouble
  ): Unit = extern

  def uiDrawPathCloseFigure(p: Ptr[uiDrawPath]): Unit = extern

  def uiDrawPathAddRectangle(
      p: Ptr[uiDrawPath],
      x: CDouble,
      y: CDouble,
      width: CDouble,
      height: CDouble
  ): Unit = extern
  def uiDrawPathEnd(p: Ptr[uiDrawPath]): Unit = extern

  def uiDrawStroke(
      c: Ptr[uiDrawContext],
      path: Ptr[uiDrawPath],
      b: Ptr[uiDrawBrush],
      p: Ptr[uiDrawStrokeParams]
  ): Unit = extern
  def uiDrawFill(
      c: Ptr[uiDrawContext],
      path: Ptr[uiDrawPath],
      b: Ptr[uiDrawBrush]
  ): Unit = extern
  def uiDrawMatrixSetIdentity(m: Ptr[uiDrawMatrix]): Unit = extern
  def uiDrawMatrixTranslate(
      m: Ptr[uiDrawMatrix],
      x: CDouble,
      y: CDouble
  ): Unit = extern
  def uiDrawMatrixScale(
      m: Ptr[uiDrawMatrix],
      xCenter: CDouble,
      yCenter: CDouble,
      x: CDouble,
      y: CDouble
  ): Unit = extern
  def uiDrawMatrixTranslate(
      m: Ptr[uiDrawMatrix],
      x: CDouble,
      y: CDouble,
      amount: CDouble
  ): Unit =
    extern
  def uiDrawMatrixSkew(
      m: Ptr[uiDrawMatrix],
      x: CDouble,
      y: CDouble,
      xamount: CDouble,
      yamount: CDouble
  ): Unit = extern
  def uiDrawMatrixMultiply(
      dest: Ptr[uiDrawMatrix],
      src: Ptr[uiDrawMatrix]
  ): Unit = extern
  def uiDrawMatrixInvertible(m: Ptr[uiDrawMatrix]): CInt = extern
  def uiDrawMatrixInvert(m: Ptr[uiDrawMatrix]): CInt = extern
  def uiDrawMatrixTransformPoint(
      m: Ptr[uiDrawMatrix],
      x: Ptr[CDouble],
      y: Ptr[CDouble]
  ): Unit =
    extern
  def uiDrawMatrixTransformSize(
      m: Ptr[uiDrawMatrix],
      x: Ptr[CDouble],
      y: Ptr[CDouble]
  ): Unit =
    extern

  def uiDrawTransform(c: Ptr[uiDrawContext], m: Ptr[uiDrawMatrix]): Unit =
    extern

  def uiDrawClip(c: Ptr[uiDrawContext], path: Ptr[uiDrawPath]): Unit = extern

  def uiDrawSave(c: Ptr[uiDrawContext]): Unit = extern
  def uiDrawRestore(c: Ptr[uiDrawContext]): Unit = extern

  type uiAttribute = extern
  def uiFreeAttribute(a: Ptr[uiAttribute]): Unit = extern
  type uiAttributeType = CUnsignedInt

  def uiAttributeGetType(a: Ptr[uiAttribute]): uiAttributeType = extern
  def uiNewFamilyAttribute(family: CString): Ptr[uiAttribute] = extern
  def uiAttributeFamily(a: Ptr[uiAttribute]): CString = extern
  def uiNewSizeAttribute(size: CDouble): Ptr[uiAttribute] = extern
  def uiAttributeSize(a: Ptr[uiAttribute]): CDouble = extern
  type uiTextWeight = CUnsignedInt

  def uiNewWeightAttribute(weight: uiTextWeight): Ptr[uiAttribute] = extern
  def uiAttributeWeight(a: Ptr[uiAttribute]): uiTextWeight = extern
  type uiTextItalic = CUnsignedInt

  def uiNewItalicAttribute(italic: uiTextItalic): Ptr[uiAttribute] = extern
  def uiAttributeItalic(a: Ptr[uiAttribute]): uiTextItalic = extern
  type uiTextStretch = CUnsignedInt

  def uiNewStretchAttribute(stretch: uiTextStretch): Ptr[uiAttribute] = extern
  def uiAttributeStretch(a: Ptr[uiAttribute]): uiTextStretch = extern
  def uiNewColorAttribute(
      r: CDouble,
      g: CDouble,
      b: CDouble,
      a: CDouble
  ): Ptr[uiAttribute] = extern
  def uiAttributeColor(
      a: Ptr[uiAttribute],
      r: Ptr[CDouble],
      g: Ptr[CDouble],
      b: Ptr[CDouble],
      alpha: Ptr[CDouble]
  ): Unit = extern
  def uiNewBackgroundAttribute(
      r: CDouble,
      g: CDouble,
      b: CDouble,
      a: CDouble
  ): Ptr[uiAttribute] =
    extern
  type uiUnderline = CUnsignedInt

  def uiNewUnderlineAttribute(u: uiUnderline): Ptr[uiAttribute] = extern
  def uiAttributeUnderline(a: Ptr[uiAttribute]): uiUnderline = extern
  type uiUnderlineColor = CUnsignedInt

  def uiNewUnderlineColorAttribute(
      u: uiUnderlineColor,
      r: CDouble,
      g: CDouble,
      b: CDouble,
      a: CDouble
  ): Ptr[uiAttribute] = extern
  def uiAttributeUnderlineColor(
      a: Ptr[uiAttribute],
      u: Ptr[uiUnderlineColor],
      r: Ptr[CDouble],
      g: Ptr[CDouble],
      b: Ptr[CDouble],
      alpha: Ptr[CDouble]
  ): Unit = extern
  type uiDrawTextAlign = CUnsignedInt

  type uiOpenTypeFeatures = extern
  type uiOpenTypeFeaturesForEachFunc =
    CFuncPtr7[Ptr[uiOpenTypeFeatures], CChar, CChar, CChar, CChar, UInt, Ptr[
      Byte
    ], uiForEach]
  def uiNewOpenTypeFeatures: Ptr[uiOpenTypeFeatures] = extern
  def uiFreeOpenTypeFeatures(otf: Ptr[uiOpenTypeFeatures]): Unit = extern
  def uiOpenTypeFeaturesClone(
      otf: Ptr[uiOpenTypeFeatures]
  ): Ptr[uiOpenTypeFeatures] = extern
  def uiOpenTypeFeaturesAdd(
      otf: Ptr[uiOpenTypeFeatures],
      a: CChar,
      b: CChar,
      c: CChar,
      d: CChar,
      value: UInt
  ): Unit = extern
  def uiOpenTypeFeaturesRemove(
      otf: Ptr[uiOpenTypeFeatures],
      a: CChar,
      b: CChar,
      c: CChar,
      d: CChar
  ): Unit = extern
  def uiOpenTypeFeaturesGet(
      otf: Ptr[uiOpenTypeFeatures],
      a: CChar,
      b: CChar,
      c: CChar,
      d: CChar,
      value: Ptr[UInt]
  ): CInt = extern
  def uiOpenTypeFeaturesForEach(
      otf: Ptr[uiOpenTypeFeatures],
      f: uiOpenTypeFeaturesForEachFunc,
      data: Ptr[Byte]
  ): Unit = extern
  def uiNewFeaturesAttribute(otf: Ptr[uiOpenTypeFeatures]): Ptr[uiAttribute] =
    extern
  def uiAttributeFeatures(a: Ptr[uiAttribute]): Ptr[uiOpenTypeFeatures] = extern
  type uiAttributedString = extern
  type uiAttributedStringForEachAttributeFunc =
    CFuncPtr5[Ptr[uiAttributedString], Ptr[uiAttribute], CSize, CSize, Ptr[
      Byte
    ], uiForEach]
  def uiNewAttributedString(initialString: CString): Ptr[uiAttributedString] =
    extern
  def uiFreeAttributedString(s: Ptr[uiAttributedString]): Unit = extern
  def uiAttributedStringString(s: Ptr[uiAttributedString]): CString = extern
  def uiAttributedStringLen(s: Ptr[uiAttributedString]): CSize = extern
  def uiAttributedStringAppendUnattributed(
      s: Ptr[uiAttributedString],
      str: CString
  ): Unit = extern
  def uiAttributedStringInsertAtUnattributed(
      s: Ptr[uiAttributedString],
      str: CString,
      at: CSize
  ): Unit = extern
  def uiAttributedStringDelete(
      s: Ptr[uiAttributedString],
      start: CSize,
      end: CSize
  ): Unit = extern
  def uiAttributedStringSetAttribute(
      s: Ptr[uiAttributedString],
      a: Ptr[uiAttribute],
      start: CSize,
      end: CSize
  ): Unit = extern
  def uiAttributedStringForEachAttribute(
      s: Ptr[uiAttributedString],
      f: uiAttributedStringForEachAttributeFunc,
      data: Ptr[Byte]
  ): Unit = extern
  def uiAttributedStringNumGraphemes(s: Ptr[uiAttributedString]): CSize = extern
  def uiAttributedStringByteIndexToGrapheme(
      s: Ptr[uiAttributedString],
      pos: CSize
  ): CSize = extern
  def uiAttributedStringGraphemeToByteIndex(
      s: Ptr[uiAttributedString],
      pos: CSize
  ): CSize = extern

  type uiFontDescriptor = CStruct5[
    CString,
    CDouble,
    uiTextWeight,
    uiTextItalic,
    uiTextStretch
  ]

  type uiDrawTextLayout = extern

  type uiDrawTextLayoutParams = CStruct4[
    Ptr[uiAttributedString],
    Ptr[uiFontDescriptor],
    CDouble,
    uiDrawTextAlign
  ]

  def uiDrawNewTextLayout(
      params: Ptr[uiDrawTextLayoutParams]
  ): Ptr[uiDrawTextLayout] =
    extern
  def uiDrawFreeTextLayout(tl: Ptr[uiDrawTextLayout]): Unit = extern
  def uiDrawText(
      c: Ptr[uiDrawContext],
      layout: Ptr[uiDrawTextLayout],
      x: CDouble,
      y: CDouble
  ): Unit = extern
  def uiDrawTextLayoutExtents(
      tl: Ptr[uiDrawTextLayout],
      width: Ptr[CDouble],
      height: Ptr[CDouble]
  ): Unit = extern

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
  def uiFontButtonFont(
      b: Ptr[uiFontButton],
      desc: Ptr[uiFontDescriptor]
  ): Unit = extern
  def uiFontButtonOnChanged(
      b: Ptr[uiFontButton],
      f: CFuncPtr2[Ptr[uiFontButton], Ptr[Byte], Unit],
      data: Ptr[Byte]
  ): Unit =
    extern
  def uiNewFontButton(): Ptr[uiFontButton] = extern
  def uiFreeFontButtonFont(desc: Ptr[uiFontDescriptor]): Unit = extern

  type uiColorButton = extern
  def uiColorButtonColor(
      b: Ptr[uiColorButton],
      r: Ptr[CDouble],
      g: Ptr[CDouble],
      bl: Ptr[CDouble],
      a: Ptr[CDouble]
  ): Unit = extern
  def uiColorButtonSetColor(
      b: Ptr[uiColorButton],
      r: CDouble,
      g: CDouble,
      bl: CDouble,
      a: CDouble
  ): Unit = extern
  def uiColorButtonOnChanged(
      b: Ptr[uiColorButton],
      f: CFuncPtr1[Ptr[uiColorButton], Unit],
      data: Ptr[Byte]
  ): Unit =
    extern
  def uiNewColorButton(): Ptr[uiColorButton] = extern

  type uiForm = extern

  def uiFormAppend(
      f: Ptr[uiForm],
      label: CString,
      c: Ptr[uiControl],
      stretchy: CInt
  ): Unit = extern
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
  def uiGridInsertAt(
      g: Ptr[uiGrid],
      c: Ptr[uiControl],
      existing: Ptr[uiControl],
      at: uiAt,
      xspan: CInt,
      yspan: CInt,
      hexpand: CInt,
      halign: uiAlign,
      vexpand: CInt,
      valign: uiAlign
  ): Unit = extern
  def uiGridPadded(g: Ptr[uiGrid]): CInt = extern
  def uiGridSetPadded(g: Ptr[uiGrid], padded: CInt): Unit = extern
  def uiNewGrid(): Ptr[uiGrid] = extern
}
object uiOps {
  import ui._

  type uiDrawCallback =
    CFuncPtr3[Ptr[uiAreaHandler], Ptr[uiArea], Ptr[uiAreaDrawParams], Unit]
  type uiMouseEventCallback =
    CFuncPtr3[Ptr[uiAreaHandler], Ptr[uiArea], Ptr[uiAreaMouseEvent], Unit]
  type uiMouseCrossedCallback =
    CFuncPtr3[Ptr[uiAreaHandler], Ptr[uiArea], CInt, Unit]
  type uiDragBrokenCallback = CFuncPtr2[Ptr[uiAreaHandler], Ptr[uiArea], Unit]
  type uiKeyEventCallback =
    CFuncPtr3[Ptr[uiAreaHandler], Ptr[uiArea], Ptr[uiAreaKeyEvent], CInt]

  implicit class uiAreaHandlerOps(val ptr: Ptr[uiAreaHandler]) extends AnyVal {
    def Draw: uiDrawCallback = ptr._1.asInstanceOf[uiDrawCallback]
    def MouseEvent: uiMouseEventCallback =
      ptr._2.asInstanceOf[uiMouseEventCallback]
    def MouseCrossed: uiMouseCrossedCallback =
      ptr._3.asInstanceOf[uiMouseCrossedCallback]
    def DragBroken: uiDragBrokenCallback =
      ptr._4.asInstanceOf[uiDragBrokenCallback]
    def KeyEvent: uiKeyEventCallback = ptr._5.asInstanceOf[uiKeyEventCallback]

    def Draw_=(v: uiDrawCallback): Unit =
      ptr._1 = v.asInstanceOf[_uiDrawCallback]
    def MouseEvent_=(v: uiMouseEventCallback): Unit =
      ptr._2 = v.asInstanceOf[_uiMouseEventCallback]
    def MouseCrossed_=(v: uiMouseCrossedCallback): Unit =
      ptr._3 = v.asInstanceOf[_uiMouseCrossedCallback]
    def DragBroken_=(v: uiDragBrokenCallback): Unit =
      ptr._4 = v.asInstanceOf[_uiDragBrokenCallback]
    def KeyEvent_=(v: uiKeyEventCallback): Unit =
      ptr._5 = v.asInstanceOf[_uiKeyEventCallback]
  }

  implicit class uiAreaDrawParamsOps(val ptr: Ptr[uiAreaDrawParams])
      extends AnyVal {
    def Context: Ptr[uiDrawContext] = ptr._1
    def AreaWidth: CDouble = ptr._2
    def AreaHeight: CDouble = ptr._3
    def ClipX: CDouble = ptr._4
    def ClipY: CDouble = ptr._5
    def ClipWidth: CDouble = ptr._6
    def ClipHeight: CDouble = ptr._7

    def Context_=(v: Ptr[uiDrawContext]): Unit = ptr._1 = v
    def AreaWidth_=(v: CDouble): Unit = ptr._2 = v
    def AreaHeight_=(v: CDouble): Unit = ptr._3 = v
    def ClipX_=(v: CDouble): Unit = ptr._4 = v
    def ClipY_=(v: CDouble): Unit = ptr._5 = v
    def ClipWidth_=(v: CDouble): Unit = ptr._6 = v
    def ClipHeight_=(v: CDouble): Unit = ptr._7 = v
  }

  implicit class uiDrawMatrixOps(val ptr: Ptr[uiDrawMatrix]) extends AnyVal {
    def M11: CDouble = ptr._1
    def M12: CDouble = ptr._2
    def M21: CDouble = ptr._3
    def M22: CDouble = ptr._4
    def M31: CDouble = ptr._5
    def M32: CDouble = ptr._6

    def M11_=(v: CDouble): Unit = ptr._1 = v
    def M12_=(v: CDouble): Unit = ptr._2 = v
    def M21_=(v: CDouble): Unit = ptr._3 = v
    def M22_=(v: CDouble): Unit = ptr._4 = v
    def M31_=(v: CDouble): Unit = ptr._5 = v
    def M32_=(v: CDouble): Unit = ptr._6 = v
  }

  implicit class uiDrawBrushOps(val ptr: Ptr[uiDrawBrush]) extends AnyVal {
    def Type: uiDrawBrushType = ptr._1
    def R: CDouble = ptr._2
    def G: CDouble = ptr._3
    def B: CDouble = ptr._4
    def A: CDouble = ptr._5

    def X0: CDouble = ptr._6
    def Y0: CDouble = ptr._7
    def X1: CDouble = ptr._8
    def Y1: CDouble = ptr._9
    def OuterRadius: CDouble = ptr._10
    def Stops: Ptr[uiDrawBrushGradientStop] = ptr._11
    def NumStops: CSize = ptr._12

    def Type_=(v: uiDrawBrushType): Unit = ptr._1 = v
    def R_=(v: CDouble): Unit = ptr._2 = v
    def G_=(v: CDouble): Unit = ptr._3 = v
    def B_=(v: CDouble): Unit = ptr._4 = v
    def A_=(v: CDouble): Unit = ptr._5 = v
    def X0_=(v: CDouble): Unit = ptr._6 = v
    def Y0_=(v: CDouble): Unit = ptr._7 = v
    def X1_=(v: CDouble): Unit = ptr._8 = v
    def Y1_=(v: CDouble): Unit = ptr._9 = v
    def OuterRadius_=(v: CDouble): Unit = ptr._10 = v
    def Stops_=(v: Ptr[uiDrawBrushGradientStop]): Unit = ptr._11 = v
    def NumStops_=(v: CSize): Unit = ptr._12 = v
  }

  implicit class uiDrawBrushGradientStopOps(
      val ptr: Ptr[uiDrawBrushGradientStop]
  ) extends AnyVal {
    def Pos: CDouble = ptr._1
    def R: CDouble = ptr._2
    def G: CDouble = ptr._3
    def B: CDouble = ptr._4
    def A: CDouble = ptr._5

    def Pos_=(v: CDouble): Unit = ptr._1 = v
    def R_=(v: CDouble): Unit = ptr._2 = v
    def G_=(v: CDouble): Unit = ptr._3 = v
    def B_=(v: CDouble): Unit = ptr._4 = v
    def A_=(v: CDouble): Unit = ptr._5 = v
  }

  implicit class uiDrawStrokeParamsOps(val ptr: Ptr[uiDrawStrokeParams])
      extends AnyVal {
    def Cap: uiDrawLineCap = ptr._1
    def Join: uiDrawLineJoin = ptr._2
    def Thickness: CDouble = ptr._3
    def MiterLimit: CDouble = ptr._4
    def Dashes: Ptr[CDouble] = ptr._5
    def NumDashes: CSize = ptr._6
    def DashPhase: CDouble = ptr._7

    def Cap_=(v: uiDrawLineCap): Unit = ptr._1 = v
    def Join_=(v: uiDrawLineJoin): Unit = ptr._2 = v
    def Thickness_=(v: CDouble): Unit = ptr._3 = v
    def MiterLimit_=(v: CDouble): Unit = ptr._4 = v
    def Dashes_=(v: Ptr[CDouble]): Unit = ptr._5 = v
    def NumDashes_=(v: CSize): Unit = ptr._6 = v
    def DashPhase_=(v: CDouble): Unit = ptr._7 = v
  }

  implicit class uiFontDescriptorOps(val ptr: Ptr[uiFontDescriptor])
      extends AnyVal {
    def Family: CString = ptr._1
    def Size: CDouble = ptr._2
    def Weight: uiTextWeight = ptr._3
    def Italic: uiTextItalic = ptr._4
    def Stretch: uiTextStretch = ptr._5

    def Family_=(v: CString): Unit = ptr._1 = v
    def Size_=(v: CDouble): Unit = ptr._2 = v
    def Weight_=(v: uiTextWeight): Unit = ptr._3 = v
    def Italic_=(v: uiTextItalic): Unit = ptr._4 = v
    def Stretch_=(v: uiTextStretch): Unit = ptr._5 = v
  }

  implicit class uiDrawTextLayoutParamsOps(val ptr: Ptr[uiDrawTextLayoutParams])
      extends AnyVal {
    def String: Ptr[uiAttributedString] = ptr._1
    def DefaultFont: Ptr[uiFontDescriptor] = ptr._2
    def Width: CDouble = ptr._3
    def Align: uiDrawTextAlign = ptr._4

    def String_=(v: Ptr[uiAttributedString]): Unit = ptr._1 = v
    def DefaultFont_=(v: Ptr[uiFontDescriptor]): Unit = ptr._2 = v
    def Width_=(v: CDouble): Unit = ptr._3 = v
    def Align_=(v: uiDrawTextAlign): Unit = ptr._4 = v
  }

  implicit class uiAreaMouseEventOps(val ptr: Ptr[uiAreaMouseEvent])
      extends AnyVal {
    def X: CDouble = ptr._1
    def Y: CDouble = ptr._2
    def AreaWidth: CDouble = ptr._3
    def AreaHeight: CDouble = ptr._4
    def Down: CInt = ptr._5
    def Up: CInt = ptr._6
    def Count: CInt = ptr._7
    def Modifiers: uiModifiers = ptr._8
    def Held1To64: ULong = ptr._9

    def X_=(v: CDouble): Unit = ptr._1 = v
    def Y_=(v: CDouble): Unit = ptr._2 = v
    def AreaWidth_=(v: CDouble): Unit = ptr._3 = v
    def AreaHeight_=(v: CDouble): Unit = ptr._4 = v
    def Down_=(v: CInt): Unit = ptr._5 = v
    def Up_=(v: CInt): Unit = ptr._6 = v
    def Count_=(v: CInt): Unit = ptr._7 = v
    def Modifiers_=(v: uiModifiers): Unit = ptr._8 = v
    def Held1To64_=(v: ULong): Unit = ptr._9 = v
  }

  implicit class uiAreaKeyEventOps(val ptr: Ptr[uiAreaKeyEvent])
      extends AnyVal {
    def Key: CChar = ptr._1
    def ExtKey: uiExtKey = ptr._2
    def Modifier: uiModifiers = ptr._3
    def Modifiers: uiModifiers = ptr._4
    def Up: CInt = ptr._5

    def Key_=(v: CChar): Unit = ptr._1 = v
    def ExtKey_=(v: uiExtKey): Unit = ptr._2 = v
    def Modifier_=(v: uiModifiers): Unit = ptr._3 = v
    def Modifiers_=(v: uiModifiers): Unit = ptr._4 = v
    def Up_=(v: CInt): Unit = ptr._5 = v
  }

  object uiForEach {
    val uiForEachContinue: UInt = 0.toUInt
    val uiForEachStop: UInt = 1.toUInt
  }

  object uiWindowResizeEdge {
    val uiWindowResizeEdgeLeft: UInt = 0.toUInt
    val uiWindowResizeEdgeTop: UInt = 1.toUInt
    val uiWindowResizeEdgeRight: UInt = 2.toUInt
    val uiWindowResizeEdgeBottom: UInt = 3.toUInt
    val uiWindowResizeEdgeTopLeft: UInt = 4.toUInt
    val uiWindowResizeEdgeTopRight: UInt = 5.toUInt
    val uiWindowResizeEdgeBottomLeft: UInt = 6.toUInt
    val uiWindowResizeEdgeBottomRight: UInt = 7.toUInt
  }

  object uiAt {
    val uiAtLeading: UInt = 0.toUInt
    val uiAtTop: UInt = 1.toUInt
    val uiAtTrailing: UInt = 2.toUInt
    val uiAtBottom: UInt = 3.toUInt
  }

  object uiDrawBrushType {
    val uiDrawBrushTypeSolid: UInt = 0.toUInt
    val uiDrawBrushTypeLinearGradient: UInt = 1.toUInt
    val uiDrawBrushTypeRadialGradient: UInt = 2.toUInt
    val uiDrawBrushTypeImage: UInt = 3.toUInt
  }

  object uiDrawLineCap {
    val uiDrawLineCapFlat: UInt = 0.toUInt
    val uiDrawLineCapRound: UInt = 1.toUInt
    val uiDrawLineCapSquare: UInt = 2.toUInt
  }

  object uiDrawLineJoin {
    val uiDrawLineJoinMiter: UInt = 0.toUInt
    val uiDrawLineJoinRound: UInt = 1.toUInt
    val uiDrawLineJoinBevel: UInt = 2.toUInt
  }

  val uiDrawDefaultMiterLimit: CDouble = 10.0

  object uiDrawFillMode {
    val uiDrawFillModeWinding: UInt = 0.toUInt
    val uiDrawFillModeAlternate: UInt = 1.toUInt
  }

  object uiAttributeTypeFamily {
    val uiAttributeTypeFamily: UInt = 0.toUInt
    val uiAttributeTypeSize: UInt = 1.toUInt
    val uiAttributeTypeWeight: UInt = 2.toUInt
    val uiAttributeTypeItalic: UInt = 3.toUInt
    val uiAttributeTypeStretch: UInt = 4.toUInt
    val uiAttributeTypeColor: UInt = 5.toUInt
    val uiAttributeTypeBackground: UInt = 6.toUInt
    val uiAttributeTypeUnderline: UInt = 7.toUInt
    val uiAttributeTypeUnderlineColor: UInt = 8.toUInt
    val uiAttributeTypeFeatures: UInt = 9.toUInt
  }

  object uiTextWeight {
    val uiTextWeightMinimum: UInt = 0.toUInt
    val uiTextWeightThin: UInt = 100.toUInt
    val uiTextWeightUltraLight: UInt = 200.toUInt
    val uiTextWeightLight: UInt = 300.toUInt
    val uiTextWeightBook: UInt = 350.toUInt
    val uiTextWeightNormal: UInt = 400.toUInt
    val uiTextWeightMedium: UInt = 500.toUInt
    val uiTextWeightSemiBold: UInt = 600.toUInt
    val uiTextWeightBold: UInt = 700.toUInt
    val uiTextWeightUltraBold: UInt = 800.toUInt
    val uiTextWeightHeavy: UInt = 900.toUInt
    val uiTextWeightUltraHeavy: UInt = 950.toUInt
    val uiTextWeightMaximum: UInt = 1000.toUInt
  }
  object uiDrawTextItalic {
    val uiDrawTextItalicNormal: UInt = 0.toUInt
    val uiDrawTextItalicOblique: UInt = 1.toUInt
    val uiDrawTextItalicItalic: UInt = 2.toUInt
  }

  object uiDrawTextStretch {
    val uiDrawTextStretchUltraCondensed: UInt = 0.toUInt
    val uiDrawTextStretchExtraCondensed: UInt = 1.toUInt
    val uiDrawTextStretchCondensed: UInt = 2.toUInt
    val uiDrawTextStretchSemiCondensed: UInt = 3.toUInt
    val uiDrawTextStretchNormal: UInt = 4.toUInt
    val uiDrawTextStretchSemiExpanded: UInt = 5.toUInt
    val uiDrawTextStretchExpanded: UInt = 6.toUInt
    val uiDrawTextStretchExtraExpanded: UInt = 7.toUInt
    val uiDrawTextStretchUltraExpanded: UInt = 8.toUInt
  }

  object uiUnderLine {
    val uiUnderlineNone: UInt = 0.toUInt
    val uiUnderlineSingle: UInt = 1.toUInt
    val uiUnderlineDouble: UInt = 2.toUInt
    val uiUnderlineSuggestion: UInt = 3.toUInt
  }

  object uiUnderLineColor {
    val uiUnderlineColorCustom: UInt = 0.toUInt
    val uiUnderlineColorSpelling: UInt = 1.toUInt
    val uiUnderlineColorGrammar: UInt = 2.toUInt
    val uiUnderlineColorAuxiliary: UInt = 3.toUInt
  }

  object uiDrawTextAlign {
    val uiDrawTextAlignLeft: UInt = 0.toUInt
    val uiDrawTextAlignCenter: UInt = 1.toUInt
    val uiDrawTextAlignRight: UInt = 2.toUInt
  }

  object uiExtKey {
    private var i = 0.toUInt
    private def index = { i += 1.toUInt; i }

    val uiExtKeyEscape: UInt = index
    val uiExtKeyInsert: UInt = index // equivalent to "Help" on Apple keyboards
    val uiExtKeyDelete: UInt = index
    val uiExtKeyHome: UInt = index
    val uiExtKeyEnd: UInt = index
    val uiExtKeyPageUp: UInt = index
    val uiExtKeyPageDown: UInt = index
    val uiExtKeyUp: UInt = index
    val uiExtKeyDown: UInt = index
    val uiExtKeyLeft: UInt = index
    val uiExtKeyRight: UInt = index
    val uiExtKeyFindex
        : UInt = index // Findex..Findex2 are guaranteed to be consecutive
    val uiExtKeyF2: UInt = index
    val uiExtKeyF3: UInt = index
    val uiExtKeyF4: UInt = index
    val uiExtKeyF5: UInt = index
    val uiExtKeyF6: UInt = index
    val uiExtKeyF7: UInt = index
    val uiExtKeyF8: UInt = index
    val uiExtKeyF9: UInt = index
    val uiExtKeyF10: UInt = index
    val uiExtKeyF11: UInt = index
    val uiExtKeyF12: UInt = index
    val uiExtKeyN0: UInt = index // numpad keys; independent of Num Lock state
    val uiExtKeyN1: UInt = index // N0..N9 are guaranteed to be consecutive
    val uiExtKeyN2: UInt = index
    val uiExtKeyN3: UInt = index
    val uiExtKeyN4: UInt = index
    val uiExtKeyN5: UInt = index
    val uiExtKeyN6: UInt = index
    val uiExtKeyN7: UInt = index
    val uiExtKeyN8: UInt = index
    val uiExtKeyN9: UInt = index
    val uiExtKeyNDot: UInt = index
    val uiExtKeyNEnter: UInt = index
    val uiExtKeyNAdd: UInt = index
    val uiExtKeyNSubtract: UInt = index
    val uiExtKeyNMultiply: UInt = index
    val uiExtKeyNDivide: UInt = index
  }

  object uiModifiers {
    val uiModifierCtrl: UInt = 1.toUInt << 0
    val uiModifierAlt: UInt = 1.toUInt << 1
    val uiModifierShift: UInt = 1.toUInt << 2
    val uiModifierSuper: UInt = 1.toUInt << 3
  }

  object uiAlign {
    val uiAlignFill: UInt = 0.toUInt
    val uiAlignStart: UInt = 1.toUInt
    val uiAlignCenter: UInt = 2.toUInt
    val uiAlignEnd: UInt = 3.toUInt
  }
}
