package scalaui

class BorderPane(north: Component,
                 west: Component,
                 center: Component,
                 east: Component,
                 south: Component)
    extends Component {
  val centerComponents = Seq(west, center, east)
  val centerPane = new HorizontalPane(centerComponents, true)
  val verticalComponents = Seq(north, centerPane, south)
  val verticalPane = new VerticalPane(verticalComponents, true)

  override private[scalaui] def build(): Unit = {
    verticalPane.build()
    control = verticalPane.control
  }
}
