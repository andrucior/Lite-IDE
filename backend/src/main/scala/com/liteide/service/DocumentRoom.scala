package com.liteide.service

import cats.effect.kernel.{Concurrent, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic

import com.liteide.domain.{HistoryEntry, Op, Presence, Role}
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

  /** Full edit log with author metadata — used for the history REST endpoint. */
  def historyEntries: F[List[HistoryEntry]]

  /** Reconstruct the document text at any past version for diff computation.
   *  Returns None if targetVersion is outside [0, currentVersion].
   */
  def textAtVersion(targetVersion: Int): F[Option[String]]

  /** Number of currently-connected sessions. */
  def peerCount: F[Int]

object DocumentRoom:

  // A full text snapshot is stored every N versions so that textAtVersion never
  // needs to replay more than N ops. Lower = faster reads, higher memory use.
  private val SnapshotInterval = 100

  private final case class State(
      initialText: String,
      text:        String,
      version:     Int,
      history:     Vector[HistoryEntry],
      snapshots:   Map[Int, String],  // sparse: version -> text at that version
  )

  /** Build a fresh room seeded with the given text (version 0, empty history). */
  def make[F[_]: Concurrent](
      docId: DocumentId,
      initialText: String
  ): F[DocumentRoom[F]] =
    for
      state    <- Ref.of[F, State](State(initialText, initialText, 0, Vector.empty, Map(0 -> initialText)))
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
                  // number for a change nobody can see. The client still observes its own state
                  // is consistent; if it really needs an ack it can issue a real edit.
                  (Right(s.version): Either[String, Int]).pure[F]
                else
                  // Transform `op` against every op applied after `baseVersion`. We do NOT
                  // filter noop products of OT here: keeping them in history preserves the
                  // invariant `history.length == version`, and the author still gets an
                  // `Applied` to ack the edit they submitted.
                  val intervening = s.history.drop(baseVersion).map(_.op)
                  val transformed = intervening.foldLeft(List(op)) { (acc, b) =>
                    acc.flatMap(a => Op.transform(a, b))
                  }
                  // Apply the resulting op list; if any single application fails, abort the
                  // whole edit (the document never goes into an invalid state).
                  Op.applyAll(s.text, transformed) match
                    case Left(reason) =>
                      (Left(reason): Either[String, Int]).pure[F]
                    case Right(newText) =>
                      // Look up the author's display name from presence for the history log.
                      presence.get.flatMap { presMap =>
                        val displayName = presMap.get(authorSessionId).map(_.displayName).getOrElse("unknown")
                        val now         = System.currentTimeMillis()
                        val newEntries  = transformed.zipWithIndex.map { (o, i) =>
                          HistoryEntry(o, authorSessionId, displayName, now, s.version + i + 1)
                        }
                        val newVersion   = s.version + transformed.size
                        val newHistory   = s.history ++ newEntries
                        val newSnapshots =
                          if newVersion % SnapshotInterval == 0 then s.snapshots + (newVersion -> newText)
                          else s.snapshots
                        val newState = State(s.initialText, newText, newVersion, newHistory, newSnapshots)
                        state.set(newState) *>
                          topic
                            .publish1(ServerMsg.Applied(newVersion, transformed, authorSessionId))
                            .as(Right(newVersion): Either[String, Int])
                      }
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

      def historyEntries: F[List[HistoryEntry]] =
        state.get.map(_.history.toList)

      def textAtVersion(targetVersion: Int): F[Option[String]] =
        state.get.map { s =>
          if targetVersion < 0 || targetVersion > s.version then None
          else if targetVersion == s.version then Some(s.text)
          else
            val (snapVersion, snapText) = s.snapshots.filter(_._1 <= targetVersion).maxBy(_._1)
            Op.applyAll(snapText, s.history.slice(snapVersion, targetVersion).map(_.op).toList).toOption
        }

      def peerCount: F[Int] =
        presence.get.map(_.size)
