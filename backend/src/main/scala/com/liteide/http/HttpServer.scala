package com.liteide.http

import cats.effect.Async
import cats.effect.kernel.Resource
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

import com.liteide.config.HttpConfig
import com.liteide.service.{DocumentService, RoomRegistry}

/** Wires up the HTTP server. Routing trees live in their own files under `http/`. */
object HttpServer:

  def serve[F[_]: Async](
      config: HttpConfig,
      docs: DocumentService[F],
      rooms: RoomRegistry[F]
  ): Resource[F, Server] =
    EmberServerBuilder
      .default[F]
      .withHost(Host.fromString(config.host).getOrElse(sys.error(s"bad host: ${config.host}")))
      .withPort(Port.fromInt(config.port).getOrElse(sys.error(s"bad port: ${config.port}")))
      .withHttpWebSocketApp { wsb =>
        Routes.all[F](docs, rooms, wsb).orNotFound
      }
      .build
