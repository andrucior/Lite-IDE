package com.liteide.domain

import io.circe.{Encoder, Json}
import io.circe.syntax.*

import com.liteide.domain.Ids.SessionId

/** One entry in the per-document edit log.
  *
  * Stored alongside the raw `Op` in `DocumentRoom.State.history` so the OT pipeline
  * can still index into history by version, while the REST history endpoint can expose
  * who did what and when.
  */
final case class HistoryEntry(
    op:                Op,
    authorSessionId:   SessionId,
    authorDisplayName: String,
    timestamp:         Long,    // epoch millis (System.currentTimeMillis)
    version:           Int,     // document version AFTER this op was applied
)

object HistoryEntry:
  given Encoder[HistoryEntry] = Encoder.instance { h =>
    Json.obj(
      "op"                -> h.op.asJson,
      "authorSessionId"   -> h.authorSessionId.asJson,
      "authorDisplayName" -> h.authorDisplayName.asJson,
      "timestamp"         -> h.timestamp.asJson,
      "version"           -> h.version.asJson,
    )
  }
