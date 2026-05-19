package com.liteide.service

import cats.effect.kernel.{Concurrent, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*

import com.liteide.domain.Ids.DocumentId

/** Maintains the set of live `DocumentRoom`s.
  *
  * Rooms are created lazily on the first join — a document may exist in `DocumentService` (cold
  * metadata) without yet having a live room. A creation mutex ensures we never race two threads
  * into building two rooms for the same id.
  */
trait RoomRegistry[F[_]]:
  def get(docId: DocumentId): F[Option[DocumentRoom[F]]]
  def getOrCreate(docId: DocumentId, initialText: => String): F[DocumentRoom[F]]
  def remove(docId: DocumentId): F[Unit]

object RoomRegistry:

  def make[F[_]: Concurrent]: F[RoomRegistry[F]] =
    for
      ref <- Ref.of[F, Map[DocumentId, DocumentRoom[F]]](Map.empty)
      creation <- Mutex[F]
    yield new RoomRegistry[F]:

      def get(docId: DocumentId): F[Option[DocumentRoom[F]]] =
        ref.get.map(_.get(docId))

      def getOrCreate(docId: DocumentId, initialText: => String): F[DocumentRoom[F]] =
        get(docId).flatMap {
          case Some(r) => r.pure[F]
          case None =>
            creation.lock.surround {
              get(docId).flatMap {
                case Some(r) => r.pure[F]
                case None =>
                  DocumentRoom.make[F](docId, initialText).flatTap { room =>
                    ref.update(_.updated(docId, room))
                  }
              }
            }
        }

      def remove(docId: DocumentId): F[Unit] =
        ref.update(_.removed(docId))
