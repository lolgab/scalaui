package scalaui.testkit

import java.nio.file.{Files, Paths, StandardOpenOption}

/** munit suite that also appends failures to `target/test-failures.log`: sbt shows no per-test output for Scala Native test processes. */
abstract class LoggedSuite extends munit.FunSuite:
  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform("log-failures", t =>
      t.withBodyMap(_.transform { r =>
        r.failed.foreach { e =>
          val msg = s"FAIL ${getClass.getName}: ${t.name}: ${e}\n${Option(e.getCause).map(c => s"  cause: $c\n").getOrElse("")}"
          Files.writeString(Paths.get("target/test-failures.log"), msg, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        r
      }(scala.concurrent.ExecutionContext.global)))
