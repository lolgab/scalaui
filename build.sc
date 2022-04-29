import mill._
import mill.scalalib._
import mill.scalanativelib._

trait Common extends ScalaNativeModule {
  def scalaVersion = "2.13.8"
  def scalaNativeVersion = "0.4.4"
}

object scalaui extends Common
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
