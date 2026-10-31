---
title: testkit
section: Modules
order: 21
description: LoggedSuite, the shared munit base class for Scala Native tests.
---
# testkit

`scalaui.testkit` contains a single class, `LoggedSuite`. It is a test-only dependency of every module (`dependsOn(testkit % Test)`) and is not published (`publish / skip := true`).

```scala
abstract class LoggedSuite extends munit.FunSuite
```

It adds a munit test transform that appends the name, exception and cause of every failing test to `target/test-failures.log`, because Scala Native test processes print nothing to sbt. See the [Testing guide](../testing.md) for how it fits with the headless and snapshot harnesses.

```scala
import scalaui.testkit.LoggedSuite

class MySuite extends LoggedSuite:
  test("addition") { assertEquals(1 + 1, 2) }
```
