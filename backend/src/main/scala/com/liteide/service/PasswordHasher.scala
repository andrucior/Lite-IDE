package com.liteide.service

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 password hashing, using only the JDK (no bcrypt/scrypt dependency).
  *
  * Stored format is a single self-describing string: `iterations:base64(salt):base64(hash)`.
  * Keeping the iteration count and salt inside the stored value means old hashes stay verifiable
  * even after we bump `Iterations` for new ones.
  *
  * `hash`/`verify` read a `SecureRandom` and run a deliberately slow key-derivation, so callers
  * should wrap them in `Sync[F].delay` rather than treat them as pure.
  */
object PasswordHasher:

  private val Algorithm  = "PBKDF2WithHmacSHA256"
  private val Iterations = 120_000
  private val KeyBits    = 256
  private val SaltBytes  = 16

  private val rng = SecureRandom()

  /** Derive a fresh salted hash for `password`. */
  def hash(password: String): String =
    val salt = new Array[Byte](SaltBytes)
    rng.nextBytes(salt)
    val digest = pbkdf2(password, salt, Iterations)
    val enc    = Base64.getEncoder
    s"$Iterations:${enc.encodeToString(salt)}:${enc.encodeToString(digest)}"

  /** Constant-time check of `password` against a value previously produced by [[hash]]. */
  def verify(password: String, stored: String): Boolean =
    stored.split(':') match
      case Array(iterStr, saltB64, hashB64) =>
        iterStr.toIntOption match
          case Some(iterations) =>
            val dec      = Base64.getDecoder
            val expected = dec.decode(hashB64)
            val actual   = pbkdf2(password, dec.decode(saltB64), iterations)
            MessageDigest.isEqual(expected, actual)
          case None => false
      case _ => false

  private def pbkdf2(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, KeyBits)
    try SecretKeyFactory.getInstance(Algorithm).generateSecret(spec).getEncoded
    finally spec.clearPassword()
