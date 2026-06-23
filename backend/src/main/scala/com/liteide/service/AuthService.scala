package com.liteide.service

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import com.liteide.domain.Account
import com.liteide.domain.Ids.UserId

/** User accounts plus credential checks.
  *
  * In-memory for the first slice, mirroring `DocumentService.inMemory` — swap for a persisted
  * backend later behind the same trait. Emails are the unique login handle and are normalised
  * (trimmed + lower-cased) so `Alice@x.com` and `alice@x.com` are the same account.
  */
trait AuthService[F[_]]:

  /** Register a new account. `Left` if the (normalised) email is already taken. */
  def register(email: String, displayName: String, password: String): F[Either[String, Account]]

  /** Verify credentials, returning the account on success and `None` on bad email *or* password.
    * The two failures are deliberately indistinguishable to callers.
    */
  def login(email: String, password: String): F[Option[Account]]

  /** Look up an account by id — used by the auth middleware to resolve a verified token. */
  def findById(id: UserId): F[Option[Account]]

object AuthService:

  /** A throwaway hash with the right shape, verified against when no account matches, so that the
    * "unknown email" path costs about the same as the "wrong password" path and doesn't leak which
    * emails are registered via response timing.
    */
  private val DummyHash = PasswordHasher.hash("password-not-in-use")

  def inMemory[F[_]: Sync]: F[AuthService[F]] =
    Ref.of[F, Map[String, Account]](Map.empty).map { accountsRef =>
      new AuthService[F]:

        private def normalize(email: String): String = email.trim.toLowerCase

        def register(email: String, displayName: String, password: String): F[Either[String, Account]] =
          val key = normalize(email)
          if key.isEmpty || password.isEmpty then
            Sync[F].pure(Left("email and password must not be empty"))
          else
            for
              hash    <- Sync[F].delay(PasswordHasher.hash(password))
              account <- Sync[F].delay(Account(UserId.random, key, displayName.trim, hash))
              result  <- accountsRef.modify { accounts =>
                if accounts.contains(key) then (accounts, Left("email already registered"))
                else (accounts.updated(key, account), Right(account))
              }
            yield result

        def login(email: String, password: String): F[Option[Account]] =
          accountsRef.get.flatMap { accounts =>
            accounts.get(normalize(email)) match
              case Some(account) =>
                Sync[F]
                  .delay(PasswordHasher.verify(password, account.passwordHash))
                  .map(Option.when(_)(account))
              case None =>
                // Spend the same work as a real check, then fail, to keep timing uniform.
                Sync[F].delay(PasswordHasher.verify(password, DummyHash)).as(None)
          }

        def findById(id: UserId): F[Option[Account]] =
          accountsRef.get.map(_.values.find(_.id == id))
    }
