package com.liteide.domain

import cats.syntax.all.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*

/** A primitive text operation in the collaborative document.
  *
  * Two operations are enough for plain-text editing; richer formatting can be modelled on top
  * later. Positions are character indices into the document's current text (UTF-16 code units —
  * same as JavaScript / Monaco, so the frontend doesn't have to translate).
  */
enum Op derives CanEqual:
  case Insert(pos: Int, text: String)
  case Delete(pos: Int, length: Int)

object Op:

  /** Apply `op` to `text`. Returns Left with a reason if the operation is out of range. */
  def applyTo(text: String, op: Op): Either[String, String] =
    op match
      case Op.Insert(pos, t) =>
        if pos < 0 || pos > text.length then
          Left(s"insert out of range: pos=$pos len=${text.length}")
        else Right(text.substring(0, pos) ++ t ++ text.substring(pos))
      case Op.Delete(pos, len) =>
        if pos < 0 || len < 0 || pos + len > text.length then
          Left(s"delete out of range: pos=$pos len=$len total=${text.length}")
        else Right(text.substring(0, pos) ++ text.substring(pos + len))

  /** Apply a sequence of ops; on first failure returns Left. */
  def applyAll(text: String, ops: List[Op]): Either[String, String] =
    ops.foldLeft(text.asRight[String])((acc, o) => acc.flatMap(applyTo(_, o)))

  /** Operational transform.
    *
    * Given `a` and `b` were both produced from the same baseline, and `b` is applied first, return
    * the sequence of ops equivalent to `a` against the post-`b` state.
    *
    * Most cases return a single op; deleting across a remote insertion splits into two.
    */
  def transform(a: Op, b: Op): List[Op] =
    (a, b) match
      // Insert vs Insert ------------------------------------------------------
      case (Op.Insert(pa, ta), Op.Insert(pb, tb)) =>
        if pa < pb then List(Op.Insert(pa, ta))
        else List(Op.Insert(pa + tb.length, ta)) // ties: later insertion goes after

      // Insert vs Delete ------------------------------------------------------
      case (Op.Insert(pa, ta), Op.Delete(pb, lb)) =>
        if pa <= pb then List(Op.Insert(pa, ta))
        else if pa >= pb + lb then List(Op.Insert(pa - lb, ta))
        else List(Op.Insert(pb, ta)) // insertion inside deleted range collapses to deletion point

      // Delete vs Insert ------------------------------------------------------
      case (Op.Delete(pa, la), Op.Insert(pb, tb)) =>
        if pb <= pa then List(Op.Delete(pa + tb.length, la))
        else if pb >= pa + la then List(Op.Delete(pa, la))
        else
          val leftLen = pb - pa
          val rightLen = la - leftLen
          List(Op.Delete(pa, leftLen), Op.Delete(pa + tb.length, rightLen))

      // Delete vs Delete ------------------------------------------------------
      case (Op.Delete(pa, la), Op.Delete(pb, lb)) =>
        val aEnd = pa + la
        val bEnd = pb + lb
        if aEnd <= pb then List(Op.Delete(pa, la))
        else if bEnd <= pa then List(Op.Delete(pa - lb, la))
        else if pa < pb && aEnd <= bEnd then
          val newLen = pb - pa
          if newLen > 0 then List(Op.Delete(pa, newLen)) else Nil
        else if pa < pb && aEnd > bEnd then List(Op.Delete(pa, la - lb))
        else if pa >= pb && aEnd <= bEnd then Nil
        else // pa >= pb && aEnd > bEnd
          val newLen = aEnd - bEnd
          if newLen > 0 then List(Op.Delete(pb, newLen)) else Nil

  // --- JSON codecs ----------------------------------------------------------

  given Encoder[Op] = Encoder.instance {
    case Op.Insert(p, t) =>
      Json.obj("type" -> "insert".asJson, "pos" -> p.asJson, "text" -> t.asJson)
    case Op.Delete(p, l) =>
      Json.obj("type" -> "delete".asJson, "pos" -> p.asJson, "length" -> l.asJson)
  }

  given Decoder[Op] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "insert" =>
        for
          p <- c.downField("pos").as[Int]
          t <- c.downField("text").as[String]
        yield Op.Insert(p, t)
      case "delete" =>
        for
          p <- c.downField("pos").as[Int]
          l <- c.downField("length").as[Int]
        yield Op.Delete(p, l)
      case other =>
        Left(DecodingFailure(s"unknown op type: $other", c.history))
    }
  }
