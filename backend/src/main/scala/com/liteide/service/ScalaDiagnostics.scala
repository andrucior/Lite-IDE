package com.liteide.service

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.control.NonFatal

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.syntax.all.*

import dotty.tools.dotc.Compiler
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.interfaces
import dotty.tools.dotc.reporting.{Diagnostic as DottyDiagnostic, Reporter}
import dotty.tools.dotc.util.SourceFile
import dotty.tools.io.VirtualDirectory

import com.liteide.domain.{Diagnostic, Severity}

/** Type-checks Scala source text with an in-process Scala 3 compiler and returns the errors and
  * warnings it produces, mapped to our [[Diagnostic]] model.
  *
  * Why the real compiler (not an LSP / hand-rolled linter): the backend is already Scala 3, the
  * documents are ephemeral snippets rather than on-disk sbt projects (so Metals/Bloop is a poor
  * fit), and reusing `dotc` gives true compiler accuracy for free.
  *
  * Concurrency: each `check` builds its own fresh compiler `Context` — the compiler is not safe to
  * share a `Context` across threads — and runs the (blocking, CPU-bound) compile on the blocking
  * pool, gated by a `Semaphore` so a burst of edits can't spawn unbounded compiles. A per-call
  * timeout guards against pathological input.
  */
trait ScalaDiagnostics[F[_]]:
  def check(source: String): F[List[Diagnostic]]

object ScalaDiagnostics:

  private val logger = org.slf4j.LoggerFactory.getLogger("com.liteide.service.ScalaDiagnostics")

  /** A diagnostics service that always returns nothing — used in tests and wherever live
    * type-checking is undesirable.
    */
  def noop[F[_]: Applicative]: ScalaDiagnostics[F] = new ScalaDiagnostics[F]:
    def check(source: String): F[List[Diagnostic]] = List.empty[Diagnostic].pure[F]

  /** Build the real, compiler-backed service.
    *
    * @param maxConcurrent
    *   how many compiles may run in parallel across all documents
    * @param timeout
    *   upper bound on a single compile before we give up and report nothing
    */
  def make[F[_]: Async](
      maxConcurrent: Int = 2,
      timeout: FiniteDuration = 10.seconds
  ): F[ScalaDiagnostics[F]] =
    Semaphore[F](maxConcurrent.toLong).map { sem =>
      new ScalaDiagnostics[F]:
        def check(source: String): F[List[Diagnostic]] =
          sem.permit.use { _ =>
            Async[F]
              .blocking(compile(source))
              .timeout(timeout)
              .handleErrorWith { e =>
                Async[F].delay(logger.debug(s"diagnostics failed: ${e.getMessage}")).as(Nil)
              }
          }
    }

  /** Run the compiler frontend over `source` in a throwaway context and collect every diagnostic
    * its reporter emits. Pure and blocking; safe to call concurrently because nothing is shared.
    */
  private def compile(source: String): List[Diagnostic] =
    val collected = mutable.ListBuffer.empty[Diagnostic]

    val reporter = new Reporter:
      def doReport(dia: DottyDiagnostic)(using Context): Unit =
        collected += toDiagnostic(dia)

    // `fresh` returns a `FreshContext`, which (unlike the `Context` supertype) exposes the
    // mutating `setReporter` / `setSettings` / `setSetting` builders we use below.
    val ctx = (new ContextBase).initialCtx.fresh
    ctx.setReporter(reporter)
    // Set classpath / colour / unused-warnings from string args rather than poking individual
    // setting fields by name — the field names are compiler-internal and drift between versions,
    // whereas the command-line flags are stable public API. `-Wunused:all` matches the project's
    // own policy and surfaces unused imports/locals.
    val args = List(
      "-classpath",
      System.getProperty("java.class.path"),
      "-color:never",
      "-Wunused:all"
    )
    ctx.setSettings(
      ctx.settings.processArguments(args, processAll = true, ctx.settingsState).sstate
    )
    // Never touch the filesystem: bytecode (if codegen runs) goes to an in-memory directory.
    ctx.setSetting(ctx.settings.outputDir, new VirtualDirectory("<diagnostics>"))

    given Context = ctx
    try
      val src = SourceFile.virtual("Snippet.scala", source)
      val run = new Compiler().newRun
      run.compileSources(List(src))
    catch
      // The compiler can throw on partially-typed input the user is still editing. Whatever it
      // reported before crashing is still useful, so we keep `collected` and move on.
      case NonFatal(e) =>
        logger.debug(s"compiler threw, returning partial diagnostics: ${e.getMessage}")
    collected.toList

  /** Map a dotty diagnostic to ours, converting the compiler's 0-based source position to the
    * 1-based line/column convention Monaco expects. Position-less diagnostics collapse to (1,1).
    */
  private def toDiagnostic(dia: DottyDiagnostic): Diagnostic =
    val severity =
      if dia.level >= interfaces.Diagnostic.ERROR then Severity.Error
      else if dia.level == interfaces.Diagnostic.WARNING then Severity.Warning
      else Severity.Info
    val pos = dia.pos
    if pos.exists then
      Diagnostic(
        severity,
        dia.message,
        pos.line + 1,
        pos.column + 1,
        pos.endLine + 1,
        pos.endColumn + 1
      )
    else Diagnostic(severity, dia.message, 1, 1, 1, 1)
