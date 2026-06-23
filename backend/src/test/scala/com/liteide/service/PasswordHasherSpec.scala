package com.liteide.service

import munit.FunSuite

final class PasswordHasherSpec extends FunSuite:

  test("verify accepts the password it hashed"):
    val stored = PasswordHasher.hash("correct horse battery staple")
    assert(PasswordHasher.verify("correct horse battery staple", stored))

  test("verify rejects a wrong password"):
    val stored = PasswordHasher.hash("s3cret")
    assert(!PasswordHasher.verify("S3cret", stored))
    assert(!PasswordHasher.verify("", stored))

  test("hashing the same password twice yields different salted digests"):
    val a = PasswordHasher.hash("repeat")
    val b = PasswordHasher.hash("repeat")
    assertNotEquals(a, b)
    assert(PasswordHasher.verify("repeat", a))
    assert(PasswordHasher.verify("repeat", b))

  test("verify rejects a malformed stored value instead of throwing"):
    assert(!PasswordHasher.verify("anything", "not-a-valid-hash"))
    assert(!PasswordHasher.verify("anything", ""))
