package com.liteide.domain

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

import com.liteide.domain.Ids.{DocumentId, SessionId, UserId}

/** A single connected client editing one document. One user may hold several sessions (multiple
  * tabs / devices) — they are tracked independently so cursors don't collide.
  */
final case class Session(
    id: SessionId,
    userId: UserId,
    documentId: DocumentId,
    displayName: String
)

object Session:
  given Encoder[Session] = deriveEncoder[Session]
  given Decoder[Session] = deriveDecoder[Session]

/** Presence info broadcast to other participants in a document.
  *
  * `role` is included so clients can visually distinguish observers from editors
  * and owners without a separate API call.
  */
final case class Presence(
    sessionId:    SessionId,
    userId:       UserId,
    displayName:  String,
    cursor:       Int,
    selectionEnd: Int,
    role:         Role,
)

object Presence:
  given Encoder[Presence] = deriveEncoder[Presence]
  given Decoder[Presence] = deriveDecoder[Presence]
