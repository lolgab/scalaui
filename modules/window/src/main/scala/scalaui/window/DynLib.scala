package scalaui.window

import scala.scalanative.unsafe.*

@link("kernel32") @extern private object Kernel:
  def LoadLibraryA(name: CString): Ptr[Byte] = extern
  def GetProcAddress(module: Ptr[Byte], name: CString): Ptr[Byte] = extern

/** Run-time library loading: `dlopen`/`dlsym` on POSIX, `LoadLibrary`/`GetProcAddress` on Windows. */
private[scalaui] object DynLib:
  import scala.scalanative.meta.LinktimeInfo

  /** Opens `name` (a file name, resolved by the OS loader); null when it cannot be loaded. */
  def open(name: String): Ptr[Byte] = Zone {
    if LinktimeInfo.isWindows then Kernel.LoadLibraryA(toCString(name)) else Dl.dlopen(toCString(name), 2) // RTLD_NOW
  }

  def symbol(lib: Ptr[Byte], name: String): Ptr[Byte] = Zone {
    if LinktimeInfo.isWindows then Kernel.GetProcAddress(lib, toCString(name)) else Dl.dlsym(lib, toCString(name))
  }
