package com.liteide

import java.time.Clock

import cats.effect.{ExitCode, IO, IOApp, Resource}

import com.liteide.auth.JwtAuth
import com.liteide.config.AppConfig
import com.liteide.db.Db
import com.liteide.http.HttpServer
import com.liteide.service.{AuthService, DocumentService, RoomRegistry, ScalaDiagnostics}

/** Application entry point.
  *
  * Composition root: load config, build services, start HTTP server. Keep this file thin — every
  * concrete decision belongs in its own module.
  */
object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    AppConfig.load[IO].flatMap { config =>
      // Composition root: own the connection pool + server for the process lifetime. Documents are
      // private to their owner/members, so there is no useful "seed" document to create up-front
      // without a real user — the first document is created by the first logged-in user via
      // `POST /api/documents`.
      val server: Resource[IO, Unit] =
        for
          pool   <- Db.pooled[IO](config.db)
          _      <- Resource.eval(Db.migrate(pool))
          docs    = DocumentService.postgres[IO](pool)
          auth    = AuthService.postgres[IO](pool)
          jwt     = JwtAuth(config.auth.jwtSecret, config.auth.ttl, Clock.systemUTC())
          diags  <- Resource.eval(ScalaDiagnostics.make[IO]())
          rooms  <- Resource.eval(RoomRegistry.make[IO](diags))
          _      <- HttpServer.serve[IO](config.http, docs, rooms, auth, jwt)
        yield ()
      server.useForever.as(ExitCode.Success)
    }
