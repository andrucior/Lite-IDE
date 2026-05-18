package com.liteide.ws

import cats.effect.Async
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import com.liteide.domain.{Role}
import com.liteide.domain.Ids.{DocumentId, SessionId, UserId}
import com.liteide.protocol.Wire.{ClientMsg, ServerMsg}
import com.liteide.service.{DocumentRoom, DocumentService, RoomRegistry}

/** WebSocket entry point for live collaboration.
  *
  * Lifecycle of one connection:
  *
  *   1. URL is `/ws/documents/:id?user=<name>&userId=<uuid>`.
  *   2. We resolve the effective `Role` for `userId` via `DocumentService`:
  *      Owner if the user created the document, an explicit grant if set, otherwise
  *      the default `Editor` (open-access model).
  *   3. We look up (or lazily create) the `DocumentRoom`.
  *   4. We mint a fresh `SessionId`, register presence with the resolved role, and
  *      ship a `Snapshot` that includes the role so the client can enforce read-only
  *      mode immediately.
  *   5. Inbound frames from `Observer` sessions are silently rejected by `DocumentRoom`.
  *   6. On termination (any reason) we publish `PeerLeft` and persist the snapshot back
  *      into the cold `DocumentService`.
  */
object CollabSocket:

  private val logger = org.slf4j.LoggerFactory.getLogger("com.liteide.ws.CollabSocket")

  def route[F[_]: Async](
      wsb:       WebSocketBuilder2[F],
      rooms:     RoomRegistry[F],
      docs:      DocumentService[F],
      docId:     DocumentId,
      userName:  String,
      userIdOpt: Option[UserId],
  ): F[Response[F]] =
    docs.get(docId).flatMap {
      case None =>
        // No such document — emit a single error frame and close.
        wsb
          .withFilterPingPongs(true)
          .build(
            send    = Stream.emit(textFrame(ServerMsg.ErrorMsg(s"no such document: $docId"))),
            receive = (_: Stream[F, WebSocketFrame]) => Stream.empty,
          )
      case Some(doc) =>
        val userId = userIdOpt.getOrElse(UserId.random)
        docs.getRole(docId, userId).flatMap { role =>
          rooms.getOrCreate(docId, doc.contents).flatMap { room =>
            openConnection(wsb, room, docs, userName, userId, role)
          }
        }
    }

  private def openConnection[F[_]: Async](
      wsb:      WebSocketBuilder2[F],
      room:     DocumentRoom[F],
      docs:     DocumentService[F],
      userName: String,
      userId:   UserId,
      role:     Role,
  ): F[Response[F]] =
    val sessionId = SessionId.random
    val display   = if userName.isBlank then s"anon-${sessionId.value.toString.take(4)}" else userName

    room.join(sessionId, userId, display, role).flatMap { case (snapshot, broadcast) =>

      val outbound: Stream[F, WebSocketFrame] =
        Stream.emit(textFrame(snapshot)) ++
          broadcast
            .filter {
              // Don't echo our own join back at us — the snapshot already lists every
              // peer that was present at handshake time.
              case ServerMsg.PeerJoined(p) => p.sessionId != sessionId
              case _                       => true
            }
            .map(textFrame)

      val inbound: Pipe[F, WebSocketFrame, Unit] =
        _.evalMap {
          case WebSocketFrame.Text(s, _) =>
            decode[ClientMsg](s) match
              case Left(err) =>
                Async[F].delay(logger.debug(s"bad client frame: ${err.getMessage}")).void
              case Right(msg) =>
                handleClientMsg(room, sessionId, msg)
          case _ =>
            Async[F].unit
        }

      // Attach cleanup to the outbound stream's finalizer only. Registering it via both a
      // stream finalizer AND `withOnClose` would run it twice on a normal close — double
      // `leave` is observable as a spurious `PeerLeft` for an already-departed session.
      val finalize: F[Unit] =
        room.leave(sessionId) *>
          room.snapshot.flatMap { case (v, t) => docs.save(room.documentId, t, v) }

      wsb
        .withFilterPingPongs(true)
        .build(outbound.onFinalize(finalize), inbound)
    }

  private def handleClientMsg[F[_]: Async](
      room:      DocumentRoom[F],
      sessionId: SessionId,
      msg:       ClientMsg,
  ): F[Unit] =
    msg match
      case ClientMsg.Edit(baseVersion, op) =>
        room.submitEdit(sessionId, baseVersion, op).flatMap {
          case Right(_) => Async[F].unit
          case Left(reason) =>
            Async[F].delay(logger.debug(s"edit rejected: $reason")).void
        }
      case ClientMsg.Cursor(pos, sel) =>
        room.submitCursor(sessionId, pos, sel)
      case ClientMsg.Resync =>
        Async[F].unit

  private def textFrame(msg: ServerMsg): WebSocketFrame =
    WebSocketFrame.Text(msg.asJson.noSpaces)
