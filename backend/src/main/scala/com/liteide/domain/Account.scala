package com.liteide.domain

import io.circe.Encoder
import io.circe.syntax.*
import io.circe.Json

import com.liteide.domain.Ids.UserId

/** A registered user account with login credentials.
  *
  * `passwordHash` is a salted PBKDF2 digest (see `PasswordHasher`); the plaintext password is never
  * stored and never leaves the register/login request body. `email` is the unique login handle and
  * is always kept normalised (trimmed + lower-cased) by `AuthService`.
  *
  * The hash must never be serialised to a client — use `AccountView` / `publicJson` for that.
  */
final case class Account(
    id:           UserId,
    email:        String,
    displayName:  String,
    passwordHash: String,
):
  /** Client-safe projection: everything except the password hash. */
  def publicJson: Json =
    Json.obj(
      "id"          -> id.asJson,
      "email"       -> email.asJson,
      "displayName" -> displayName.asJson,
    )

object Account:
  /** Intentionally no `Encoder[Account]`: there is no safe default that includes `passwordHash`.
    * Callers serialise via [[Account.publicJson]] instead.
    */
  given Encoder[Account] = Encoder.instance(_.publicJson)
