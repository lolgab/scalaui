import mill._
import mill.scalalib._
import mill.scalanativelib._
import mill.scalalib.publish._
import $ivy.`com.goyeau::mill-scalafix::0.2.8`
import com.goyeau.mill.scalafix.ScalafixModule
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.1.4`
import de.tobiasroeser.mill.vcs.version.VcsVersion

trait Common extends ScalaNativeModule {
  def scalaVersion = "2.13.8"
  def scalaNativeVersion = "0.4.4"
}

trait Publish extends PublishModule {
  def pomSettings =
    PomSettings(
      description = "Scala Native GUI library based on libui",
      organization = "com.github.lolgab",
      url = "https://github.com/lolgab/scalaui",
      licenses = Seq(License.MIT),
      versionControl = VersionControl.github(owner = "lolgab", repo = "scalaui"),
      developers = Seq(
        Developer("lolgab", "Lorenzo Gabriele", "https://github.com/lolgab")
      )
    )
  def publishVersion = VcsVersion.vcsState().format()
}

object scalaui extends Common with Publish
object integration extends Module {
  object tests extends Module {
    object `border-panel` extends Common {
      def moduleDeps = Seq(scalaui)
    }
    object `content-size` extends Common {
      def moduleDeps = Seq(scalaui)
    }
    object `font` extends Common {
      def moduleDeps = Seq(scalaui)
    }
    object `hello-world` extends Common {
      def moduleDeps = Seq(scalaui)
    }
    object `multiple-windows` extends Common {
      def moduleDeps = Seq(scalaui)
    }
    object `on-closing` extends Common {
      def moduleDeps = Seq(scalaui)
    }
    object paint extends Common {
      def moduleDeps = Seq(scalaui)
    }
    object complex extends Common {
      def moduleDeps = Seq(scalaui)
    }
    object `write-on-screen` extends Common {
      def moduleDeps = Seq(scalaui)
    }
  }
}
