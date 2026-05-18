package com.liteide.domain

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

import com.liteide.domain.Ids.{DocumentId, UserId}

/** Metadata + current snapshot for a collaboratively-edited document.
  *
  * The authoritative live state — including operation history needed for OT — lives in
  * `DocumentRoom`. This type is what the REST layer hands out and what cold storage will
  * eventually persist.
  */
final case class Document(
    id:       DocumentId,
    title:    String,
    contents: String,
    version:  Int,
    ownerId:  UserId,
)

object Document:
  def empty(title: String, ownerId: UserId): Document =
    Document(DocumentId.random, title, "", 0, ownerId)

  given Encoder[Document] = deriveEncoder[Document]
  given Decoder[Document] = deriveDecoder[Document]
