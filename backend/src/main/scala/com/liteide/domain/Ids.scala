package com.liteide.domain

import io.circe.{Decoder, Encoder}
import java.util.UUID

/** Strongly-typed identifiers via opaque types — zero runtime cost, no accidental mixing of e.g. a
  * `DocumentId` with a `UserId`.
  */
object Ids:

  opaque type DocumentId = UUID
  object DocumentId:
    def random: DocumentId = UUID.randomUUID()
    def apply(uuid: UUID): DocumentId = uuid
    def fromString(s: String): Option[DocumentId] =
      try Some(UUID.fromString(s))
      catch case _: IllegalArgumentException => None
    extension (id: DocumentId) def value: UUID = id

    given CanEqual[DocumentId, DocumentId] = CanEqual.derived
    given Encoder[DocumentId] = Encoder[UUID].contramap(_.value)
    given Decoder[DocumentId] = Decoder[UUID].map(DocumentId.apply)

  opaque type UserId = UUID
  object UserId:
    def random: UserId = UUID.randomUUID()
    def apply(uuid: UUID): UserId = uuid
    extension (id: UserId) def value: UUID = id

    given CanEqual[UserId, UserId] = CanEqual.derived
    given Encoder[UserId] = Encoder[UUID].contramap(_.value)
    given Decoder[UserId] = Decoder[UUID].map(UserId.apply)

  opaque type SessionId = UUID
  object SessionId:
    def random: SessionId = UUID.randomUUID()
    def apply(uuid: UUID): SessionId = uuid
    extension (id: SessionId) def value: UUID = id

    given CanEqual[SessionId, SessionId] = CanEqual.derived
    given Encoder[SessionId] = Encoder[UUID].contramap(_.value)
    given Decoder[SessionId] = Decoder[UUID].map(SessionId.apply)
