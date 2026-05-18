package com.liteide.protocol

import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import munit.FunSuite

import com.liteide.domain.{HistoryEntry, Op, Presence}
import com.liteide.domain.Ids.{DocumentId, SessionId, UserId}
import com.liteide.protocol.Wire.{ClientMsg, ServerMsg}

import java.util.UUID

/** Wire-format tests — pinning the JSON shape that the frontend will be coded against.
  *
  * These tests are deliberately literal: the React client builds and parses these exact
  * strings, so if the JSON shape drifts they should be the first thing to break.
  */
final class WireSpec extends FunSuite:

  // -- ClientMsg ----------------------------------------------------------

  test("decode client edit"):
    val j =
      """{"type":"edit","baseVersion":3,"op":{"type":"insert","pos":2,"text":"hi"}}"""
    assertEquals(
      decode[ClientMsg](j),
      Right(ClientMsg.Edit(3, Op.Insert(2, "hi"))),
    )

  test("decode client cursor"):
    val j = """{"type":"cursor","pos":5,"selectionEnd":9}"""
    assertEquals(decode[ClientMsg](j), Right(ClientMsg.Cursor(5, 9)))

  test("decode client resync"):
    assertEquals(decode[ClientMsg]("""{"type":"resync"}"""), Right(ClientMsg.Resync))

  test("decode rejects unknown client message type"):
    assert(decode[ClientMsg]("""{"type":"yolo"}""").isLeft)

  // -- ServerMsg ----------------------------------------------------------

  test("encode snapshot has the agreed field names"):
    val docId = DocumentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val sid   = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val uid   = UserId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
    val snap: ServerMsg =
      ServerMsg.Snapshot(docId, sid, uid, version = 7, text = "hi", peers = Nil)
    val j     = snap.asJson
    assertEquals(j.hcursor.downField("type").as[String], Right("snapshot"))
    assertEquals(j.hcursor.downField("version").as[Int], Right(7))
    assertEquals(j.hcursor.downField("text").as[String], Right("hi"))

  test("encode applied carries every op verbatim"):
    val sid = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val msg: ServerMsg = ServerMsg.Applied(version = 4, ops = List(Op.Insert(0, "x")), authorSessionId = sid)
    val j   = msg.asJson
    assertEquals(j.hcursor.downField("type").as[String], Right("applied"))
    val opsArr = j.hcursor.downField("ops").values.toList.flatten
    assertEquals(opsArr.size, 1)
    assertEquals(opsArr.head.hcursor.downField("text").as[String], Right("x"))

  test("encode cursor update"):
    val sid = SessionId(UUID.randomUUID())
    val uid = UserId(UUID.randomUUID())
    val msg: ServerMsg = ServerMsg.CursorUpdate(sid, uid, "alice", cursor = 12, selectionEnd = 15)
    val j   = msg.asJson
    assertEquals(j.hcursor.downField("type").as[String], Right("cursor"))
    assertEquals(j.hcursor.downField("displayName").as[String], Right("alice"))

  test("encode error message"):
    val msg: ServerMsg = ServerMsg.ErrorMsg("nope")
    val j = msg.asJson
    assertEquals(j.hcursor.downField("type").as[String], Right("error"))
    assertEquals(j.hcursor.downField("reason").as[String], Right("nope"))

  test("encode peer joined / peer left round-trip through parse"):
    val sid = SessionId(UUID.randomUUID())
    val uid = UserId(UUID.randomUUID())
    val p   = Presence(sid, uid, "bob", cursor = 0, selectionEnd = 0)
    val m1: ServerMsg = ServerMsg.PeerJoined(p)
    val m2: ServerMsg = ServerMsg.PeerLeft(sid)
    val j1  = m1.asJson.noSpaces
    val j2  = m2.asJson.noSpaces
    // We re-parse to defend against accidental noSpaces serialiser changes, and pin the
    // `type` discriminator value so the frontend's switch doesn't silently break.
    assertEquals(parse(j1).toOption.flatMap(_.hcursor.downField("type").as[String].toOption), Some("peerJoined"))
    assertEquals(parse(j2).toOption.flatMap(_.hcursor.downField("type").as[String].toOption), Some("peerLeft"))

  test("encode HistoryEntry has the agreed field names"):
    val sid   = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val entry = HistoryEntry(Op.Insert(3, "hi"), sid, "alice", timestamp = 1_000_000L, version = 5)
    val j     = entry.asJson
    assertEquals(j.hcursor.downField("version").as[Int],           Right(5))
    assertEquals(j.hcursor.downField("authorDisplayName").as[String], Right("alice"))
    assertEquals(j.hcursor.downField("timestamp").as[Long],        Right(1_000_000L))
    val op = j.hcursor.downField("op")
    assertEquals(op.downField("type").as[String], Right("insert"))
    assertEquals(op.downField("pos").as[Int],     Right(3))
    assertEquals(op.downField("text").as[String], Right("hi"))
