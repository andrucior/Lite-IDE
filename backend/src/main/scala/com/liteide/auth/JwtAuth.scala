package com.liteide.auth

import java.time.{Clock, Instant}
import scala.concurrent.duration.FiniteDuration

import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim, JwtOptions}

import com.liteide.domain.Ids.UserId

/** Issues and verifies the JWT that rides inside the auth cookie.
  *
  * HS256 with a single server-side secret: the token's only job is to carry a verified `UserId`, so
  * the subject (`sub`) claim holds it and nothing else sensitive lives in the payload.
  *
  * Time comes from the injected `clock` (injectable so tests can fast-forward). We stamp `iat`/`exp`
  * from it on `issue`, and on `verify` we re-check expiry against it ourselves — jwt-scala's built-in
  * expiry check would otherwise silently use the system clock, ignoring the injected one.
  *
  * `issue`/`verify` read the clock, so they are not referentially transparent — call sites wrap them
  * in `Sync[F].delay`.
  */
final class JwtAuth(secret: String, ttl: FiniteDuration, clock: Clock):

  private val algorithm = JwtAlgorithm.HS256

  // Verify the signature, but check `exp` ourselves against `clock` (see class doc).
  private val decodeOptions = JwtOptions(expiration = false, notBefore = false)

  /** Seconds the cookie/token stays valid — used for the cookie's `Max-Age`. */
  val maxAgeSeconds: Long = ttl.toSeconds

  /** Mint a signed token whose subject is `userId`, valid for `ttl` from now. */
  def issue(userId: UserId): String =
    val now   = Instant.now(clock)
    val claim = JwtClaim(
      subject    = Some(userId.value.toString),
      issuedAt   = Some(now.getEpochSecond),
      expiration = Some(now.getEpochSecond + ttl.toSeconds),
    )
    JwtCirce.encode(claim, secret, algorithm)

  /** Verify signature + expiry and recover the `UserId`. `None` for any tampered, expired, or
    * malformed token.
    */
  def verify(token: String): Option[UserId] =
    JwtCirce
      .decode(token, secret, Seq(algorithm), decodeOptions)
      .toOption
      .filter(claim => claim.expiration.forall(Instant.now(clock).getEpochSecond < _))
      .flatMap(_.subject)
      .flatMap(UserId.fromString)
