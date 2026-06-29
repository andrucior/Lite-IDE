package com.liteide.service

import cats.effect.IO
import munit.CatsEffectSuite

import com.liteide.domain.Severity

/** Tests for the compiler-backed [[ScalaDiagnostics]] service.
  *
  * These run a real in-process Scala 3 compile per case, so they are a little slower than the pure
  * suites — but they're the only way to pin that we map the compiler's errors, warnings, and source
  * positions onto our model correctly.
  */
final class ScalaDiagnosticsSpec extends CatsEffectSuite:

  private val service: IO[ScalaDiagnostics[IO]] = ScalaDiagnostics.make[IO]()

  test("clean code produces no diagnostics"):
    service.flatMap(_.check("object Ok:\n  val x: Int = 1\n")).map { ds =>
      assertEquals(ds, Nil)
    }

  test("a type mismatch is reported as an error with a sane position"):
    service.flatMap(_.check("object Bad:\n  val x: Int = \"nope\"\n")).map { ds =>
      val errors = ds.filter(_.severity == Severity.Error)
      assert(errors.nonEmpty, s"expected an error, got $ds")
      // The bad literal is on line 2 (1-based); positions must be within the source.
      val e = errors.head
      assert(e.startLine >= 1 && e.startCol >= 1, s"non-positive position: $e")
    }

  test("a parse error is reported as an error"):
    service.flatMap(_.check("object Broken:\n  val =\n")).map { ds =>
      assert(ds.exists(_.severity == Severity.Error), s"expected a parse error, got $ds")
    }

  test("an unused import is reported as a warning (via -Wunused:all)"):
    val src =
      """import scala.collection.mutable
        |object HasUnused:
        |  val x: Int = 1
        |""".stripMargin
    service.flatMap(_.check(src)).map { ds =>
      assert(ds.exists(_.severity == Severity.Warning), s"expected an unused warning, got $ds")
    }
