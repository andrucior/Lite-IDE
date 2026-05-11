package com.liteide.domain

import munit.FunSuite

/** Convergence tests for the operational transform.
  *
  * For any baseline `s` and any two concurrent ops `a`, `b` produced from `s`, applying
  * `b` then `transform(a, b)` must yield the same text as applying `a` then
  * `transform(b, a)`. This is the property that lets every client converge regardless of
  * who applied what first.
  */
final class OpSpec extends FunSuite:

  private def runLeftThenRight(s: String, a: Op, b: Op): Either[String, String] =
    for
      s1 <- Op.applyTo(s, b)
      s2 <- Op.applyAll(s1, Op.transform(a, b))
    yield s2

  private def runRightThenLeft(s: String, a: Op, b: Op): Either[String, String] =
    for
      s1 <- Op.applyTo(s, a)
      s2 <- Op.applyAll(s1, Op.transform(b, a))
    yield s2

  private def assertConverges(s: String, a: Op, b: Op): Unit =
    val lr = runLeftThenRight(s, a, b)
    val rl = runRightThenLeft(s, a, b)
    assertEquals(lr, rl, s"divergence on s=`$s` a=$a b=$b: $lr vs $rl")

  test("insert before insert"):
    assertConverges("hello", Op.Insert(0, "X"), Op.Insert(5, "Y"))

  test("insert after insert"):
    assertConverges("hello", Op.Insert(5, "Y"), Op.Insert(0, "X"))

  test("insert vs delete — insert before deletion"):
    assertConverges("hello world", Op.Insert(1, "X"), Op.Delete(6, 5))

  test("insert vs delete — insert inside deletion"):
    assertConverges("hello world", Op.Insert(8, "X"), Op.Delete(6, 5))

  test("delete vs insert — insert inside the delete range (split)"):
    // Deleting "hello " while another client inserts at offset 3 ("hel" + "X" + "lo ")
    assertConverges("hello world", Op.Delete(0, 6), Op.Insert(3, "X"))

  test("delete vs delete — disjoint"):
    assertConverges("abcdefghij", Op.Delete(0, 3), Op.Delete(5, 2))

  test("delete vs delete — overlap, a contains b"):
    assertConverges("abcdefghij", Op.Delete(1, 6), Op.Delete(3, 2))

  test("delete vs delete — overlap, b contains a"):
    assertConverges("abcdefghij", Op.Delete(3, 2), Op.Delete(1, 6))

  test("delete vs delete — identical"):
    assertConverges("abcdefghij", Op.Delete(2, 3), Op.Delete(2, 3))

  test("delete vs delete — partial overlap"):
    assertConverges("abcdefghij", Op.Delete(1, 4), Op.Delete(3, 4))
