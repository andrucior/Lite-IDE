package com.liteide.service

import cats.effect.kernel.{Concurrent, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic

import com.liteide.domain.{Op, Presence, Role}
import com.liteide.domain.Ids.{DocumentId, SessionId, UserId}
import com.liteide.protocol.Wire.ServerMsg

/** The live, in-memory state of one document and the people editing it.
  *
  * Concurrency model:
  *   - All mutations of `(text, version, history)` happen inside a per-room `Mutex` so that
  *     transform-and-apply is atomic; the rest of the runtime stays free to do work in parallel.
  *     The mutex is fine because each room has its own — fan-out across documents scales naturally.
  *   - Outbound fan-out uses an fs2 `Topic`, which is many-to-many and back-pressures per
  *     subscriber. New joiners subscribe with a bounded buffer.
  *   - Presence (cursors / peers) is a separate `Ref` because it doesn't affect text and doesn't
  *     need to be serialised behind the same mutex.
  */
trait DocumentRoom[F[_]]:
  def documentId: DocumentId

  /** Subscribe to broadcast messages. Returns the snapshot the new participant should see
    * (atomically captured at subscribe time) and a stream of every subsequent broadcast.
    *
    * `role` is stored in the session's presence so it can be checked in `submitEdit`
    * and broadcast to peers via `PeerJoined`.
    */
  def join(sessionId: SessionId, userId: UserId, displayName: String, role: Role)
      : F[(ServerMsg.Snapshot, Stream[F, ServerMsg])]

  /** Remove a session — drop its presence and broadcast `PeerLeft`. */
  def leave(sessionId: SessionId): F[Unit]

  /** Apply a client edit (transforming against intervening ops if `baseVersion` is stale)
    * and broadcast the result. Returns the new version on success.
    *
    * Observers are rejected immediately without touching OT state.
    */
  def submitEdit(authorSessionId: SessionId, baseVersion: Int, op: Op): F[Either[String, Int]]

  /** Update a session's cursor + selection and broadcast. */
  def submitCursor(sessionId: SessionId, pos: Int, selectionEnd: Int): F[Unit]

  /** Current snapshot — used for REST GET. */
  def snapshot: F[(Int, String)]

  /** Number of currently-connected sessions. */
  def peerCount: F[Int]

object DocumentRoom:

  private final case class State(text: String, version: Int, history: Vector[Op])

  /** Build a fresh room seeded with the given text (version 0, empty history). */
  def make[F[_]: Concurrent](
      docId: DocumentId,
      initialText: String
  ): F[DocumentRoom[F]] =
    for
      state <- Ref.of[F, State](State(initialText, 0, Vector.empty))
      presence <- Ref.of[F, Map[SessionId, Presence]](Map.empty)
      topic <- Topic[F, ServerMsg]
      mutex <- Mutex[F]
    yield new DocumentRoom[F]:

      val documentId: DocumentId = docId

      def join(
          sessionId:   SessionId,
          userId:      UserId,
          displayName: String,
          role:        Role,
      ): F[(ServerMsg.Snapshot, Stream[F, ServerMsg])] =
        // We capture the snapshot AND register the subscription under the mutex. Because
        // every state-mutating publish also runs under the mutex (see `submitEdit`), this
        // is enough to guarantee the new subscriber sees every broadcast for any version
        // strictly greater than the captured snapshot's. Cursor/presence broadcasts don't
        // touch `state`, but ordering loss there is harmless — the next cursor update from
        // the same peer will overwrite it.
        mutex.lock.surround {
          for
            // `subscribeAwait` returns a `Resource` that registers the subscriber
            // immediately on allocate — unlike `subscribe`, which defers registration to
            // first pull and would let us miss any broadcast that fires before http4s
            // starts draining the WS. We allocate manually so we can hand the release
            // back as a stream finalizer.
            allocated <- topic.subscribeAwait(64).allocated
            (rawStream, release) = allocated
            s <- state.get
            peers <- presence.get
            me     = Presence(sessionId, userId, displayName, cursor = 0, selectionEnd = 0, role = role)
            _     <- presence.update(_.updated(sessionId, me))
            // Explicit type ascription is required: without it, the for-comprehension
            // widens `snap` to the parent `ServerMsg` type (due to the later
            // `topic.publish1` step) and the yielded tuple no longer matches the
            // declared return type `(ServerMsg.Snapshot, Stream[F, ServerMsg])`.
            snap: ServerMsg.Snapshot = ServerMsg.Snapshot(
                       documentId  = docId,
                       sessionId   = sessionId,
                       userId      = userId,
                       version     = s.version,
                       text        = s.text,
                       peers       = peers.values.toList,
                       role        = role,
                     )
            _     <- topic.publish1(ServerMsg.PeerJoined(me)).void
            stream = rawStream.onFinalize(release)
          yield (snap, stream)
        }

      def leave(sessionId: SessionId): F[Unit] =
        for
          removed <- presence.modify(m => (m.removed(sessionId), m.contains(sessionId)))
          _ <-
            if removed then topic.publish1(ServerMsg.PeerLeft(sessionId)).void
            else Concurrent[F].unit
        yield ()

      def submitEdit(
          authorSessionId: SessionId,
          baseVersion: Int,
          op: Op
      ): F[Either[String, Int]] =
        mutex.lock.surround {
          // Role check: observers cannot write. We read presence under the mutex so the
          // check is consistent with the OT state being modified below.
          presence.get.map(_.get(authorSessionId).map(_.role).getOrElse(Role.Editor)).flatMap {
            case Role.Observer =>
              (Left("observers cannot submit edits"): Either[String, Int]).pure[F]
            case _ =>
              state.get.flatMap { s =>
                if baseVersion < 0 || baseVersion > s.version then
                  (Left(s"baseVersion $baseVersion out of range [0, ${s.version}]"): Either[String, Int])
                    .pure[F]
                else if Op.isNoop(op) then
                  // Drop trivial / empty edits before they reach OT so we don't bump the version
                  // number for a change nobody can see.
                  (Right(s.version): Either[String, Int]).pure[F]
                else
                  val intervening = s.history.drop(baseVersion)
                  val transformed = intervening.foldLeft(List(op)) { (acc, b) =>
                    acc.flatMap(a => Op.transform(a, b))
                  }
                  Op.applyAll(s.text, transformed) match
                    case Left(reason) =>
                      (Left(reason): Either[String, Int]).pure[F]
                    case Right(newText) =>
                      val newVersion = s.version + transformed.size
                      val newHistory = s.history ++ transformed
                      val newState   = State(newText, newVersion, newHistory)
                      state.set(newState) *>
                        topic
                          .publish1(ServerMsg.Applied(newVersion, transformed, authorSessionId))
                          .as(Right(newVersion): Either[String, Int])
              }
          }
        }

      def submitCursor(sessionId: SessionId, pos: Int, selectionEnd: Int): F[Unit] =
        presence
          .modify { m =>
            m.get(sessionId) match
              case None => (m, None)
              case Some(p) =>
                val updated = p.copy(cursor = pos, selectionEnd = selectionEnd)
                (m.updated(sessionId, updated), Some(updated))
          }
          .flatMap {
            case None => Concurrent[F].unit
            case Some(p) =>
              topic
                .publish1(
                  ServerMsg.CursorUpdate(
                    sessionId = p.sessionId,
                    userId = p.userId,
                    displayName = p.displayName,
                    cursor = p.cursor,
                    selectionEnd = p.selectionEnd
                  )
                )
                .void
          }

      def snapshot: F[(Int, String)] =
        state.get.map(s => (s.version, s.text))

      def peerCount: F[Int] =
        presence.get.map(_.size)
