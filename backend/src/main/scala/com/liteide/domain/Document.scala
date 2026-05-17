package com.liteide.domain

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

import com.liteide.domain.Ids.DocumentId

/** Metadata + current snapshot for a collaboratively-edited document.
  *
  * The authoritative live state — including operation history needed for OT — lives in
  * `DocumentRoom`. This type is what the REST layer hands out and what cold storage will eventually
  * persist.
  */
final case class Document(
    id: DocumentId,
    title: String,
    contents: String,
    version: Int
)

object Document:
  def empty(title: String): Document =
    Document(DocumentId.random, title, "", 0)

  given Encoder[Document] = deriveEncoder[Document]
  given Decoder[Document] = deriveDecoder[Document]
