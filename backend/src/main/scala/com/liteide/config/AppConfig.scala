package com.liteide.config

import scala.concurrent.duration.*

import cats.effect.Sync

/** Top-level application configuration.
  *
  * Currently env-var based to avoid pulling a config lib before we need one. Replace with
  * PureConfig / Ciris once the surface grows.
  */
final case class AppConfig(http: HttpConfig, auth: AuthConfig, db: DbConfig)

final case class HttpConfig(host: String, port: Int)

/** Connection settings for the Postgres backend reached via Skunk.
  *
  * `maxSessions` bounds the connection pool — every concurrent query checks out a session, so this
  * is the ceiling on simultaneous DB round-trips. The dev defaults match `docker-compose.yml`.
  */
final case class DbConfig(
   url: String,
   maxSessions: Int
)

/** Settings for the cookie/JWT auth layer.
  *
  *   - `jwtSecret` signs the session token; the dev default is intentionally insecure, so set
  *     `JWT_SECRET` in any real deployment.
  *   - `ttl` is how long an issued session stays valid (cookie `Max-Age` + token `exp`).
  */
final case class AuthConfig(jwtSecret: String, ttl: FiniteDuration)

object AppConfig:

  private val DevJwtSecret = "dev-insecure-secret-change-me"

  def load[F[_]: Sync]: F[AppConfig] =
    Sync[F].delay {
      val host = sys.env.getOrElse("HTTP_HOST", "0.0.0.0")
      val port = sys.env.get("HTTP_PORT").flatMap(_.toIntOption).getOrElse(8090)

      val jwtSecret = sys.env.getOrElse("JWT_SECRET", DevJwtSecret)
      val ttlHours  = sys.env.get("JWT_TTL_HOURS").flatMap(_.toLongOption).getOrElse(24L)

      val db = DbConfig(
        url        = sys.env.getOrElse("DB_URL", "postgresql://liteide:liteide@localhost:5434/liteide"),
        maxSessions = sys.env.get("DB_MAX_SESSIONS").flatMap(_.toIntOption).getOrElse(10),
      )

      AppConfig(HttpConfig(host, port), AuthConfig(jwtSecret, ttlHours.hours), db)
    }
