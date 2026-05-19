package com.liteide.ws

import cats.effect.Async
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import com.liteide.domain.Ids.{DocumentId, SessionId, UserId}
import com.liteide.protocol.Wire.{ClientMsg, ServerMsg}
import com.liteide.service.{DocumentRoom, DocumentService, RoomRegistry}

/** WebSocket entry point for live collaboration.
  *
  * Lifecycle of one connection:
  *
  *   1. URL is `/ws/documents/:id?user=<name>`. 2. We look up (or lazily create) the
  *      `DocumentRoom`. 3. We mint a fresh `SessionId` / `UserId`, register presence, and ship a
  *      `Snapshot`. 4. Inbound frames are decoded into `ClientMsg` and dispatched onto the room. 5.
  *      The outbound stream is the room's broadcast topic; we drop our own `PeerJoined` to avoid
  *      the obviously-redundant echo, but we keep our own `Applied` because the client uses it as
  *      an authoritative ack. 6. On termination (any reason) we publish `PeerLeft` and persist the
  *      snapshot back into the cold `DocumentService`.
  */
object CollabSocket:

  private val logger = org.slf4j.LoggerFactory.getLogger("com.liteide.ws.CollabSocket")

  def route[F[_]: Async](
      wsb: WebSocketBuilder2[F],
      rooms: RoomRegistry[F],
      docs: DocumentService[F],
      docId: DocumentId,
      userName: String
  ): F[Response[F]] =
    docs.get(docId).flatMap {
      case None =>
        // No such document — emit a single error frame and close. The frontend treats
        // this as a fatal handshake failure.
        wsb
          .withFilterPingPongs(true)
          .build(
            send = Stream.emit(textFrame(ServerMsg.ErrorMsg(s"no such document: $docId"))),
            receive = (_: Stream[F, WebSocketFrame]) => Stream.empty
          )
      case Some(doc) =>
        rooms.getOrCreate(docId, doc.contents).flatMap { room =>
          openConnection(wsb, room, docs, userName)
        }
    }

  private def openConnection[F[_]: Async](
      wsb: WebSocketBuilder2[F],
      room: DocumentRoom[F],
      docs: DocumentService[F],
      userName: String
  ): F[Response[F]] =
    val sessionId = SessionId.random
    val userId = UserId.random
    val display = if userName.isBlank then s"anon-${sessionId.value.toString.take(4)}" else userName

    room.join(sessionId, userId, display).flatMap { case (snapshot, broadcast) =>
      val outbound: Stream[F, WebSocketFrame] =
        Stream.emit(textFrame(snapshot)) ++
          broadcast
            .filter {
              // Don't echo our own join back at us — the snapshot already lists every
              // peer that was present at handshake time.
              case ServerMsg.PeerJoined(p) => p.sessionId != sessionId
              case _ => true
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
      room: DocumentRoom[F],
      sessionId: SessionId,
      msg: ClientMsg
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
        // Per-session reply channel is a follow-up. For now `Resync` is a no-op — clients
        // can reconnect to get a fresh snapshot.
        Async[F].unit

  private def textFrame(msg: ServerMsg): WebSocketFrame =
    WebSocketFrame.Text(msg.asJson.noSpaces)
