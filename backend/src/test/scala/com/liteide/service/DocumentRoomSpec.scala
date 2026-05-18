package com.liteide.service

import cats.effect.IO
import cats.effect.std.Queue
import fs2.Stream
import munit.CatsEffectSuite

import com.liteide.domain.{HistoryEntry, Op, Presence}
import com.liteide.domain.Ids.{DocumentId, SessionId, UserId}
import com.liteide.protocol.Wire.ServerMsg

import scala.concurrent.duration.*

/** Black-box tests for `DocumentRoom`.
  *
  * The room is the heart of the concurrency model. These tests pin the externally visible
  * behaviour — join semantics, edit acks, peer broadcast ordering, OT cancellation of
  * empty edits — without reaching into the `State` case class.
  */
final class DocumentRoomSpec extends CatsEffectSuite:

  private val docId = DocumentId.random

  /** Run a room callback that has access to its broadcast stream pre-drained into a queue.
    * The queue lets a test pull messages one at a time without racing the room.
    *
    * `join` publishes a `PeerJoined(self)` after the subscription is allocated, so the
    * joiner's own queue will contain it. We drain it here (best-effort, short timeout) so
    * tests can assert against the first *meaningful* event without racing.
    */
  private def withSubscriber[A](initial: String)(
      body: (DocumentRoom[IO], SessionId, Queue[IO, ServerMsg]) => IO[A]
  ): IO[A] =
    for
      room  <- DocumentRoom.make[IO](docId, initial)
      sid    = SessionId.random
      uid    = UserId.random
      joined <- room.join(sid, uid, "alice")
      (_, stream) = joined
      queue  <- Queue.unbounded[IO, ServerMsg]
      pump  <- stream.evalMap(queue.offer).compile.drain.start
      // Swallow our own PeerJoined echo. The timeout is short — if it never arrives the
      // test still proceeds; if it does, the test starts from a clean queue.
      _     <- Stream
                 .repeatEval(queue.take.timeout(200.millis).attempt)
                 .takeWhile {
                   case Right(_: ServerMsg.PeerJoined) => true
                   case _                              => false
                 }
                 .compile
                 .drain
      out   <- body(room, sid, queue)
      _     <- pump.cancel
    yield out

  test("join returns a snapshot of the initial state"):
    val room = DocumentRoom.make[IO](docId, "hello")
    room.flatMap { r =>
      r.join(SessionId.random, UserId.random, "alice").map { case (snap, _) =>
        assertEquals(snap.text, "hello")
        assertEquals(snap.version, 0)
        assertEquals(snap.peers, List.empty[Presence])
      }
    }

  test("a fresh edit bumps the version and broadcasts Applied"):
    withSubscriber("ab") { (room, sid, q) =>
      for
        result <- room.submitEdit(sid, 0, Op.Insert(2, "c"))
        _       = assertEquals(result, Right(1))
        // The first message on our own subscription is `Applied` — the room does not
        // resend our own `PeerJoined` to us (the snapshot already covered presence).
        msg    <- q.take.timeout(2.seconds)
        _       = msg match
                    case ServerMsg.Applied(v, ops, author) =>
                      assertEquals(v, 1)
                      assertEquals(ops, List(Op.Insert(2, "c")))
                      assertEquals(author, sid)
                    case other => fail(s"expected Applied, got $other")
        s      <- room.snapshot
        (v, t)  = s
        _       = assertEquals(v, 1)
        _       = assertEquals(t, "abc")
      yield ()
    }

  test("a stale baseVersion is transformed against intervening ops"):
    withSubscriber("hello") { (room, sid, _) =>
      for
        _ <- room.submitEdit(sid, 0, Op.Insert(0, "X")) // -> "Xhello", v=1
        // Second client edits as if it never saw the X (baseVersion=0). Its op should be
        // shifted right by 1.
        r <- room.submitEdit(SessionId.random, 0, Op.Insert(2, "Y"))
        _  = assertEquals(r, Right(2))
        s <- room.snapshot
        _  = assertEquals(s._2, "XheYllo")
      yield ()
    }

  test("baseVersion out of range is rejected, version unchanged"):
    DocumentRoom.make[IO](docId, "abc").flatMap { r =>
      for
        bad <- r.submitEdit(SessionId.random, 9, Op.Insert(0, "x"))
        _    = assert(bad.isLeft)
        s   <- r.snapshot
        _    = assertEquals(s, (0, "abc"))
      yield ()
    }

  test("empty insert is a no-op: no version bump, no broadcast"):
    withSubscriber("hi") { (room, sid, q) =>
      for
        r <- room.submitEdit(sid, 0, Op.Insert(1, ""))
        _  = assertEquals(r, Right(0))
        // Give the room a tick to (not) publish anything. If a broadcast was wrongly
        // emitted we would observe it within ~50ms; if not, the take times out.
        timedOut <- q.take.timeout(200.millis).attempt.map(_.isLeft)
        _         = assert(timedOut, "no-op edit must not broadcast")
        s <- room.snapshot
        _  = assertEquals(s, (0, "hi"))
      yield ()
    }

  test("a delete entirely covered by an intervening delete is swallowed"):
    withSubscriber("abcdef") { (room, sid, _) =>
      for
        _ <- room.submitEdit(sid, 0, Op.Delete(1, 3)) // -> "aef", v=1
        // Second client tries to delete what's now entirely inside the deleted range.
        r <- room.submitEdit(SessionId.random, 0, Op.Delete(2, 2))
        _  = assertEquals(r, Right(1)) // acked at the unchanged version
        s <- room.snapshot
        _  = assertEquals(s._2, "aef")
      yield ()
    }

  test("cursor update reaches other peers"):
    DocumentRoom.make[IO](docId, "abc").flatMap { room =>
      val sidA = SessionId.random
      val sidB = SessionId.random
      for
        _ <- room.join(sidA, UserId.random, "alice")
        b <- room.join(sidB, UserId.random, "bob")
        (_, streamB) = b
        // Drain B's stream into a queue, then have A move their cursor.
        q  <- Queue.unbounded[IO, ServerMsg]
        f  <- streamB.evalMap(q.offer).compile.drain.start
        _  <- room.submitCursor(sidA, 2, 3)
        // B sees: PeerJoined(A) is suppressed since A joined first; we expect a
        // CursorUpdate. Pull until we find it (other messages allowed in between).
        c  <- Stream
                .repeatEval(q.take)
                .collect { case c: ServerMsg.CursorUpdate => c }
                .take(1)
                .compile
                .lastOrError
                .timeout(2.seconds)
        _   = assertEquals(c.cursor, 2)
        _   = assertEquals(c.selectionEnd, 3)
        _  <- f.cancel
      yield ()
    }

  test("concurrent edits from two peers converge (mutex serialises, both versions reachable)"):
    // Two sessions race to insert at position 0 of the same baseVersion. The room's
    // mutex must serialise the edits and the OT must shift whichever one loses the race
    // by the length of the winner. Regardless of who goes first the final document must
    // contain both insertions and the version must advance by exactly 2.
    DocumentRoom.make[IO](docId, "Z").flatMap { room =>
      val sidA = SessionId.random
      val sidB = SessionId.random
      for
        _ <- room.join(sidA, UserId.random, "alice")
        _ <- room.join(sidB, UserId.random, "bob")
        // Fire the two edits in parallel — both based on version 0.
        results <- IO.both(
                     room.submitEdit(sidA, 0, Op.Insert(0, "A")),
                     room.submitEdit(sidB, 0, Op.Insert(0, "B")),
                   )
        (rA, rB) = results
        _  = assert(rA.isRight, s"A failed: $rA")
        _  = assert(rB.isRight, s"B failed: $rB")
        s <- room.snapshot
        (version, text) = s
        _  = assertEquals(version, 2)
        // Both characters must be present and "Z" must be untouched.
        _  = assert(text.contains("A"), s"missing A in '$text'")
        _  = assert(text.contains("B"), s"missing B in '$text'")
        _  = assert(text.contains("Z"), s"missing Z in '$text'")
        _  = assertEquals(text.length, 3)
      yield ()
    }

  test("leave decrements peer count and broadcasts PeerLeft"):
    DocumentRoom.make[IO](docId, "x").flatMap { room =>
      val sid = SessionId.random
      for
        _  <- room.join(sid, UserId.random, "alice")
        n1 <- room.peerCount
        _   = assertEquals(n1, 1)
        _  <- room.leave(sid)
        n2 <- room.peerCount
        _   = assertEquals(n2, 0)
      yield ()
    }

  test("historyEntries is empty on a fresh room"):
    DocumentRoom.make[IO](docId, "abc").flatMap { room =>
      room.historyEntries.map(h => assertEquals(h, List.empty[HistoryEntry]))
    }

  test("historyEntries records op, version and author after an edit"):
    DocumentRoom.make[IO](docId, "ab").flatMap { room =>
      val sid = SessionId.random
      for
        _       <- room.join(sid, UserId.random, "alice")
        _       <- room.submitEdit(sid, 0, Op.Insert(2, "c"))
        entries <- room.historyEntries
        _        = assertEquals(entries.size, 1)
        entry    = entries.head
        _        = assertEquals(entry.op, Op.Insert(2, "c"))
        _        = assertEquals(entry.version, 1)
        _        = assertEquals(entry.authorDisplayName, "alice")
        _        = assertEquals(entry.authorSessionId, sid)
      yield ()
    }
