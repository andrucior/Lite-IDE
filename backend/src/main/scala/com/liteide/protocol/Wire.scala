package com.liteide.protocol

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*

import com.liteide.domain.{Diagnostic, Op, Presence, Role, Severity}
import com.liteide.domain.Ids.{DocumentId, SessionId, UserId}

/** Wire protocol over the collaboration WebSocket.
  *
  * Each message is a JSON object with a `"type"` discriminator. Versions are integers monotonically
  * increasing per document; clients send their `baseVersion` on every edit so the server can
  * transform stale ops via OT.
  */
object Wire:

  // -- Client → server -----------------------------------------------------

  enum ClientMsg derives CanEqual:
    /** Submit a local edit on top of `baseVersion`. */
    case Edit(baseVersion: Int, op: Op)

    /** Update this session's cursor / selection. */
    case Cursor(pos: Int, selectionEnd: Int)

    /** Announce the editor language for this document. When it is `"scala"` the server runs live
      * diagnostics; any other value clears them. Language is document-global (the text is shared),
      * so the last writer wins.
      */
    case SetLanguage(language: String)

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
        case "setLanguage" =>
          c.downField("language").as[String].map(ClientMsg.SetLanguage(_))
        case "resync" =>
          Right(ClientMsg.Resync)
        case other =>
          Left(DecodingFailure(s"unknown client msg: $other", c.history))
      }
    }

  // -- Server → client -----------------------------------------------------

  enum ServerMsg derives CanEqual:
    /** First message after a successful join: full document state + current peers.
      *
      * `role` is the current user's role on this document so the frontend can immediately enforce
      * read-only mode for observers and show the owner controls.
      */
    case Snapshot(
        documentId: DocumentId,
        sessionId: SessionId,
        userId: UserId,
        version: Int,
        text: String,
        peers: List[Presence],
        role: Role
    )

    /** Authoritative broadcast of one or more ops applied at `version`.
      *
      * If the client authored these (compare `authorSessionId` to its own session id), it can use
      * the ack to bump its baseVersion; otherwise it applies them locally.
      */
    case Applied(
        version: Int,
        ops: List[Op],
        authorSessionId: SessionId
    )

    /** A peer updated their cursor / selection. */
    case CursorUpdate(
        sessionId: SessionId,
        userId: UserId,
        displayName: String,
        cursor: Int,
        selectionEnd: Int
    )

    /** A new participant joined. */
    case PeerJoined(presence: Presence)

    /** A participant disconnected. */
    case PeerLeft(sessionId: SessionId)

    /** An owner changed a connected user's role live, or revoked their access entirely
      * (`role = None`). Clients update that user's editing rights immediately; the affected
      * user themselves leaves the document when their access is revoked.
      */
    case RoleChanged(userId: UserId, role: Option[Role])

    /** Compiler diagnostics for the document at `version`. Broadcast to every session in the room
      * whenever the (debounced) type-check finishes; an empty list clears the markers.
      */
    case Diagnostics(version: Int, diagnostics: List[Diagnostic])

    /** Server-side error reporting (bad op, parse failure, …). */
    case ErrorMsg(reason: String)

  object ServerMsg:
    private given Encoder[Severity] = Encoder.encodeString.contramap {
      case Severity.Error => "error"
      case Severity.Warning => "warning"
      case Severity.Info => "info"
    }

    private given Encoder[Diagnostic] = Encoder.instance { d =>
      Json.obj(
        "severity" -> d.severity.asJson,
        "message" -> d.message.asJson,
        "startLine" -> d.startLine.asJson,
        "startCol" -> d.startCol.asJson,
        "endLine" -> d.endLine.asJson,
        "endCol" -> d.endCol.asJson
      )
    }

    given Encoder[ServerMsg] = Encoder.instance {
      case ServerMsg.Snapshot(docId, sid, uid, v, t, peers, role) =>
        Json.obj(
          "type" -> "snapshot".asJson,
          "documentId" -> docId.asJson,
          "sessionId" -> sid.asJson,
          "userId" -> uid.asJson,
          "version" -> v.asJson,
          "text" -> t.asJson,
          "peers" -> peers.asJson,
          "role" -> role.asJson
        )
      case ServerMsg.Applied(v, ops, author) =>
        Json.obj(
          "type" -> "applied".asJson,
          "version" -> v.asJson,
          "ops" -> ops.asJson,
          "authorSessionId" -> author.asJson
        )
      case ServerMsg.CursorUpdate(sid, uid, name, cur, sel) =>
        Json.obj(
          "type" -> "cursor".asJson,
          "sessionId" -> sid.asJson,
          "userId" -> uid.asJson,
          "displayName" -> name.asJson,
          "cursor" -> cur.asJson,
          "selectionEnd" -> sel.asJson
        )
      case ServerMsg.PeerJoined(p) =>
        Json.obj("type" -> "peerJoined".asJson, "presence" -> p.asJson)
      case ServerMsg.PeerLeft(sid) =>
        Json.obj("type" -> "peerLeft".asJson, "sessionId" -> sid.asJson)
      case ServerMsg.RoleChanged(uid, roleOpt) =>
        Json.obj(
          "type"   -> "roleChanged".asJson,
          "userId" -> uid.asJson,
          "role"   -> roleOpt.asJson, // null when access is revoked
        )
      case ServerMsg.Diagnostics(version, diagnostics) =>
        Json.obj(
          "type" -> "diagnostics".asJson,
          "version" -> version.asJson,
          "diagnostics" -> diagnostics.asJson
        )
      case ServerMsg.ErrorMsg(reason) =>
        Json.obj("type" -> "error".asJson, "reason" -> reason.asJson)
    }
