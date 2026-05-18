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

import com.liteide.domain.Ids.DocumentId
import com.liteide.domain.HistoryEntry
import com.liteide.service.{DocumentService, RoomRegistry}
import com.liteide.ws.CollabSocket

/** Top-level HTTP routing tree.
  *
  *   - `/health`              — liveness probe.
  *   - `/api/documents`       — REST CRUD over document metadata + snapshots.
  *   - `/ws/documents/:id`    — WebSocket entry into the live collaboration session.
  *
  * Everything is wrapped in a permissive CORS layer so the Vite dev server (different
  * origin) can talk to us during development. Tighten before production.
  */
object Routes:

  private final case class CreateDocumentRequest(title: String, contents: Option[String])
  private object CreateDocumentRequest:
    given Decoder[CreateDocumentRequest] = deriveDecoder[CreateDocumentRequest]

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
      "/api/documents" -> documents[F](docs, rooms),
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

  private def documents[F[_]: Async](docs: DocumentService[F], rooms: RoomRegistry[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    given EntityDecoder[F, CreateDocumentRequest] = jsonOf[F, CreateDocumentRequest]

    object FromV extends QueryParamDecoderMatcher[Int]("from")
    object ToV   extends QueryParamDecoderMatcher[Int]("to")

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
          docs.create(body.title, body.contents.getOrElse("")).flatMap { d =>
            Created(d.asJson)
          }
        }

      // Get history diff -------------------------------------------------
      case GET -> Root / idStr / "history" / "diff" :? FromV(from) +& ToV(to) =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> "invalid id".asJson))
          case Some(id) =>
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

      // Get history ---------------------------------------------------------
      case GET -> Root / idStr / "history" =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> "invalid id".asJson))
          case Some(id) =>
            rooms.get(id).flatMap {
              case None       => Ok(List.empty[HistoryEntry].asJson)
              case Some(room) => room.historyEntries.flatMap(entries => Ok(entries.asJson))
            }

      // Get one -------------------------------------------------------------
      case GET -> Root / idStr =>
        DocumentId.fromString(idStr) match
          case None => NotFound(Json.obj("error" -> "invalid id".asJson))
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

    object UserQ extends OptionalQueryParamDecoderMatcher[String]("user")

    HttpRoutes.of[F] { case GET -> Root / "documents" / idStr :? UserQ(userOpt) =>
      DocumentId.fromString(idStr) match
        case None     => NotFound("invalid document id")
        case Some(id) =>
          CollabSocket.route[F](wsb, rooms, docs, id, userOpt.getOrElse(""))
    }
