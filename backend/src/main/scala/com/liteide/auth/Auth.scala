package com.liteide.auth

import cats.data.{Kleisli, OptionT}
import cats.effect.kernel.Sync
import io.circe.Json
import io.circe.syntax.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.server.AuthMiddleware
import org.http4s.{AuthedRoutes, Request, Response, Status}

import com.liteide.domain.Ids.UserId

/** Cookie-based authentication wiring shared by the route trees. */
object Auth:

  /** Name of the HttpOnly cookie that carries the JWT. */
  val CookieName = "lite_ide_auth"

  /** Build the `AuthMiddleware` that turns the auth cookie into a verified `UserId`.
    *
    * It reads the JWT from the cookie, verifies it with [[JwtAuth]], and hands the resulting
    * `UserId` to the wrapped `AuthedRoutes`. A missing, malformed, or expired token short-circuits
    * to `401 Unauthorized` without ever reaching the protected route.
    */
  def middleware[F[_]: Sync](jwt: JwtAuth): AuthMiddleware[F, UserId] =
    val verify: Kleisli[F, Request[F], Either[String, UserId]] =
      Kleisli { req =>
        Sync[F].delay {
          req.cookies
            .find(_.name == CookieName)
            .map(_.content)
            .flatMap(jwt.verify)
            .toRight("unauthenticated")
        }
      }

    val onFailure: AuthedRoutes[String, F] =
      Kleisli { req =>
        OptionT.pure[F](
          Response[F](Status.Unauthorized)
            .withEntity(Json.obj("error" -> req.context.asJson))
        )
      }

    AuthMiddleware(verify, onFailure)
