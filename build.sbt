name := "scalaui"

organization := "it.lolgab"

version := "0.0.2"

scalaVersion := "2.13.6"

nativeLinkingOptions := Seq(
  s"-L${baseDirectory.value}/native-lib"
)

enablePlugins(ScalaNativePlugin)
