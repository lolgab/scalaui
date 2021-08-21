name := "scalaui"

organization := "it.lolgab"

version := "0.0.1"

scalaVersion := "2.13.6"

nativeLinkingOptions := Seq(
  s"-L${baseDirectory.value}/native-lib",
  "-v" /*,
  "-static"*/
)

enablePlugins(ScalaNativePlugin)
