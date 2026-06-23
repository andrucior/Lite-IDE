package com.liteide.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.{AuthedRoutes, EntityDecoder, HttpRoutes, Response, Status}
import org.http4s.circe.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.server.websocket.WebSocketBuilder2

import com.liteide.auth.{Auth, JwtAuth}
import com.liteide.domain.{HistoryEntry, Role}
import com.liteide.domain.Ids.{DocumentId, UserId}
import com.liteide.service.{AuthService, DocumentService, RoomRegistry}
import com.liteide.ws.CollabSocket

/** Top-level HTTP routing tree.
  *
  *   - `/health`              — liveness probe (public).
  *   - `/api/auth`            — register / login / logout / me (see `AuthRoutes`).
  *   - `/api/documents`       — REST over document metadata + membership. Every route here requires
  *     a valid auth cookie; the acting user is taken from it, never from the request body.
  *   - `/ws/documents/:id`    — WebSocket entry into the live collaboration session, also gated on
  *     the auth cookie.
  *
  * Everything is wrapped in a CORS layer that reflects the request origin and allows credentials so
  * the Vite dev server (different origin) can send the cookie during development. Tighten before
  * production.
  */
object Routes:

  private val InvalidId = "invalid id"

  private final case class CreateDocumentRequest(
      title:    String,
      contents: Option[String],
  )
  private object CreateDocumentRequest:
    given Decoder[CreateDocumentRequest] = deriveDecoder[CreateDocumentRequest]

  /** Change an existing member's role. The acting user comes from the auth cookie. */
  private final case class SetPermissionRequest(
      userId: String,
      role:   Role,
  )
  private object SetPermissionRequest:
    given Decoder[SetPermissionRequest] = deriveDecoder[SetPermissionRequest]

  /** Add a person to a document by their login email. Role defaults to `Editor`. */
  private final case class AddMemberRequest(
      email: String,
      role:  Option[Role],
  )
  private object AddMemberRequest:
    given Decoder[AddMemberRequest] = deriveDecoder[AddMemberRequest]

  private final case class DocumentSummary(id: String, title: String, version: Int)
  private object DocumentSummary:
    given Encoder[DocumentSummary] = deriveEncoder[DocumentSummary]

  def all[F[_]: Async](
      docs: DocumentService[F],
      rooms: RoomRegistry[F],
      auth: AuthService[F],
      jwt: JwtAuth,
      wsb: WebSocketBuilder2[F]
  ): HttpRoutes[F] =
    val tree = Router(
      "/"              -> health[F],
      "/api/auth"      -> AuthRoutes.routes[F](auth, jwt),
      "/api/documents" -> Auth.middleware[F](jwt).apply(documents[F](docs, rooms, auth)),
      "/ws"            -> websockets[F](docs, rooms, jwt, wsb),
    )
    // Credentials must be allowed (and the request Origin reflected rather than `*`, which the spec
    // forbids alongside credentials) so the browser will send/accept the HttpOnly auth cookie from
    // the Vite dev server's different origin. Tighten the origin predicate before production.
    CORS.policy.withAllowOriginHost(_ => true).withAllowCredentials(true).apply(tree)

  // ------------------------------------------------------------------ health

  private def health[F[_]: Async]: HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case GET -> Root / "health" =>
      Ok("ok")
    }

  // --------------------------------------------------------------- documents

  private def documents[F[_]: Async](
      docs: DocumentService[F],
      rooms: RoomRegistry[F],
      auth: AuthService[F],
  ): AuthedRoutes[UserId, F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    given EntityDecoder[F, CreateDocumentRequest] = jsonOf[F, CreateDocumentRequest]
    given EntityDecoder[F, SetPermissionRequest]  = jsonOf[F, SetPermissionRequest]
    given EntityDecoder[F, AddMemberRequest]      = jsonOf[F, AddMemberRequest]

    object FromV extends QueryParamDecoderMatcher[Int]("from")
    object ToV   extends QueryParamDecoderMatcher[Int]("to")

    /** Parse `idStr`, confirm `userId` may access that document, then run `body`. A bad id, an
      * unknown document, and "no access" all collapse to `404` so we never reveal which documents
      * exist to users who aren't members.
      */
    def withAccess(idStr: String, userId: UserId)(body: DocumentId => F[Response[F]]): F[Response[F]] =
      DocumentId.fromString(idStr) match
        case None => NotFound(Json.obj("error" -> InvalidId.asJson))
        case Some(id) =>
          docs.accessRole(id, userId).flatMap {
            case None    => NotFound(Json.obj("error" -> "no such document".asJson))
            case Some(_) => body(id)
          }

    AuthedRoutes.of[UserId, F] {
      // List — only the caller's own documents ------------------------------
      case GET -> Root as userId =>
        docs.listFor(userId).flatMap { own =>
          val payload = own.map(d => DocumentSummary(d.id.value.toString, d.title, d.version))
          Ok(payload.asJson)
        }

      // Create — owner is the authenticated user ----------------------------
      case req @ POST -> Root as userId =>
        req.req.as[CreateDocumentRequest].flatMap { body =>
          docs.create(body.title, body.contents.getOrElse(""), userId).flatMap(d => Created(d.asJson))
        }

      // Get history diff ----------------------------------------------------
      case GET -> Root / idStr / "history" / "diff" :? FromV(from) +& ToV(to) as userId =>
        withAccess(idStr, userId) { id =>
          rooms.get(id).flatMap {
            case None => NotFound(Json.obj("error" -> "room not active".asJson))
            case Some(room) =>
              (room.textAtVersion(from - 1), room.textAtVersion(to)).tupled.flatMap {
                case (Some(before), Some(after)) =>
                  Ok(Json.obj("before" -> before.asJson, "after" -> after.asJson))
                case _ =>
                  UnprocessableEntity(Json.obj("error" -> "version out of range".asJson))
              }
          }
        }

      // Get history ---------------------------------------------------------
      case GET -> Root / idStr / "history" as userId =>
        withAccess(idStr, userId) { id =>
          rooms.get(id).flatMap {
            case None       => Ok(List.empty[HistoryEntry].asJson)
            case Some(room) => room.historyEntries.flatMap(entries => Ok(entries.asJson))
          }
        }

      // List members --------------------------------------------------------
      case GET -> Root / idStr / "permissions" as userId =>
        withAccess(idStr, userId) { id =>
          docs.listPermissions(id).flatMap {
            case None => NotFound(Json.obj("error" -> "no such document".asJson))
            case Some((ownerId, perms)) =>
              val entries = perms.toList.map { case (uid, role) =>
                Json.obj("userId" -> uid.asJson, "role" -> role.asJson)
              }
              Ok(Json.obj(
                "ownerId"     -> ownerId.asJson,
                "permissions" -> entries.asJson,
              ))
          }
        }

      // Add a member by email (owner only) ----------------------------------
      case req @ POST -> Root / idStr / "members" as userId =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> InvalidId.asJson))
          case Some(docId) =>
            req.req.as[AddMemberRequest].flatMap { body =>
              auth.findByEmail(body.email).flatMap {
                case None => NotFound(Json.obj("error" -> "no user with that email".asJson))
                case Some(account) =>
                  docs.addMember(docId, userId, account.id, body.role.getOrElse(Role.Editor)).flatMap {
                    case Right(_) =>
                      Created(Json.obj("ok" -> true.asJson, "userId" -> account.id.asJson))
                    case Left(err) => Forbidden(Json.obj("error" -> err.asJson))
                  }
              }
            }

      // Change a member's role (owner only) ---------------------------------
      case req @ POST -> Root / idStr / "permissions" as userId =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> InvalidId.asJson))
          case Some(docId) =>
            req.req.as[SetPermissionRequest].flatMap { body =>
              UserId.fromString(body.userId) match
                case Some(targetId) =>
                  docs.setRole(docId, userId, targetId, body.role).flatMap {
                    case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
                    case Left(err) => Forbidden(Json.obj("error" -> err.asJson))
                  }
                case None =>
                  BadRequest(Json.obj("error" -> "invalid userId".asJson))
            }

      // Remove a member (owner only) ----------------------------------------
      case DELETE -> Root / idStr / "permissions" / targetIdStr as userId =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> InvalidId.asJson))
          case Some(docId) =>
            UserId.fromString(targetIdStr) match
              case Some(targetId) =>
                docs.removeRole(docId, userId, targetId).flatMap {
                  case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
                  case Left(err) => Forbidden(Json.obj("error" -> err.asJson))
                }
              case None =>
                BadRequest(Json.obj("error" -> "invalid userId".asJson))

      // Get one -------------------------------------------------------------
      case GET -> Root / idStr as userId =>
        withAccess(idStr, userId) { id =>
          docs.get(id).flatMap {
            case None    => NotFound(Json.obj("error" -> "no such document".asJson))
            case Some(d) => Ok(d.asJson)
          }
        }
    }

  // --------------------------------------------------------------- websockets

  private def websockets[F[_]: Async](
      docs: DocumentService[F],
      rooms: RoomRegistry[F],
      jwt: JwtAuth,
      wsb: WebSocketBuilder2[F]
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    object UserQ extends OptionalQueryParamDecoderMatcher[String]("user")

    HttpRoutes.of[F] { case req @ GET -> Root / "documents" / idStr :? UserQ(userOpt) =>
      DocumentId.fromString(idStr) match
        case None => NotFound("invalid document id")
        case Some(id) =>
          // The collaboration socket is logged-in-only: resolve the user from the JWT auth cookie
          // and refuse the upgrade if it is missing or invalid.
          req.cookies.find(_.name == Auth.CookieName).map(_.content).flatMap(jwt.verify) match
            case None =>
              Response[F](Status.Unauthorized).withEntity("authentication required").pure[F]
            case Some(userId) =>
              CollabSocket.route[F](wsb, rooms, docs, id, userOpt.getOrElse(""), userId)
    }
