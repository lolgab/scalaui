package scalaui

trait Freeable {
  private[scalaui] def free(): Unit = {}
}
