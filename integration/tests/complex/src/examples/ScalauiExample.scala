package examples

import scalaui._

object ScalauiExample {
  def buttonOnClick(): Unit = println("Hi from scalaui!")
  def msgBox(): Unit =
    w.messageBox("Important", "remember to say thanks to Lorenzo :-)")
  def error(): Unit = w.errorBox("Error", "A nightmare happened :-O")
  lazy val label = new Label("My label :-)")
  lazy val button = new Button("Do something on the terminal", buttonOnClick _)
  lazy val checkbox = new Checkbox("my checkbox")
  lazy val textField = new TextField("initial Text")
  lazy val textArea = new NonWrappingTextArea()
  lazy val radioButtons = new RadioButtons(Seq("One", "Two", "Three"))
  lazy val tabsSeq = Seq(
    ("Tab1", textArea),
    ("Tab2", radioButtons)
  )
  lazy val tabs = new Tabs(tabsSeq)
  lazy val progressBar = new ProgressBar()
  lazy val pane = new VerticalPanel(
    label,
    button,
    checkbox,
    textField,
    tabs,
    new EditableComboBox(Seq("aaa", "bbb", "ccc")),
    new Slider(0, 100, doNothing _),
    new SpinBox(0, 100, doNothing _),
    new Group("Ciao", new Button("Ciao", doNothing _)),
    progressBar
  )
  lazy val fileMenu = new Menu(
    "File",
    new MenuItem("Message box", msgBox _),
    SeparatorMenuItem,
    new MenuItem("Error box", error _)
  )
  lazy val editMenu = new Menu("Edit")
  lazy val menus = Seq(fileMenu, editMenu)
  lazy val w = new Window(
    "My window",
    width = 200,
    height = 400,
    content = pane,
    menus = menus
  )

  def main(args: Array[String]): Unit = {
    render(w)
  }
}
