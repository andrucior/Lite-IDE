package com.liteide.domain

import munit.FunSuite

/** Tests for the per-op application semantics — separate from convergence so that range
  * checks and boundary conditions stay readable.
  */
final class OpApplySpec extends FunSuite:

  test("insert at start"):
    assertEquals(Op.applyTo("world", Op.Insert(0, "hello ")), Right("hello world"))

  test("insert at end is allowed"):
    assertEquals(Op.applyTo("hi", Op.Insert(2, "!")), Right("hi!"))

  test("insert past end is rejected"):
    assert(Op.applyTo("hi", Op.Insert(3, "!")).isLeft)

  test("insert with negative position is rejected"):
    assert(Op.applyTo("hi", Op.Insert(-1, "x")).isLeft)

  test("delete in the middle"):
    assertEquals(Op.applyTo("abcdef", Op.Delete(2, 2)), Right("abef"))

  test("delete past end is rejected"):
    assert(Op.applyTo("abc", Op.Delete(2, 5)).isLeft)

  test("delete with negative length is rejected"):
    assert(Op.applyTo("abc", Op.Delete(0, -1)).isLeft)

  test("applyAll stops at first failure and reports it"):
    val result = Op.applyAll("abc", List(Op.Insert(0, "X"), Op.Delete(10, 1)))
    assert(result.isLeft)

  test("isNoop detects empty insert"):
    assert(Op.isNoop(Op.Insert(0, "")))

  test("isNoop detects zero-length delete"):
    assert(Op.isNoop(Op.Delete(3, 0)))

  test("isNoop is false for a real change"):
    assert(!Op.isNoop(Op.Insert(0, "x")))
    assert(!Op.isNoop(Op.Delete(0, 1)))
