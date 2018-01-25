package scalaui

trait Container[Properties] extends Component {
  val children: Seq[(Component, Properties)]

  private[scalaui] override def build(): Unit = {
    for((child, properties)<- children) {
      child.build()
      appendChild(child, properties)
    }
  }

  protected def appendChild(child: Component, properties: Properties): Unit
}