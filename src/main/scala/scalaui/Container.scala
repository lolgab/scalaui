package scalaui

trait Container extends Component {
  val children: Seq[StretchableComponent]

  private[scalaui] override def build(): Unit = {
    for (child <- children) {
      child.component.build()
      appendChild(child)
    }
  }

  protected def appendChild(child: StretchableComponent): Unit

  override private[scalaui] def free(): Unit = {
    for (child <- children) child.component.free()
  }
}
