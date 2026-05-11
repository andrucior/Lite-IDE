# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Lite-IDE is a university Scala-course project: a collaborative code editor in the spirit of Google Docs (real-time multi-user editing, visible cursors/selections, syntax highlighting). Stretch goals listed in the README: a permission system (owner / editor / observer) and edit history.

Two hard constraints set by the project owner:
- **Backend must be written in Scala.** Frontend is React + Vite (JS). Do not migrate the backend to another language or the frontend to another framework.
- **Concurrent users are first-class.** Any backend design (state model, session handling, document-mutation pipeline) must be evaluated against many simultaneous editors on the same document. Prefer immutable data + message passing (Cats Effect / fs2 / Pekko, or Akka — pick one and stick to it) over shared mutable state. CRDT or OT is the natural fit for the document state; pick one early and document the choice in `backend/`.
- **Scala code quality matters.** Idiomatic Scala 3 (the project is pinned to 3.3.7 LTS): use `enum`, `given`/`using`, extension methods, opaque types where they help, and effect types instead of `Future` + `var`. Keep IO at the edges, keep the document/session core pure and unit-testable.

## Repository layout

```
backend/   — sbt project, Scala 3.3.7, currently empty scaffold (no src/ yet)
frontend/frontend/   — Vite + React 19 app; Monaco editor is already a dependency (@monaco-editor/react)
```

Note the doubled `frontend/frontend/` path — all frontend commands run from the inner directory.

## Common commands

Backend (run from `backend/`):
- `sbt compile` — compile
- `sbt run` — run main class
- `sbt test` — run all tests (no test framework wired in yet; add MUnit or ScalaTest in `build.sbt` when first test lands)
- `sbt "testOnly *FooSpec"` — single test class
- `sbt ~compile` / `sbt ~test` — watch mode

Frontend (run from `frontend/frontend/`):
- `npm install` — install deps
- `npm run dev` — Vite dev server
- `npm run build` — production build
- `npm run lint` — ESLint (flat config in `eslint.config.js`)
- `npm run preview` — preview built output

## Working notes for future changes

- `backend/build.sbt` is currently a single-module skeleton with no dependencies. When adding the HTTP/WebSocket layer, declare the stack explicitly in `build.sbt` (http4s + fs2, or Pekko HTTP, etc.) rather than pulling ad-hoc libraries — the choice shapes the whole concurrency model.
- The frontend already pulls in both `@monaco-editor/react` and `react-monaco-editor`. Pick one before building UI on top of it; they are not interchangeable.
- README is in Polish; user-facing prose can be Polish or English, but keep code identifiers, commit messages, and Scala/JS comments in English.
