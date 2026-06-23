package com.liteide

import java.time.Clock

import cats.effect.{ExitCode, IO, IOApp}

import com.liteide.auth.JwtAuth
import com.liteide.config.AppConfig
import com.liteide.http.HttpServer
import com.liteide.service.{AuthService, DocumentService, RoomRegistry}

/** Application entry point.
  *
  * Composition root: load config, build services, start HTTP server. Keep this file thin — every
  * concrete decision belongs in its own module.
  */
object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    for
      config <- AppConfig.load[IO]
      // Documents are now private to their owner/members, so there is no useful "seed" document we
      // could create up-front without a real user to own it — the first document is created by the
      // first logged-in user via `POST /api/documents`.
      docs <- DocumentService.inMemory[IO]
      auth   <- AuthService.inMemory[IO]
      jwt     = JwtAuth(config.auth.jwtSecret, config.auth.ttl, Clock.systemUTC())
      rooms  <- RoomRegistry.make[IO]
      _      <- HttpServer.serve[IO](config.http, docs, rooms, auth, jwt).useForever
    yield ExitCode.Success
