# Frontend

React 19 + Vite + Monaco — the collaborative editor UI. Talks to the Scala backend
over `/api/*` (REST) and `/ws/*` (WebSocket); both are proxied to `localhost:8090` by
the Vite dev server (see `vite.config.js`).

> New to the project? Read [`../../GETTING_STARTED.md`](../../GETTING_STARTED.md)
> first — it walks through running the backend and the frontend together.

## Prerequisites

- Node.js 20 or newer
- npm (bundled with Node)

On macOS: `brew install node`.

## Commands

```sh
npm install        # first time only
npm run dev        # Vite dev server, usually on :5173
npm run lint       # ESLint
npm run build      # production build into dist/
npm run preview    # serve dist/ for a smoke check
```

The backend must be running on `:8090` for the editor to work. The dev server proxies
`/api` and `/ws` to it; in production a reverse proxy is expected to do the same.

## What the UI does

1. Asks for a display name (persisted in `localStorage`).
2. Shows a lobby — list of documents, plus a form to create new ones.
3. On open, mounts Monaco and opens a WebSocket to `/ws/documents/:id?user=…`.
4. The server's first frame is a `snapshot`; until it arrives the editor shows
   "Connecting…" so the user never types into a doc with an unknown version.
5. Local Monaco edits become `{type:"edit"}` frames carrying the current
   `baseVersion`. Remote `applied` frames are applied to the buffer under a guard so
   they don't bounce back to the server.
6. Cursor moves become `{type:"cursor"}` frames. Each peer's cursor is rendered as a
   coloured caret in Monaco; selections as a coloured highlight. Colours are stable
   per `sessionId`.

## Source map

```
src/
├── main.jsx          — React entry point
├── App.jsx           — display-name prompt + routes between lobby and editor
├── DocumentList.jsx  — lobby: list + create + open
├── CodeEditor.jsx    — Monaco surface; renders peer cursors as decorations
├── useCollab.js      — owns the WebSocket and the OT bookkeeping
├── api.js            — fetch wrappers + wsUrl helper
└── index.css         — dark theme + lobby/toolbar styling
```

`useCollab.js` is the only non-trivial bit. It exposes `sendChanges`, `sendCursor`,
the current `peers` array, and a connection `status`. It also owns an
`applyingRemote` ref that `CodeEditor` reads to suppress its own change emit while
remote ops are being applied.

## Concurrency note

The client is currently a single-op-in-flight model: a local edit is sent to the
server with the latest known `baseVersion`, the server transforms it, and the `Applied`
echo for our own session acts as the ack. This is sufficient for a demo with a few
collaborators on one document. The Wave-style send/buffer/ack queue is the natural
next step — the seam is `useCollab.sendChanges`.

## Tests

There's no JS test runner yet. When one is added, Vitest + React Testing Library is
the recommended pairing; the OT bookkeeping in `useCollab.js` is the highest-value
target (pure functions, no DOM).

To smoke-test collaboration manually:

1. `sbt run` in `backend/`.
2. `npm run dev` in this directory.
3. Open the printed URL in two browser windows and use different display names.
4. Open the same document in both windows; edits and cursors should round-trip.
