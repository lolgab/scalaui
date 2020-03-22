package examples

import scalaui._

object ScalauiExample {
  lazy val label = new Label("My label :-)")
  lazy val button = new Button(
    "Do something on the terminal",
    () => println("Hi from scalaui!")
  )
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
    new Slider(0, 100, () => ()),
    new SpinBox(0, 100, () => ()),
    new Group("Ciao", new Button("Ciao", () => ())),
    progressBar
  )
  lazy val fileMenu = new Menu(
    "File",
    new MenuItem(
      "Message box",
      () => w.messageBox("Important", "remember to say thanks to Lorenzo :-)")
    ),
    SeparatorMenuItem,
    new MenuItem(
      "Error box",
      () => w.errorBox("Error", "A nightmare happened :-O")
    )
  )
  lazy val editMenu = new Menu("Edit")
  lazy val menus = Seq(fileMenu, editMenu)
  lazy val w: Window = new Window(
    "My window",
    width = 200,
    height = 400,
    content = pane,
    menus = menus
  )

  def main(args: Array[String]) = {
    render(w)
  }
}
