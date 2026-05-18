package com.liteide.service

import cats.effect.kernel.{Concurrent, Ref}
import cats.syntax.all.*

import com.liteide.domain.{Document, Role}
import com.liteide.domain.Ids.{DocumentId, UserId}

/** Cold storage / metadata for documents.
  *
  * `contents` and `version` here are point-in-time snapshots: they're authoritative for
  * documents with no active room, but for a live document the truth is in the
  * `DocumentRoom`. Persisting the live state back to here is `save`.
  *
  * Permissions are stored separately as `Map[UserId, Role]` per document. The owner is
  * always implicit from `Document.ownerId`; additional explicit grants live here.
  * Any user not listed defaults to `Editor` so documents stay open by default — a grant
  * is only needed to *restrict* someone to `Observer`.
  */
trait DocumentService[F[_]]:
  def create(title: String, initialContents: String, ownerId: UserId): F[Document]
  def get(id: DocumentId):                                              F[Option[Document]]
  def list:                                                             F[List[Document]]

  /** Persist the latest version/contents from a live room back into metadata. */
  def save(id: DocumentId, contents: String, version: Int): F[Unit]

  /** Effective role for `userId` on `docId`.
    *
    * Resolution order:
    *   1. Owner if `userId == doc.ownerId`.
    *   2. Explicit entry in the permission map.
    *   3. Default: `Editor` (open document).
    *
    * Returns `Editor` for unknown documents so callers don't need to handle None.
    */
  def getRole(docId: DocumentId, userId: UserId): F[Role]

  /** Set an explicit role for `targetUserId`. Only the owner may call this.
    *
    * Owners cannot change their own role or grant the `Owner` role to others.
    * Returns `Left` with a reason string on any violation.
    */
  def setRole(
      docId:        DocumentId,
      actingUserId: UserId,
      targetUserId: UserId,
      role:         Role,
  ): F[Either[String, Unit]]

  /** Remove an explicit role grant for `targetUserId`, reverting them to the default
    * `Editor` role. Only the owner may call this.
    */
  def removeRole(
      docId:        DocumentId,
      actingUserId: UserId,
      targetUserId: UserId,
  ): F[Either[String, Unit]]

  /** Returns `(ownerId, explicit grants)` for the document, or `None` if the document
    * does not exist. The owner entry is not included in the grants map.
    */
  def listPermissions(docId: DocumentId): F[Option[(UserId, Map[UserId, Role])]]

object DocumentService:

  /** In-memory implementation — sufficient for the first vertical slice.
    *
    * Replace with a persisted backend (Postgres + Skunk, SQLite + magnum, …) once the
    * storage layer exists; the interface above is intentionally small to keep that swap
    * cheap.
    */
  def inMemory[F[_]: Concurrent]: F[DocumentService[F]] =
    for
      docsRef  <- Ref.of[F, Map[DocumentId, Document]](Map.empty)
      permsRef <- Ref.of[F, Map[DocumentId, Map[UserId, Role]]](Map.empty)
    yield new DocumentService[F]:

      def create(title: String, initialContents: String, ownerId: UserId): F[Document] =
        val doc = Document(DocumentId.random, title, initialContents, 0, ownerId)
        docsRef.update(_.updated(doc.id, doc)).as(doc)

      def get(id: DocumentId): F[Option[Document]] =
        docsRef.get.map(_.get(id))

      def list: F[List[Document]] =
        docsRef.get.map(_.values.toList.sortBy(_.title))

      def save(id: DocumentId, contents: String, version: Int): F[Unit] =
        docsRef.update { m =>
          m.get(id) match
            case None    => m
            case Some(d) => m.updated(id, d.copy(contents = contents, version = version))
        }

      def getRole(docId: DocumentId, userId: UserId): F[Role] =
        for
          docOpt <- docsRef.get.map(_.get(docId))
          perms  <- permsRef.get.map(_.getOrElse(docId, Map.empty))
        yield docOpt match
          case None      => Role.Editor
          case Some(doc) =>
            if doc.ownerId == userId then Role.Owner
            else perms.getOrElse(userId, Role.Editor)

      def setRole(
          docId:        DocumentId,
          actingUserId: UserId,
          targetUserId: UserId,
          role:         Role,
      ): F[Either[String, Unit]] =
        getRole(docId, actingUserId).flatMap {
          case Role.Owner =>
            if targetUserId == actingUserId then
              Left("cannot change your own role").pure[F]
            else if role == Role.Owner then
              Left("cannot grant the Owner role").pure[F]
            else
              permsRef
                .update { m =>
                  val grants = m.getOrElse(docId, Map.empty)
                  m.updated(docId, grants.updated(targetUserId, role))
                }
                .as(Right(()))
          case _ =>
            Left("only the owner can change permissions").pure[F]
        }

      def removeRole(
          docId:        DocumentId,
          actingUserId: UserId,
          targetUserId: UserId,
      ): F[Either[String, Unit]] =
        getRole(docId, actingUserId).flatMap {
          case Role.Owner =>
            if targetUserId == actingUserId then
              Left("cannot remove your own role").pure[F]
            else
              permsRef
                .update { m =>
                  val grants = m.getOrElse(docId, Map.empty)
                  m.updated(docId, grants.removed(targetUserId))
                }
                .as(Right(()))
          case _ =>
            Left("only the owner can change permissions").pure[F]
        }

      def listPermissions(docId: DocumentId): F[Option[(UserId, Map[UserId, Role])]] =
        for
          docOpt <- docsRef.get.map(_.get(docId))
          perms  <- permsRef.get.map(_.getOrElse(docId, Map.empty))
        yield docOpt.map(d => (d.ownerId, perms))
