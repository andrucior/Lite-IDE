package com.liteide.service

import cats.effect.kernel.{Concurrent, Ref, Resource, Sync}
import cats.syntax.all.*
import skunk.*
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*

import com.liteide.db.Codecs
import com.liteide.domain.{Document, Role}
import com.liteide.domain.Ids.{DocumentId, UserId}

/** Cold storage / metadata for documents.
  *
  * `contents` and `version` here are point-in-time snapshots: they're authoritative for
  * documents with no active room, but for a live document the truth is in the
  * `DocumentRoom`. Persisting the live state back to here is `save`.
  *
  * Access is **private by default**: a document is visible and editable only to its owner and to
  * users who have been explicitly added as members. Membership is the permission map
  * `Map[UserId, Role]` per document; the owner is always implicit from `Document.ownerId` and never
  * appears in that map. A user with no owner/membership relationship to a document has no access at
  * all (`accessRole` returns `None`) — that is what makes "each user only sees their own spaces"
  * hold.
  */
trait DocumentService[F[_]]:
  def create(title: String, initialContents: String, ownerId: UserId): F[Document]
  def get(id: DocumentId):                                              F[Option[Document]]

  /** Documents `userId` may access: the ones they own plus the ones they've been added to. */
  def listFor(userId: UserId): F[List[Document]]

  /** Persist the latest version/contents from a live room back into metadata. */
  def save(id: DocumentId, contents: String, version: Int): F[Unit]

  /** Effective role for `userId` on `docId`, or `None` if the user has no access.
    *
    * Resolution order:
    *   1. `Owner` if `userId == doc.ownerId`.
    *   2. Explicit membership entry in the permission map.
    *   3. `None` — not a member, no access.
    *
    * Also `None` for unknown documents.
    */
  def accessRole(docId: DocumentId, userId: UserId): F[Option[Role]]

  /** Add `targetUserId` as a member of `docId` with `role`. Only the owner may call this.
    *
    * Fails (`Left`) if the caller is not the owner, the target is the owner themselves, the role is
    * `Owner`, or the target is already a member (use [[setRole]] to change an existing member's
    * role).
    */
  def addMember(
      docId:        DocumentId,
      actingUserId: UserId,
      targetUserId: UserId,
      role:         Role,
  ): F[Either[String, Unit]]

  /** Change an existing-or-new member's role. Only the owner may call this.
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

  /** Remove a member from `docId`, revoking their access. Only the owner may call this. */
  def removeRole(
      docId:        DocumentId,
      actingUserId: UserId,
      targetUserId: UserId,
  ): F[Either[String, Unit]]

  /** Returns `(ownerId, members)` for the document, or `None` if the document does not exist. The
    * owner entry is not included in the members map.
    */
  def listPermissions(docId: DocumentId): F[Option[(UserId, Map[UserId, Role])]]

object DocumentService:

  /** Rejection reason shared by `addMember`/`setRole`: the `Owner` role is conferred only by
    * `Document.ownerId` and can never be granted through the membership map.
    */
  private val CannotGrantOwnerRole = "cannot grant the Owner role"

  /** In-memory implementation — sufficient for the first vertical slice.
    *
    * Replace with a persisted backend (Postgres + Skunk, SQLite + magnum, …) once the storage layer
    * exists; the interface above is intentionally small to keep that swap cheap.
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

      def listFor(userId: UserId): F[List[Document]] =
        for
          docs  <- docsRef.get
          perms <- permsRef.get
        yield docs.values
          .filter(d => d.ownerId == userId || perms.get(d.id).exists(_.contains(userId)))
          .toList
          .sortBy(_.title)

      def save(id: DocumentId, contents: String, version: Int): F[Unit] =
        docsRef.update { m =>
          m.get(id) match
            case None    => m
            case Some(d) => m.updated(id, d.copy(contents = contents, version = version))
        }

      def accessRole(docId: DocumentId, userId: UserId): F[Option[Role]] =
        for
          docOpt <- docsRef.get.map(_.get(docId))
          perms  <- permsRef.get.map(_.getOrElse(docId, Map.empty))
        yield docOpt.flatMap { doc =>
          if doc.ownerId == userId then Some(Role.Owner)
          else perms.get(userId)
        }

      /** Owner-only guard shared by the mutation operations. Calls `update` with the document's
        * current member map only when `actingUserId` owns `docId`, then atomically stores the
        * returned map (or surfaces the `Left` reason without writing anything).
        */
      private def asOwner(docId: DocumentId, actingUserId: UserId)(
          update: Map[UserId, Role] => Either[String, Map[UserId, Role]]
      ): F[Either[String, Unit]] =
        accessRole(docId, actingUserId).flatMap {
          case Some(Role.Owner) =>
            permsRef.modify { m =>
              update(m.getOrElse(docId, Map.empty)) match
                case Left(err)        => (m, Left(err))
                case Right(newGrants) => (m.updated(docId, newGrants), Right(()))
            }
          case Some(_) => Left("only the owner can change permissions").pure[F]
          case None    => Left("no such document").pure[F]
        }

      def addMember(
          docId:        DocumentId,
          actingUserId: UserId,
          targetUserId: UserId,
          role:         Role,
      ): F[Either[String, Unit]] =
        asOwner(docId, actingUserId) { grants =>
          if targetUserId == actingUserId then Left("you are already the owner")
          else if role == Role.Owner then Left(CannotGrantOwnerRole)
          else if grants.contains(targetUserId) then Left("user is already a member")
          else Right(grants.updated(targetUserId, role))
        }

      def setRole(
          docId:        DocumentId,
          actingUserId: UserId,
          targetUserId: UserId,
          role:         Role,
      ): F[Either[String, Unit]] =
        asOwner(docId, actingUserId) { grants =>
          if targetUserId == actingUserId then Left("cannot change your own role")
          else if role == Role.Owner then Left(CannotGrantOwnerRole)
          else Right(grants.updated(targetUserId, role))
        }

      def removeRole(
          docId:        DocumentId,
          actingUserId: UserId,
          targetUserId: UserId,
      ): F[Either[String, Unit]] =
        asOwner(docId, actingUserId) { grants =>
          if targetUserId == actingUserId then Left("cannot remove your own role")
          else Right(grants.removed(targetUserId))
        }

      def listPermissions(docId: DocumentId): F[Option[(UserId, Map[UserId, Role])]] =
        for
          docOpt <- docsRef.get.map(_.get(docId))
          perms  <- permsRef.get.map(_.getOrElse(docId, Map.empty))
        yield docOpt.map(d => (d.ownerId, perms))

  /** Postgres-backed implementation behind the same trait.
    *
    * The `Map[DocumentId, Map[UserId, Role]]` membership of `inMemory` becomes the
    * `document_members` join table; the owner-only guard and the same validation messages are kept
    * here so callers see identical behaviour. "Already a member" is detected via
    * `ON CONFLICT DO NOTHING` returning a zero-row insert rather than a pre-read of the whole map.
    */
  def postgres[F[_]: Sync](pool: Resource[F, Session[F]]): DocumentService[F] =
    new DocumentService[F]:

      private val insertDoc: Command[Document] =
        sql"""INSERT INTO documents (id, title, contents, version, owner_id)
              VALUES (${Codecs.document})""".command

      private val selectById: Query[DocumentId, Document] =
        sql"""SELECT id, title, contents, version, owner_id
              FROM documents WHERE id = ${Codecs.documentId}""".query(Codecs.document)

      private val listForQ: Query[(UserId, UserId), Document] =
        sql"""SELECT DISTINCT d.id, d.title, d.contents, d.version, d.owner_id
              FROM documents d
              LEFT JOIN document_members m ON m.doc_id = d.id
              WHERE d.owner_id = ${Codecs.userId} OR m.user_id = ${Codecs.userId}
              ORDER BY d.title""".query(Codecs.document)

      private val updateDoc: Command[(String, Int, DocumentId)] =
        sql"""UPDATE documents SET contents = $text, version = $int4
              WHERE id = ${Codecs.documentId}""".command

      private val selectRole: Query[(DocumentId, UserId), Role] =
        sql"""SELECT role FROM document_members
              WHERE doc_id = ${Codecs.documentId} AND user_id = ${Codecs.userId}""".query(Codecs.role)

      private val insertMember: Command[(DocumentId, UserId, Role)] =
        sql"""INSERT INTO document_members (doc_id, user_id, role)
              VALUES (${Codecs.documentId}, ${Codecs.userId}, ${Codecs.role})
              ON CONFLICT (doc_id, user_id) DO NOTHING""".command

      private val upsertMember: Command[(DocumentId, UserId, Role)] =
        sql"""INSERT INTO document_members (doc_id, user_id, role)
              VALUES (${Codecs.documentId}, ${Codecs.userId}, ${Codecs.role})
              ON CONFLICT (doc_id, user_id) DO UPDATE SET role = EXCLUDED.role""".command

      private val deleteMember: Command[(DocumentId, UserId)] =
        sql"""DELETE FROM document_members
              WHERE doc_id = ${Codecs.documentId} AND user_id = ${Codecs.userId}""".command

      private val membersQ: Query[DocumentId, (UserId, Role)] =
        sql"""SELECT user_id, role FROM document_members
              WHERE doc_id = ${Codecs.documentId}""".query(Codecs.userId *: Codecs.role)

      def create(title: String, initialContents: String, ownerId: UserId): F[Document] =
        val doc = Document(DocumentId.random, title, initialContents, 0, ownerId)
        pool.use(_.prepare(insertDoc).flatMap(_.execute(doc))).as(doc)

      def get(id: DocumentId): F[Option[Document]] =
        pool.use(_.prepare(selectById).flatMap(_.option(id)))

      def listFor(userId: UserId): F[List[Document]] =
        pool.use(_.prepare(listForQ).flatMap(_.stream((userId, userId), 64).compile.toList))

      def save(id: DocumentId, contents: String, version: Int): F[Unit] =
        pool.use(_.prepare(updateDoc).flatMap(_.execute((contents, version, id)))).void

      def accessRole(docId: DocumentId, userId: UserId): F[Option[Role]] =
        get(docId).flatMap {
          case None                               => Sync[F].pure(None)
          case Some(doc) if doc.ownerId == userId => Sync[F].pure(Some(Role.Owner))
          case Some(_)                            =>
            pool.use(_.prepare(selectRole).flatMap(_.option((docId, userId))))
        }

      /** Owner-only guard: run `action` only when `actingUserId` owns `docId`, otherwise surface the
        * same reason strings as the in-memory store.
        */
      private def asOwner(docId: DocumentId, actingUserId: UserId)(
          action: => F[Either[String, Unit]]
      ): F[Either[String, Unit]] =
        accessRole(docId, actingUserId).flatMap {
          case Some(Role.Owner) => action
          case Some(_)          => Sync[F].pure(Left("only the owner can change permissions"))
          case None             => Sync[F].pure(Left("no such document"))
        }

      def addMember(
          docId:        DocumentId,
          actingUserId: UserId,
          targetUserId: UserId,
          role:         Role,
      ): F[Either[String, Unit]] =
        asOwner(docId, actingUserId) {
          if targetUserId == actingUserId then Sync[F].pure(Left("you are already the owner"))
          else if role == Role.Owner then Sync[F].pure(Left(CannotGrantOwnerRole))
          else
            pool.use(_.prepare(insertMember).flatMap(_.execute((docId, targetUserId, role)))).map {
              case Completion.Insert(0) => Left("user is already a member")
              case _                    => Right(())
            }
        }

      def setRole(
          docId:        DocumentId,
          actingUserId: UserId,
          targetUserId: UserId,
          role:         Role,
      ): F[Either[String, Unit]] =
        asOwner(docId, actingUserId) {
          if targetUserId == actingUserId then Sync[F].pure(Left("cannot change your own role"))
          else if role == Role.Owner then Sync[F].pure(Left(CannotGrantOwnerRole))
          else pool.use(_.prepare(upsertMember).flatMap(_.execute((docId, targetUserId, role)))).as(Right(()))
        }

      def removeRole(
          docId:        DocumentId,
          actingUserId: UserId,
          targetUserId: UserId,
      ): F[Either[String, Unit]] =
        asOwner(docId, actingUserId) {
          if targetUserId == actingUserId then Sync[F].pure(Left("cannot remove your own role"))
          else pool.use(_.prepare(deleteMember).flatMap(_.execute((docId, targetUserId)))).as(Right(()))
        }

      def listPermissions(docId: DocumentId): F[Option[(UserId, Map[UserId, Role])]] =
        get(docId).flatMap {
          case None      => Sync[F].pure(None)
          case Some(doc) =>
            pool
              .use(_.prepare(membersQ).flatMap(_.stream(docId, 64).compile.toList))
              .map(rows => Some((doc.ownerId, rows.toMap)))
        }
