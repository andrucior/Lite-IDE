# Getting started

This page tells you how to run Lite-IDE end-to-end on a fresh machine. Deeper notes
live next to the code they describe: [`backend/README.md`](./backend/README.md) and
[`frontend/README.md`](./frontend/README.md).

## What's in the repo

```
backend/            — Scala 3 (3.3.7 LTS) / sbt — http4s + cats-effect + fs2
frontend/frontend/  — React 19 + Vite + Monaco
```

The double `frontend/frontend/` path is intentional — the inner directory is the
actual Vite project. All frontend commands run from there.

## Prerequisites

| Tool         | Version          | Why                              |
| ------------ | ---------------- | -------------------------------- |
| JDK          | 17 or newer      | runs sbt + the Scala compiler    |
| sbt          | 1.10+            | drives the backend build         |
| Node.js      | 20 or newer      | Vite dev server + build          |
| npm          | bundled with Node| frontend deps                    |

Install on macOS with Homebrew:

```sh
brew install --cask temurin@21      # JDK
brew install sbt node               # build tools
```

## Run it (two terminals)

**Terminal 1 — backend:**

```sh
cd backend
sbt run
```

The first run downloads dependencies; subsequent runs are fast. The server listens on
`http://localhost:8080` and seeds a demo document called `welcome` so the lobby is
non-empty on first load.

**Terminal 2 — frontend:**

```sh
cd frontend/frontend
npm install           # first time only
npm run dev
```

Vite prints a URL (usually `http://localhost:5173`). Open it. The dev server proxies
`/api/*` and `/ws/*` to `localhost:8080`, so the same origin works for REST and
WebSocket.

## Try the collaboration

1. Open the URL in two browser windows (or two devices on the LAN).
2. Pick a different display name in each — the name is stored in `localStorage`.
3. Open the same document in both. You should see:
   - The other participant's name in the toolbar.
   - Their cursor as a coloured caret in the editor; their selection as a coloured
     highlight.
   - Edits made in one window appear in the other within a frame or two.

If the toolbar shows `closed` or `error`, the backend isn't running or isn't reachable
through the proxy. Check the sbt terminal for stack traces.

## Run the tests

Backend (MUnit + munit-cats-effect):

```sh
cd backend
sbt test                  # full suite
sbt "testOnly *OpSpec"    # one class
sbt ~test                 # watch mode — reruns on file save
```

The suite covers:
- `OpSpec` — OT convergence (insert/insert, insert/delete, delete/delete, splits).
- `OpApplySpec` — apply-to-text boundary cases and `isNoop` detection.
- `WireSpec` — JSON shape pinning for every client/server message — if these break,
  the frontend's parser will break too.
- `DocumentRoomSpec` — black-box tests of join / edit / cursor broadcast / leave on the
  in-memory document room.

Frontend lint:

```sh
cd frontend/frontend
npm run lint
```

There's no JS test suite yet. Adding one (Vitest + React Testing Library) is the
natural follow-up; the OT bookkeeping in `src/useCollab.js` is the highest-value
target.

## Production build

Backend:

```sh
cd backend
sbt assembly      # not configured yet — see backend/README.md for packaging notes
sbt stage         # produces a runnable layout under target/universal/stage/
```

Frontend:

```sh
cd frontend/frontend
npm run build     # emits dist/
npm run preview   # serves dist/ locally for a smoke check
```

In production you'll typically put a reverse proxy in front of both, mapping `/api`
and `/ws` to the backend and everything else to the static frontend.

## How it fits together

```
┌──────────────┐   /api/documents    ┌───────────────────────────┐
│  React app   │ ──────────────────▶ │  http4s REST router       │
│  (Monaco)    │                     │  Routes.scala             │
│              │   /ws/documents/:id │                           │
│  useCollab   │ ◀──── WebSocket ───▶│  CollabSocket             │
└──────────────┘                     │       │                   │
                                     │       ▼                   │
                                     │  DocumentRoom (per doc)   │
                                     │   • Mutex-guarded text    │
                                     │   • fs2 Topic broadcast   │
                                     │   • OT transform on edit  │
                                     └───────────────────────────┘
```

Each connected tab gets its own `SessionId`. Every edit is sent as a JSON
`{type:"edit", baseVersion, op}` frame. The server transforms it against any ops
applied since `baseVersion`, appends to history, and broadcasts an `applied` frame to
every subscriber. Cursors are a separate `{type:"cursor", ...}` message that doesn't
touch the document state.

See [`backend/README.md`](./backend/README.md) for the operational-transform model
and [`frontend/README.md`](./frontend/README.md) for the Monaco wiring.
