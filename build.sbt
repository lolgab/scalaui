ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "com.github.lolgab"
// version comes from git tags via sbt-dynver (through sbt-ci-release): tag `v0.1.0` releases 0.1.0, untagged commits are snapshots
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/lolgab/scalaui"))
ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/lolgab/scalaui"), "scm:git:git@github.com:lolgab/scalaui.git"))
ThisBuild / developers := List(Developer("lolgab", "Lorenzo Gabriele", "lorenzolespaul@gmail.com", url("https://github.com/lolgab")))
ThisBuild / description := "Cross-platform immediate-mode UI toolkit for Scala Native"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")

lazy val testkit = Project("testkit", file("modules/testkit"))
  .enablePlugins(ScalaNativePlugin)
  .settings(name := "scalaui-testkit", libraryDependencies += "org.scalameta" %% "munit" % "1.3.6", publish / skip := true)

// Cross-compiling to Windows from macOS with Homebrew's mingw-w64: SCALAUI_WIN_CROSS=<mingw sysroot>
// (run sbt with `--server`: the default thin client's background server would not see the variable)
def platformConfig(c: scala.scalanative.build.NativeConfig): scala.scalanative.build.NativeConfig = {
  // Threads are only used by the Linux window code (AT-SPI bus connection, X11/Wayland); without multithreading Scala Native runs
  // Thread bodies on the caller. So multithreading is Linux-only: multithreaded builds poll GC safepoints at every loop back edge,
  // which makes tight pixel loops 1.5-3x slower (see site/content/performance.md). SCALAUI_MT=1/0 overrides.
  val linuxTarget = sys.props.getOrElse("os.name", "").toLowerCase.contains("linux") && !sys.env.contains("SCALAUI_WIN_CROSS")
  val mt = sys.env.get("SCALAUI_MT").map(_ == "1").getOrElse(linuxTarget)
  val c0 = c.withEmbedResources(true).withMultithreading(mt)
  // SCALAUI_RELEASE=1 (CI release builds): optimised binaries; the CPU renderer is ~10x slower in debug mode
  val c1 = if sys.env.get("SCALAUI_RELEASE").exists(v => v.nonEmpty && v != "0") then c0.withMode(scala.scalanative.build.Mode.releaseFast) else c0
  sys.env.get("SCALAUI_WIN_CROSS") match {
    case Some(root) =>
      c1.withTargetTriple("x86_64-pc-windows-gnu")
        .withCompileOptions(c1.compileOptions ++ Seq(s"--sysroot=$root", s"-cxx-isystem$root/include/c++/16.2.0", s"-cxx-isystem$root/include/c++/16.2.0/x86_64-w64-mingw32"))
        .withLinkingOptions(Seq(s"--sysroot=$root", "-fuse-ld=/opt/homebrew/bin/x86_64-w64-mingw32-ld", s"-L$root/lib", s"-L$root/../lib/gcc/x86_64-w64-mingw32/16.2.0"))
    case None => c1 // macOS: libobjc is autolinked by the Cocoa shim (scalaui_objc.c), so no -lobjc here either
  }
}

def module(id: String): Project =
  Project(id, file(s"modules/$id")).dependsOn(testkit % Test)
    .enablePlugins(ScalaNativePlugin)
    .settings(
      name := s"scalaui-$id",
      nativeConfig ~= platformConfig,
      libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
      Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")
    )

// --- foundation: geometry, zlib, Unicode algorithms (bidi, segmentation, line breaking: no Scala Native library provides them); xml/json come from existing libraries ---
lazy val geom = module("geom")

// --- graphics (vello_cpu, image, skrifa, harfrust, parley, krilla) ---
lazy val deflate = module("deflate")
lazy val unicode = module("unicode")
lazy val brotli = module("brotli")
// linker stub for java.net.URL needed by scala-xml on Scala Native; shared so svg + docx can link together
lazy val xmlstub = module("xmlstub")
lazy val color = module("color").dependsOn(geom)
lazy val raster = module("raster").dependsOn(geom, color)
lazy val codecs = module("codecs").dependsOn(color, deflate)
lazy val cms = module("cms").dependsOn(color, geom)
lazy val imaging = module("imaging").dependsOn(raster, codecs, color, cms, font)
lazy val font = module("font").dependsOn(geom, raster, deflate, brotli, codecs % Test)
lazy val shaping = module("shaping").dependsOn(font, unicode)
lazy val text = module("text").dependsOn(shaping, raster, gpu, unicode, imaging)
lazy val svg = module("svg").dependsOn(xmlstub, raster, color, geom, codecs, imaging, font, shaping, deflate).settings(
  libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.4.0"
)
lazy val docx = module("docx").dependsOn(xmlstub, geom, deflate).settings(
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-xml" % "2.4.0",
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.35.3"
  )
)
lazy val pdf = module("pdf").dependsOn(raster, shaping, codecs, deflate, cms, imaging)

// --- platform (wgpu + naga, winit, rfd, arboard) ---
lazy val gpu = module("gpu").dependsOn(geom, color, raster, window, codecs)
lazy val window = module("window").dependsOn(geom)
  // the Linux window tests start threads (compiled on every OS), so this one test binary stays multithreaded
  .settings(Test / nativeConfig ~= (_.withMultithreading(true)))
  // the Wayland and X11 suites call the Linux-only protocol code directly (poll, mmap, sendmsg, ...), which does not link on Windows
  .settings(Test / unmanagedSources / excludeFilter := {
    val windows = sys.props.getOrElse("os.name", "").toLowerCase.contains("windows") || sys.env.contains("SCALAUI_WIN_CROSS")
    if windows then HiddenFileFilter || "Wayland*Suite.scala" || "X11Suite.scala" || "XimSuite.scala" else HiddenFileFilter
  })

lazy val examples = (project in file("examples"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(window, raster, gpu, text, ui, pdf)
  .settings(nativeConfig ~= platformConfig)
  .settings(name := "scalaui-examples", publish / skip := true)
lazy val dialogs = module("dialogs").dependsOn(window)
lazy val clipboard = module("clipboard").dependsOn(window)

// --- ui (egui / eframe) ---
lazy val ui = module("ui").dependsOn(raster, text, gpu, window, dialogs, clipboard, codecs, svg, imaging)

// micro benchmarks (not part of the aggregate): sbt --server -J-Xmx8g "bench/run"
lazy val bench = (project in file("modules/bench"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(raster, imaging, codecs, gpu, text, shaping, font)
  .settings(name := "scalaui-bench", nativeConfig ~= (c => platformConfig(c).withMode(scala.scalanative.build.Mode.releaseFast)), publish / skip := true)

lazy val root = (project in file("."))
  .aggregate(
    testkit, geom, deflate, brotli, color, imaging, svg, raster, codecs, font, shaping, text, pdf,
    gpu, window, examples, dialogs, clipboard, ui, docx, unicode, cms, xmlstub
  )
  .settings(name := "scalaui", publish / skip := true)
