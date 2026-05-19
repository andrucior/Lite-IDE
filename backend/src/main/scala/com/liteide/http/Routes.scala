package com.liteide.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.{EntityDecoder, HttpRoutes}
import org.http4s.circe.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.server.websocket.WebSocketBuilder2

import com.liteide.domain.{Role}
import com.liteide.domain.Ids.{DocumentId, UserId}
import com.liteide.service.{DocumentService, RoomRegistry}
import com.liteide.ws.CollabSocket

/** Top-level HTTP routing tree.
  *
  *   - `/health`              — liveness probe.
  *   - `/api/documents`       — REST CRUD over document metadata + permissions.
  *   - `/ws/documents/:id`    — WebSocket entry into the live collaboration session.
  *
  * Everything is wrapped in a permissive CORS layer so the Vite dev server (different
  * origin) can talk to us during development. Tighten before production.
  */
object Routes:

  private val InvalidId = "invalid id"

  private final case class CreateDocumentRequest(
      title:         String,
      contents:      Option[String],
      creatorUserId: Option[String],
  )
  private object CreateDocumentRequest:
    given Decoder[CreateDocumentRequest] = deriveDecoder[CreateDocumentRequest]

  private final case class SetPermissionRequest(
      actingUserId: String,
      userId:       String,
      role:         Role,
  )
  private object SetPermissionRequest:
    given Decoder[SetPermissionRequest] = deriveDecoder[SetPermissionRequest]

  private final case class DocumentSummary(id: String, title: String, version: Int)
  private object DocumentSummary:
    given Encoder[DocumentSummary] = deriveEncoder[DocumentSummary]

  def all[F[_]: Async](
      docs:  DocumentService[F],
      rooms: RoomRegistry[F],
      wsb:   WebSocketBuilder2[F],
  ): HttpRoutes[F] =
    val tree = Router(
      "/"              -> health[F],
      "/api/documents" -> documents[F](docs),
      "/ws"            -> websockets[F](docs, rooms, wsb),
    )
    CORS.policy.withAllowOriginAll.withAllowCredentials(false).apply(tree)

  // ------------------------------------------------------------------ health

  private def health[F[_]: Async]: HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case GET -> Root / "health" =>
      Ok("ok")
    }

  // --------------------------------------------------------------- documents

  private def documents[F[_]: Async](docs: DocumentService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    given EntityDecoder[F, CreateDocumentRequest]  = jsonOf[F, CreateDocumentRequest]
    given EntityDecoder[F, SetPermissionRequest]   = jsonOf[F, SetPermissionRequest]

    object ActingUserQ extends QueryParamDecoderMatcher[String]("actingUserId")

    HttpRoutes.of[F] {
      // List ----------------------------------------------------------------
      case GET -> Root =>
        docs.list.flatMap { all =>
          val payload = all.map { d =>
            DocumentSummary(d.id.value.toString, d.title, d.version)
          }
          Ok(payload.asJson)
        }

      // Create --------------------------------------------------------------
      case req @ POST -> Root =>
        req.as[CreateDocumentRequest].flatMap { body =>
          val ownerId = body.creatorUserId.flatMap(UserId.fromString).getOrElse(UserId.random)
          docs.create(body.title, body.contents.getOrElse(""), ownerId).flatMap { d =>
            Created(d.asJson)
          }
        }

      // List permissions ----------------------------------------------------
      case GET -> Root / idStr / "permissions" =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> InvalidId.asJson))
          case Some(id) =>
            docs.listPermissions(id).flatMap {
              case None => NotFound(Json.obj("error" -> "no such document".asJson))
              case Some((_, perms)) =>
                val entries = perms.toList.map { case (uid, role) =>
                  Json.obj("userId" -> uid.asJson, "role" -> role.asJson)
                }
                Ok(Json.obj(
                  "permissions" -> entries.asJson,
                ))
            }

      // Set permission (owner only) -----------------------------------------
      case req @ POST -> Root / idStr / "permissions" =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> InvalidId.asJson))
          case Some(docId) =>
            req.as[SetPermissionRequest].flatMap { body =>
              (UserId.fromString(body.actingUserId), UserId.fromString(body.userId)) match
                case (Some(actingId), Some(targetId)) =>
                  docs.setRole(docId, actingId, targetId, body.role).flatMap {
                    case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
                    case Left(err) => Forbidden(Json.obj("error" -> err.asJson))
                  }
                case _ =>
                  BadRequest(Json.obj("error" -> "invalid userId".asJson))
            }

      // Remove permission (owner only) --------------------------------------
      case DELETE -> Root / idStr / "permissions" / targetIdStr :? ActingUserQ(actingStr) =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> InvalidId.asJson))
          case Some(docId) =>
            (UserId.fromString(actingStr), UserId.fromString(targetIdStr)) match
              case (Some(actingId), Some(targetId)) =>
                docs.removeRole(docId, actingId, targetId).flatMap {
                  case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
                  case Left(err) => Forbidden(Json.obj("error" -> err.asJson))
                }
              case _ =>
                BadRequest(Json.obj("error" -> "invalid userId".asJson))

      // Get one -------------------------------------------------------------
      case GET -> Root / idStr =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> InvalidId.asJson))
          case Some(id) =>
            docs.get(id).flatMap {
              case None    => NotFound(Json.obj("error" -> "no such document".asJson))
              case Some(d) => Ok(d.asJson)
            }
    }

  // --------------------------------------------------------------- websockets

  private def websockets[F[_]: Async](
      docs:  DocumentService[F],
      rooms: RoomRegistry[F],
      wsb:   WebSocketBuilder2[F],
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    object UserQ   extends OptionalQueryParamDecoderMatcher[String]("user")
    object UserIdQ extends OptionalQueryParamDecoderMatcher[String]("userId")

    HttpRoutes.of[F] { case GET -> Root / "documents" / idStr :? UserQ(userOpt) +& UserIdQ(userIdOpt) =>
      DocumentId.fromString(idStr) match
        case None     => NotFound("invalid document id")
        case Some(id) =>
          val userId = userIdOpt.flatMap(UserId.fromString)
          CollabSocket.route[F](wsb, rooms, docs, id, userOpt.getOrElse(""), userId)
    }
