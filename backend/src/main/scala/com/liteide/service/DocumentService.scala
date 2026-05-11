package com.liteide.service

import cats.effect.kernel.{Concurrent, Ref}
import cats.syntax.all.*

import com.liteide.domain.Document
import com.liteide.domain.Ids.DocumentId

/** Cold storage / metadata for documents.
  *
  * `contents` and `version` here are point-in-time snapshots: they're authoritative for
  * documents with no active room, but for a live document the truth is in the
  * `DocumentRoom`. Persisting the live state back to here is `save`.
  */
trait DocumentService[F[_]]:
  def create(title: String, initialContents: String = ""): F[Document]
  def get(id: DocumentId):                                  F[Option[Document]]
  def list:                                                 F[List[Document]]

  /** Persist the latest version/contents from a live room back into metadata. */
  def save(id: DocumentId, contents: String, version: Int): F[Unit]

object DocumentService:

  /** In-memory implementation — sufficient for the first vertical slice.
    *
    * Replace with a persisted backend (Postgres + Skunk, SQLite + magnum, …) once the
    * storage layer exists; the interface above is intentionally small to keep that swap
    * cheap.
    */
  def inMemory[F[_]: Concurrent]: F[DocumentService[F]] =
    Ref.of[F, Map[DocumentId, Document]](Map.empty).map { ref =>
      new DocumentService[F]:
        def create(title: String, initialContents: String): F[Document] =
          val doc = Document.empty(title).copy(contents = initialContents)
          ref.update(_.updated(doc.id, doc)).as(doc)

        def get(id: DocumentId): F[Option[Document]] =
          ref.get.map(_.get(id))

        def list: F[List[Document]] =
          ref.get.map(_.values.toList.sortBy(_.title))

        def save(id: DocumentId, contents: String, version: Int): F[Unit] =
          ref.update { m =>
            m.get(id) match
              case None    => m
              case Some(d) => m.updated(id, d.copy(contents = contents, version = version))
          }
    }
