package com.liteide.db

import skunk.*
import skunk.codec.all.*

import com.liteide.domain.{Account, Document, Role}
import com.liteide.domain.Ids.{DocumentId, UserId}

/** Skunk codecs bridging the domain model to Postgres columns.
  *
  * The opaque id types are `UUID` underneath, so they map straight onto the `uuid` codec; `Role` is
  * stored as its lower-cased string form (matching the JSON encoding in `domain.Role`). Column types
  * here must line up with the DDL in [[Db]] — Skunk validates them against the server on `prepare`.
  */
object Codecs:

  val userId: Codec[UserId]         = uuid.imap(UserId(_))(_.value)
  val documentId: Codec[DocumentId] = uuid.imap(DocumentId(_))(_.value)

  val role: Codec[Role] = text.eimap {
    case "owner"    => Right(Role.Owner)
    case "editor"   => Right(Role.Editor)
    case "observer" => Right(Role.Observer)
    case other      => Left(s"unknown role: $other")
  } {
    case Role.Owner    => "owner"
    case Role.Editor   => "editor"
    case Role.Observer => "observer"
  }

  val account: Codec[Account] =
    (userId *: text *: text *: text).imap {
      case (id, email, name, hash) => Account(id, email, name, hash)
    }(a => (a.id, a.email, a.displayName, a.passwordHash))

  val document: Codec[Document] =
    (documentId *: text *: text *: int4 *: userId).imap {
      case (id, title, contents, version, owner) => Document(id, title, contents, version, owner)
    }(d => (d.id, d.title, d.contents, d.version, d.ownerId))
