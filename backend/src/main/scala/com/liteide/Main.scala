package com.liteide

import cats.effect.{ExitCode, IO, IOApp}

import com.liteide.config.AppConfig
import com.liteide.http.HttpServer
import com.liteide.service.{DocumentService, RoomRegistry}

/** Application entry point.
  *
  * Composition root: load config, build services, start HTTP server. Keep this file thin —
  * every concrete decision belongs in its own module.
  */
object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    for
      config <- AppConfig.load[IO]
      docs   <- DocumentService.inMemory[IO]
      // Seed a demo document so a freshly-started server is immediately useful from the
      // frontend without an out-of-band POST.
      _      <- docs.create("welcome", "// Welcome to Lite-IDE\n").void
      rooms  <- RoomRegistry.make[IO]
      _      <- HttpServer.serve[IO](config.http, docs, rooms).useForever
    yield ExitCode.Success
