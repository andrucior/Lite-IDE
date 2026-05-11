package com.liteide.protocol

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*

import com.liteide.domain.{Op, Presence}
import com.liteide.domain.Ids.{DocumentId, SessionId, UserId}

/** Wire protocol over the collaboration WebSocket.
  *
  * Each message is a JSON object with a `"type"` discriminator. Versions are integers
  * monotonically increasing per document; clients send their `baseVersion` on every edit so
  * the server can transform stale ops via OT.
  */
object Wire:

  // -- Client → server -----------------------------------------------------

  enum ClientMsg derives CanEqual:
    /** Submit a local edit on top of `baseVersion`. */
    case Edit(baseVersion: Int, op: Op)

    /** Update this session's cursor / selection. */
    case Cursor(pos: Int, selectionEnd: Int)

    /** Request a fresh snapshot — e.g. after a perceived desync. */
    case Resync

  object ClientMsg:
    given Decoder[ClientMsg] = Decoder.instance { c =>
      c.downField("type").as[String].flatMap {
        case "edit" =>
          for
            v <- c.downField("baseVersion").as[Int]
            o <- c.downField("op").as[Op]
          yield ClientMsg.Edit(v, o)
        case "cursor" =>
          for
            p <- c.downField("pos").as[Int]
            s <- c.downField("selectionEnd").as[Int]
          yield ClientMsg.Cursor(p, s)
        case "resync" =>
          Right(ClientMsg.Resync)
        case other =>
          Left(DecodingFailure(s"unknown client msg: $other", c.history))
      }
    }

  // -- Server → client -----------------------------------------------------

  enum ServerMsg derives CanEqual:
    /** First message after a successful join: full document state + current peers. */
    case Snapshot(
        documentId: DocumentId,
        sessionId:  SessionId,
        userId:     UserId,
        version:    Int,
        text:       String,
        peers:      List[Presence],
    )

    /** Authoritative broadcast of one or more ops applied at `version`.
      *
      * If the client authored these (compare `authorSessionId` to its own session id), it
      * can use the ack to bump its baseVersion; otherwise it applies them locally.
      */
    case Applied(
        version:         Int,
        ops:             List[Op],
        authorSessionId: SessionId,
    )

    /** A peer updated their cursor / selection. */
    case CursorUpdate(
        sessionId:    SessionId,
        userId:       UserId,
        displayName:  String,
        cursor:       Int,
        selectionEnd: Int,
    )

    /** A new participant joined. */
    case PeerJoined(presence: Presence)

    /** A participant disconnected. */
    case PeerLeft(sessionId: SessionId)

    /** Server-side error reporting (bad op, parse failure, …). */
    case ErrorMsg(reason: String)

  object ServerMsg:
    given Encoder[ServerMsg] = Encoder.instance {
      case ServerMsg.Snapshot(docId, sid, uid, v, t, peers) =>
        Json.obj(
          "type"       -> "snapshot".asJson,
          "documentId" -> docId.asJson,
          "sessionId"  -> sid.asJson,
          "userId"     -> uid.asJson,
          "version"    -> v.asJson,
          "text"       -> t.asJson,
          "peers"      -> peers.asJson,
        )
      case ServerMsg.Applied(v, ops, author) =>
        Json.obj(
          "type"            -> "applied".asJson,
          "version"         -> v.asJson,
          "ops"             -> ops.asJson,
          "authorSessionId" -> author.asJson,
        )
      case ServerMsg.CursorUpdate(sid, uid, name, cur, sel) =>
        Json.obj(
          "type"         -> "cursor".asJson,
          "sessionId"    -> sid.asJson,
          "userId"       -> uid.asJson,
          "displayName"  -> name.asJson,
          "cursor"       -> cur.asJson,
          "selectionEnd" -> sel.asJson,
        )
      case ServerMsg.PeerJoined(p) =>
        Json.obj("type" -> "peerJoined".asJson, "presence" -> p.asJson)
      case ServerMsg.PeerLeft(sid) =>
        Json.obj("type" -> "peerLeft".asJson, "sessionId" -> sid.asJson)
      case ServerMsg.ErrorMsg(reason) =>
        Json.obj("type" -> "error".asJson, "reason" -> reason.asJson)
    }
