package com.liteide.http

import cats.effect.kernel.Async
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.{AuthedRoutes, EntityDecoder, HttpRoutes, Response, ResponseCookie, SameSite}

import com.liteide.auth.{Auth, JwtAuth}
import com.liteide.domain.Ids.UserId
import com.liteide.service.AuthService

/** Account lifecycle endpoints, mounted under `/api/auth`:
  *
  *   - `POST /register` — create an account, then sign the caller in.
  *   - `POST /login`    — exchange credentials for a session.
  *   - `POST /logout`   — clear the session cookie.
  *   - `GET  /me`       — (auth-guarded) the current account, used to check/restore a session.
  *
  * Sign-in is stateless: on success we set an HttpOnly JWT cookie (see [[Auth.CookieName]]). The
  * cookie is `HttpOnly` so JavaScript can't read the token, and `SameSite=Lax` to blunt CSRF while
  * still allowing top-level navigations.
  */
object AuthRoutes:

  private final case class RegisterRequest(email: String, displayName: String, password: String)
  private object RegisterRequest:
    given Decoder[RegisterRequest] = deriveDecoder[RegisterRequest]

  private final case class LoginRequest(email: String, password: String)
  private object LoginRequest:
    given Decoder[LoginRequest] = deriveDecoder[LoginRequest]

  def routes[F[_]: Async](auth: AuthService[F], jwt: JwtAuth): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    given EntityDecoder[F, RegisterRequest] = jsonOf[F, RegisterRequest]
    given EntityDecoder[F, LoginRequest]    = jsonOf[F, LoginRequest]

    // ---- public (no session required) -----------------------------------
    val public = HttpRoutes.of[F] {
      case req @ POST -> Root / "register" =>
        req.as[RegisterRequest].flatMap { body =>
          auth.register(body.email, body.displayName, body.password).flatMap {
            case Left(err)      => Conflict(Json.obj("error" -> err.asJson))
            case Right(account) => Created(account.publicJson).flatMap(startSession(jwt, account.id))
          }
        }

      case req @ POST -> Root / "login" =>
        req.as[LoginRequest].flatMap { body =>
          auth.login(body.email, body.password).flatMap {
            case None          => Forbidden(Json.obj("error" -> "invalid credentials".asJson))
            case Some(account) => Ok(account.publicJson).flatMap(startSession(jwt, account.id))
          }
        }

      case POST -> Root / "logout" =>
        Ok(Json.obj("ok" -> true.asJson)).map(_.addCookie(expiredCookie))
    }

    // ---- guarded by the JWT cookie via AuthedRoutes ---------------------
    val authedUser: AuthedRoutes[UserId, F] = AuthedRoutes.of {
      case GET -> Root / "me" as userId =>
        auth.findById(userId).flatMap {
          case Some(account) => Ok(account.publicJson)
          case None          => unauthorized(Json.obj("error" -> "account no longer exists".asJson))
        }
    }

    public <+> Auth.middleware[F](jwt).apply(authedUser)

  /** Issue a token for `userId` and attach it as the session cookie on `resp`. */
  private def startSession[F[_]: Async](jwt: JwtAuth, userId: UserId)(resp: Response[F]): F[Response[F]] =
    Async[F].delay(jwt.issue(userId)).map { token =>
      resp.addCookie(sessionCookie(token, jwt.maxAgeSeconds))
    }

  private def sessionCookie(token: String, maxAgeSeconds: Long): ResponseCookie =
    ResponseCookie(
      name     = Auth.CookieName,
      content  = token,
      httpOnly = true,
      secure   = false, // dev runs over plain http; flip to true behind TLS
      sameSite = Some(SameSite.None),
      path     = Some("/"),
      maxAge   = Some(maxAgeSeconds),
    )

  /** Same name/path as [[sessionCookie]] but already expired, so the browser drops it on logout. */
  private val expiredCookie: ResponseCookie =
    ResponseCookie(
      name     = Auth.CookieName,
      content  = "",
      httpOnly = true,
      secure   = false,
      sameSite = Some(SameSite.None),
      path     = Some("/"),
      maxAge   = Some(0L),
    )

  /** http4s' `Unauthorized` DSL forces a `WWW-Authenticate` challenge we don't use for cookie auth,
    * so build the 401 body directly.
    */
  private def unauthorized[F[_]: Async](body: Json): F[Response[F]] =
    Async[F].pure(Response[F](org.http4s.Status.Unauthorized).withEntity(body))
