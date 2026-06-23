# Lite-IDE backend

Scala 3 / Cats Effect / http4s server for the collaborative editor. Runs on `:8090`
by default (override with `HTTP_HOST` / `HTTP_PORT`).

> New to the project? Read [`../GETTING_STARTED.md`](../GETTING_STARTED.md) first — it
> walks through running the backend and the frontend together.

## Prerequisites

- JDK 17+
- sbt 1.10+

On macOS: `brew install --cask temurin@21 && brew install sbt`.

## Commands

```bash
sbt run                          # boot the server (seeds one demo doc titled "welcome")
sbt ~compile                     # watch-compile on save
sbt test                         # run every test
sbt "testOnly *OpSpec"           # one test class
sbt "testOnly *DocumentRoomSpec" # one test class
sbt ~test                        # watch-test
HTTP_PORT=9090 sbt run           # override the port
```

The first `sbt` invocation downloads the dependency tree into `~/.cache/coursier` and
`~/.sbt`; subsequent runs reuse the cache.

## Test suite

| File                                       | What it pins                                                  |
| ------------------------------------------ | ------------------------------------------------------------- |
| `domain/OpSpec.scala`                      | OT convergence — left/right application order must agree      |
| `domain/OpApplySpec.scala`                 | range-check semantics of `Op.applyTo` and `Op.isNoop`         |
| `protocol/WireSpec.scala`                  | exact JSON shape of every client/server frame                 |
| `service/DocumentRoomSpec.scala`           | join snapshot, edit ack, cursor broadcast, peer leave         |

If `WireSpec` breaks, the React client's switch in `useCollab.js` will break too —
both sides agree on these literal field names.

## Architecture choices

- **Concurrency model:** server-authoritative **operational transform** over plain text
  ops (`Insert(pos, text)` / `Delete(pos, length)`). Per-document state lives in a
  `DocumentRoom` guarded by its own `cats.effect.std.Mutex`; fan-out to subscribers uses
  `fs2.Topic`. Rooms are independent, so adding documents scales horizontally inside the
  same JVM without contention.
- **Why OT and not a CRDT:** OT lets us keep the document state as a plain `String`,
  which Monaco-style frontends can consume verbatim. CRDTs (Yjs/Automerge) would push
  more state to the client and demand a much bigger interop surface.
- **Cold storage:** `DocumentService.inMemory` is a placeholder `Ref[Map[...]]`. Swap it
  for a real backend (Skunk/Postgres) without touching anything above the `service/`
  layer.

## HTTP API

| Method | Path                       | Body / params                          | Response                            |
| ------ | -------------------------- | -------------------------------------- | ----------------------------------- |
| GET    | `/health`                  |                                        | `ok`                                |
| GET    | `/api/documents`           |                                        | `[{ id, title, version }]`          |
| POST   | `/api/documents`           | `{ "title": "...", "contents": "..." }`| `Document` JSON                     |
| GET    | `/api/documents/:id`       |                                        | `Document` JSON                     |
| GET    | `/ws/documents/:id?user=N` | (WebSocket upgrade)                    | live collaboration channel         |

## WebSocket protocol

All frames are JSON text with a `"type"` discriminator.

### Client → server

```json
{ "type": "edit",   "baseVersion": 7, "op": { "type": "insert", "pos": 42, "text": "hi" } }
{ "type": "edit",   "baseVersion": 7, "op": { "type": "delete", "pos": 42, "length": 3 } }
{ "type": "cursor", "pos": 17, "selectionEnd": 22 }
{ "type": "resync" }
```

`baseVersion` is the document version the client based the op on. If it is stale, the
server transforms the op against every op applied since (OT). Out-of-range ops are
rejected silently for now — clients should reconnect on perceived divergence.

### Server → client

```json
{ "type": "snapshot", "documentId": "...", "sessionId": "...", "userId": "...",
  "version": 7, "text": "...", "peers": [Presence, ...] }

{ "type": "applied", "version": 8, "ops": [Op, ...], "authorSessionId": "..." }

{ "type": "cursor",     "sessionId": "...", "userId": "...", "displayName": "Alice",
                        "cursor": 17, "selectionEnd": 22 }
{ "type": "peerJoined", "presence": Presence }
{ "type": "peerLeft",   "sessionId": "..." }
{ "type": "error",      "reason": "..." }
```

The first frame after a successful upgrade is always `snapshot`. After that the client
applies every `applied` it receives in order, using `authorSessionId` to distinguish
its own acks from remote edits.

## Layout

```
src/main/scala/com/liteide/
├── Main.scala                     — composition root
├── config/AppConfig.scala         — env-var config
├── domain/
│   ├── Document.scala             — text + version
│   ├── Ids.scala                  — opaque id types
│   ├── Op.scala                   — Insert/Delete + applyTo + OT transform
│   ├── Session.scala              — Session + Presence
│   └── User.scala                 — User + Role
├── protocol/Wire.scala            — ClientMsg / ServerMsg ADT + circe codecs
├── service/
│   ├── DocumentRoom.scala         — live per-document state, OT pipeline, broadcast
│   ├── DocumentService.scala      — cold metadata storage
│   └── RoomRegistry.scala         — lazy room lookup/creation
├── http/
│   ├── HttpServer.scala           — ember server + WS app
│   └── Routes.scala               — REST + WS routing tree
└── ws/CollabSocket.scala          — WS handshake + per-connection lifecycle
```

`src/test/scala/com/liteide/domain/OpSpec.scala` covers OT convergence for the
interesting cases (concurrent inserts/deletes, overlap, split).
