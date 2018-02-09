package scalaui

class BorderPanel(north: Component,
                  west: Component,
                  center: Component,
                  east: Component,
                  south: Component)
    extends Component {
  val centerPanel = new HorizontalPanel(west, Stretchy(center), east)
  val verticalPanel = new VerticalPanel(north, Stretchy(centerPanel), south)

  override private[scalaui] def build(): Unit = {
    verticalPanel.build()
    control = verticalPanel.control
  }
}