package com.liteide.config

import cats.effect.Sync

/** Top-level application configuration.
  *
  *  Currently env-var based to avoid pulling a config lib before we need one.
  *  Replace with PureConfig / Ciris once the surface grows.
  */
final case class AppConfig(http: HttpConfig)

final case class HttpConfig(host: String, port: Int)

object AppConfig:

  def load[F[_]: Sync]: F[AppConfig] =
    Sync[F].delay {
      val host = sys.env.getOrElse("HTTP_HOST", "0.0.0.0")
      val port = sys.env.get("HTTP_PORT").flatMap(_.toIntOption).getOrElse(8080)
      AppConfig(HttpConfig(host, port))
    }
