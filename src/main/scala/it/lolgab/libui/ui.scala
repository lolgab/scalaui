package it.lolgab.libui

import scala.scalanative.native
import native.extern
import native.{
  Ptr,
  CChar,
  CInt,
  UInt,
  ULong,
  CStruct1,
  CSize,
  CString,
  CFunctionPtr1,
  CFunctionPtr2,
  CFunctionPtr3
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

  def uiQueueMain(f: CFunctionPtr1[Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern

  def unOnShouldQuit(f: CFunctionPtr1[Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern

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
  def uiControlSetParent(uc: Ptr[uiControl], uiControl: Ptr[uiControl]): Unit = extern
  def uiControlToplevel(uc: Ptr[uiControl]): CInt = extern
  def uiControlVisible(uc: Ptr[uiControl]): CInt = extern
  def uiControlShow(uc: Ptr[uiControl]): Unit = extern
  def uiControlHide(uc: Ptr[uiControl]): Unit = extern
  def uiControlEnabled(uc: Ptr[uiControl]): CInt = extern
  def uiControlEnable(uc: Ptr[uiControl]): Unit = extern
  def uiControlDisable(uc: Ptr[uiControl]): Unit = extern
  
  def uiAllocControl(n: CSize, OSsig: UInt, typesig: UInt, typenamestr: CString): Ptr[uiControl] = extern
  def uiFreeControl(uc: Ptr[uiControl]): Unit = extern

  def uiControlVerifySetParent(uc: Ptr[uiControl], parent: Ptr[uiControl]): Unit = extern
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
  def uiButtonOnClicked(b: Ptr[uiButton], f: CFunctionPtr2[Ptr[uiButton], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
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
  def uiCheckboxOnToggled(c: Ptr[uiCheckbox], f: CFunctionPtr2[uiCheckbox,Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
  def uiCheckboxChecked(c: Ptr[uiCheckbox]): CInt = extern
  def uiCheckboxSetChecked(c: Ptr[uiCheckbox], checked: CInt): Unit = extern
  def uiNewCheckbox(text: CString): Ptr[uiCheckbox] = extern

  type uiEntry = extern
  def uiEntryText(e: Ptr[uiEntry]): CString = extern
  def uiEntrySetText(e: Ptr[uiEntry], text: CString): Unit = extern
  def uiEntryOnChanged(e: Ptr[uiEntry], f: CFunctionPtr2[Ptr[uiEntry], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
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
  def uiTabAppend(t: Ptr[uiTab], name: CString, c: Ptr[uiControl]): Unit = extern
  def uiTabInsertAt(t: Ptr[uiTab], name: CString, before: CInt, c: Ptr[uiControl]): Unit = extern
  def uiTabDelete(t: Ptr[uiTab], index: CInt): Unit = extern
  def uiTabNumPages(t: Ptr[uiTab]): CInt = extern
  def uiTabMargined(t: Ptr[uiTab], page: CInt): CInt = extern
  def uiTabSetMargined(t: Ptr[uiTab], page: CInt , margined: CInt): Unit = extern
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
  def uiSpinboxOnChanged(s: Ptr[uiSpinbox], f: CFunctionPtr2[Ptr[uiSpinbox], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
  def uiNewSpinbox(min: CInt, max: CInt): Ptr[uiSpinbox] = extern

  type uiSlider = extern
  def uiSliderValue(s: Ptr[uiSlider]): CInt = extern
  def uiSliderSetValue(s: Ptr[uiSlider], value: CInt): Unit = extern
  def uiSliderOnChanged(s: Ptr[uiSlider], f: CFunctionPtr2[Ptr[uiSlider], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
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
  def uiComboboxOnSelected(c: Ptr[uiCombobox], f: CFunctionPtr2[Ptr[uiCombobox], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
  def uiNewCombobox(): Ptr[uiCombobox] = extern

  type uiEditableCombobox = extern
  def uiEditableComboboxAppend(c: Ptr[uiEditableCombobox], text: CString): Unit = extern
  def uiEditableComboboxText(c: Ptr[uiEditableCombobox]): CString = extern
  def uiEditableComboboxSetText(c: Ptr[uiEditableCombobox], text: CString): Unit = extern
  def uiEditableComboboxOnChanged(c: Ptr[uiEditableCombobox], f: CFunctionPtr2[Ptr[uiEditableCombobox], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
  def uiNewEditableCombobox(): Ptr[uiEditableCombobox] = extern

  type uiRadioButtons = extern
  def uiRadioButtonsAppend(r: Ptr[uiRadioButtons], text: CString): Unit = extern
  def uiRadioButtonsSelected(r: Ptr[uiRadioButtons]): CInt = extern
  def uiRadioButtonsSetSelected(r: Ptr[uiRadioButtons], n: CInt): Unit = extern
  def uiRadioButtonsOnSelected(r: Ptr[uiRadioButtons], f: CFunctionPtr2[Ptr[uiRadioButtons], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
  def uiNewRadioButtons(): Ptr[uiRadioButtons] = extern

  type uiDateTimePicker = extern
  def uiNewDateTimePicker(): Ptr[uiDateTimePicker] = extern
  def uiNewDatePicker(): Ptr[uiDateTimePicker] = extern
  def uiNewTimePicker(): Ptr[uiDateTimePicker] = extern


type uiMultilineEntry = extern
  def uiMultilineEntryText(e: Ptr[uiMultilineEntry]): CString = extern
  def uiMultilineEntrySetText(e: Ptr[uiMultilineEntry], text: CString): Unit = extern
  def uiMultilineEntryAppend(e: Ptr[uiMultilineEntry], text: CString): Unit = extern
  def uiMultilineEntryOnChanged(e: Ptr[uiMultilineEntry], f: CFunctionPtr2[Ptr[uiMultilineEntry], Ptr[Byte], Unit], data: Ptr[Byte]): Unit = extern
  def uiMultilineEntryReadOnly(e: Ptr[uiMultilineEntry]): CInt = extern
  def uiMultilineEntrySetReadOnly(e: Ptr[uiMultilineEntry], readonly: CInt): Unit = extern
  def uiNewMultilineEntry(): Ptr[uiMultilineEntry] = extern
  def uiNewNonWrappingMultilineEntry(): Ptr[uiMultilineEntry] = extern


  type uiMenuItem = extern
  def uiMenuItemEnable(m: Ptr[uiMenuItem]): Unit = extern
  def uiMenuItemDisable(m: Ptr[uiMenuItem]): Unit = extern
  def uiMenuItemOnClicked(
      m: Ptr[uiMenuItem],
      f: CFunctionPtr3[Ptr[uiMenuItem], Ptr[uiWindow], Ptr[Byte], Unit],
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

  def uiOpenFile(parent: Ptr[uiWindow]): CChar = extern
  def uiSaveFile(parent: Ptr[uiWindow]): CChar = extern
  def uiMsgBox(parent: Ptr[uiWindow], title: CString, description: CString): Unit = extern
  def uiMsgBoxError(parent: Ptr[uiWindow], title: CString, description: CString): Unit = extern
}