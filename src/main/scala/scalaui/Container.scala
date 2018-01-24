package scalaui

trait Container extends Component {
  val children: Seq[Component]

  private[scalaui] override def build(): Unit = {
    for(child <- children) {
      child.build()
      appendChild(child)
    }
  }

  protected def appendChild(child: Component): Unit
}