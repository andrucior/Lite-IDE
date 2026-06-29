package com.liteide.db

import cats.effect.kernel.{MonadCancelThrow, Resource, Temporal}
import cats.effect.std.Console
import cats.syntax.all.*
import fs2.io.net.Network
import natchez.Trace.Implicits.noop
import skunk.*
import skunk.implicits.*
import java.net.URI

import com.liteide.config.DbConfig

/** Connection pool and schema bootstrap for the Postgres backend.
  *
  * `pooled` yields the usual Skunk shape — an outer `Resource` that owns the pool, handing out an
  * inner `Resource[F, Session[F]]` that each query checks out a session from. That inner resource is
  * what the persisted services take. Tracing is wired to the no-op backend; swap in a real
  * `natchez` tracer here if/when we want query spans.
  */
object Db:
  def parse(url: String): (String, Int, String, String, String) =
    val uri = new URI(url)

    val Array(user, pass) = uri.getUserInfo.split(":")
    val host = uri.getHost
    val port = if uri.getPort == -1 then 5432 else uri.getPort
    val db = uri.getPath.drop(1)

    (host, port, user, pass, db)

  def pooled[F[_]: Temporal: Network: Console](cfg: DbConfig)
  : Resource[F, Resource[F, Session[F]]] =

    val (host, port, user, password, db) = parse(cfg.url)
    Session.pooled[F](
      host = host,
      port = port,
      user = user,
      database = db,
      password = Some(password),
      max = cfg.maxSessions
    )

  /** Idempotent schema creation — safe to run on every boot. */
  private val schema: List[Command[Void]] = List(
    sql"""CREATE TABLE IF NOT EXISTS users (
            id            UUID PRIMARY KEY,
            email         TEXT UNIQUE NOT NULL,
            display_name  TEXT NOT NULL,
            password_hash TEXT NOT NULL
          )""".command,
    sql"""CREATE TABLE IF NOT EXISTS documents (
            id        UUID PRIMARY KEY,
            title     TEXT NOT NULL,
            contents  TEXT NOT NULL,
            version   INT  NOT NULL,
            owner_id  UUID NOT NULL REFERENCES users(id)
          )""".command,
    sql"""CREATE TABLE IF NOT EXISTS document_members (
            doc_id   UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
            user_id  UUID NOT NULL REFERENCES users(id),
            role     TEXT NOT NULL,
            PRIMARY KEY (doc_id, user_id)
          )""".command,
  )

  def migrate[F[_]: MonadCancelThrow](pool: Resource[F, Session[F]]): F[Unit] =
    pool.use(session => schema.traverse_(session.execute))
