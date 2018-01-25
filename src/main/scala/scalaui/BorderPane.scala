package scalaui

import scalaui._

class BorderPane(north: Component,
                 west: Component,
                 center: Component,
                 east: Component,
                 south: Component)
    extends Component {
  val centerPane = new HorizontalPane(Seq(west, (center, true), east))
  val verticalPane = new VerticalPane(Seq(north, (centerPane, true), south))

  override private[scalaui] def build(): Unit = {
    verticalPane.build()
    control = verticalPane.control
  }
}
