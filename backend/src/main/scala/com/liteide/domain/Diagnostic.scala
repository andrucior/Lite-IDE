package com.liteide.domain

/** A single compiler diagnostic for the live document.
  *
  * Positions are **1-based** line/column ranges so they map straight onto Monaco's marker API
  * (`startLineNumber` / `startColumn` / …) on the frontend without any off-by-one translation. The
  * producer (`ScalaDiagnostics`) is responsible for converting the compiler's 0-based source
  * positions into this convention.
  */
final case class Diagnostic(
    severity: Severity,
    message: String,
    startLine: Int,
    startCol: Int,
    endLine: Int,
    endCol: Int
) derives CanEqual

/** Severity of a [[Diagnostic]], mirroring the three levels the compiler reporter emits. */
enum Severity derives CanEqual:
  case Error, Warning, Info
