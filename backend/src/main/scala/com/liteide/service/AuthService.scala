package com.liteide.service

import cats.effect.kernel.{Ref, Resource, Sync}
import cats.syntax.all.*
import skunk.*
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*

import com.liteide.db.Codecs
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

  /** Look up an account by its (case-insensitive) login email — used to add people to a document
    * by email rather than by raw user id.
    */
  def findByEmail(email: String): F[Option[Account]]

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

        def findByEmail(email: String): F[Option[Account]] =
          accountsRef.get.map(_.get(normalize(email)))
    }

  /** Postgres-backed implementation behind the same trait — see `DocumentService.postgres` for the
    * companion store. Accounts live in the `users` table; the email uniqueness rule that `inMemory`
    * enforces with a `Map` key is delegated to the `UNIQUE` constraint via `ON CONFLICT DO NOTHING`.
    */
  def postgres[F[_]: Sync](pool: Resource[F, Session[F]]): AuthService[F] =
    new AuthService[F]:

      private def normalize(email: String): String = email.trim.toLowerCase

      private val insertUser: Command[Account] =
        sql"""INSERT INTO users (id, email, display_name, password_hash)
              VALUES (${Codecs.account})
              ON CONFLICT (email) DO NOTHING""".command

      private val selectByEmail: Query[String, Account] =
        sql"""SELECT id, email, display_name, password_hash
              FROM users WHERE email = $text""".query(Codecs.account)

      private val selectById: Query[UserId, Account] =
        sql"""SELECT id, email, display_name, password_hash
              FROM users WHERE id = ${Codecs.userId}""".query(Codecs.account)

      def register(email: String, displayName: String, password: String): F[Either[String, Account]] =
        val key = normalize(email)
        if key.isEmpty || password.isEmpty then
          Sync[F].pure(Left("email and password must not be empty"))
        else
          for
            hash    <- Sync[F].delay(PasswordHasher.hash(password))
            account  = Account(UserId.random, key, displayName.trim, hash)
            result  <- pool.use(_.prepare(insertUser).flatMap(_.execute(account))).map {
                         case Completion.Insert(0) => Left("email already registered")
                         case _                    => Right(account)
                       }
          yield result

      def login(email: String, password: String): F[Option[Account]] =
        pool.use(_.prepare(selectByEmail).flatMap(_.option(normalize(email)))).flatMap {
          case Some(account) =>
            Sync[F].delay(PasswordHasher.verify(password, account.passwordHash)).map(Option.when(_)(account))
          case None =>
            // Spend the same work as a real check, then fail, to keep timing uniform.
            Sync[F].delay(PasswordHasher.verify(password, DummyHash)).as(None)
        }

      def findById(id: UserId): F[Option[Account]] =
        pool.use(_.prepare(selectById).flatMap(_.option(id)))

      def findByEmail(email: String): F[Option[Account]] =
        pool.use(_.prepare(selectByEmail).flatMap(_.option(normalize(email))))
