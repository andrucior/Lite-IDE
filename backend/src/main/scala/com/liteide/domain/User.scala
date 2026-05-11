package com.liteide.domain

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

import com.liteide.domain.Ids.UserId

final case class User(id: UserId, displayName: String)

object User:
  given Encoder[User] = deriveEncoder[User]
  given Decoder[User] = deriveDecoder[User]

/** Per-document role. Drives the permission system (owner / editor / observer)
  * mentioned in the README.
  */
enum Role derives CanEqual:
  case Owner, Editor, Observer

object Role:
  given Encoder[Role] = Encoder[String].contramap {
    case Role.Owner    => "owner"
    case Role.Editor   => "editor"
    case Role.Observer => "observer"
  }
  given Decoder[Role] = Decoder[String].emap {
    case "owner"    => Right(Role.Owner)
    case "editor"   => Right(Role.Editor)
    case "observer" => Right(Role.Observer)
    case other      => Left(s"unknown role: $other")
  }
