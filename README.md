# scalaui
Scala Native GUI framework based on [libui](https://github.com/andlabs/libui)

## Example text editor
![How it looks like in elementary](https://image.ibb.co/cMVcgw/editor.png)

[scalaui-texteditor](https://github.com/lolgab/scalaui-texteditor/)

## Getting Started
Once you have setup you Scala Native project and enviroment, add the following to your build.sbt:

 ```scala
libraryDependencies += "it.lolgab" %%% "scalaui" % "0.0.1"
 ```
(there is no release yet, you have to publishLocal the library to let it work!)

Then you can import scalaui:

```scala
import scalaui._
```

We want to create a simple window with a button that prints "Hello World!" on the console when clicked.

First you have to define a method which will be executed everytime the button is clicked:

```scala
def greeting(): Unit = println("Hello World!")
```

Then you create a button in your `Main` object, passing a name and the method name followed by a `_`:
```scala
val button = new Button("My Button", greeting _)
```

Then you create a window passing the button as content:

```scala
val window = new Window("My Window", width = 200, height = 200, content = button)
```

Then you can actually trigger the creation of the window with all its components in your main method:

```scala
def main(args: Array[String]) = {
  scalaui.render(window)
}
```
You now should see a nice window with a button, let's click it:

![](https://image.ibb.co/bWFa1w/scalaui.png)

### It works! :tada::tada::tada:
