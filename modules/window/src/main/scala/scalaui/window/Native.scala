package scalaui.window

import scala.scalanative.unsafe.*
import scala.scalanative.meta.LinktimeInfo

@extern private object GcState:
  /** The switch itself, so deliberately not `@blocking`. 0 = Managed (runs Scala, polls GC safepoints), 1 = Unmanaged. */
  @name("scalanative_GC_set_mutator_thread_state") def set(state: CInt): Unit = extern

/** Thread-state helpers for native calls Scala Native cannot see are blocking.
  *
  * A call to an `@extern` method marked `@blocking` runs with the thread Unmanaged, so a garbage collection does not wait for
  * it. Calls through a function pointer (everything resolved with `dlsym`: libmpv, OpenGL, the Apple frameworks) get no such
  * treatment, and a stop-the-world collection triggered on another thread then waits, with every mutator stopped, until the
  * native call returns. Wrap those calls in [[blocking]]. Only code that touches no managed objects may run inside.
  */
private[scalaui] object Native:
  /** Runs a native call that may wait (I/O, GPU, another process's locks, mpv's internal locks) with the GC not waiting for it. */
  inline def blocking[A](inline body: => A): A =
    if LinktimeInfo.isMultithreadingEnabled then
      GcState.set(1)
      try body finally GcState.set(0)
    else body

  /** For a callback that native code calls on a thread currently inside a `@blocking` call (so Unmanaged): runs `body` as
    * ordinary Scala (Managed) and hands the thread back to the native code Unmanaged. */
  inline def managed[A](inline body: => A): A =
    if LinktimeInfo.isMultithreadingEnabled then
      GcState.set(0)
      try body finally GcState.set(1)
    else body
