package com.liteide.service

import cats.effect.IO
import munit.CatsEffectSuite

/** Behavioural tests for `AuthService.inMemory`: registration uniqueness, credential checks, and
  * email normalisation.
  */
final class AuthServiceSpec extends CatsEffectSuite:

  test("register then login with the right password returns the account"):
    for
      svc     <- AuthService.inMemory[IO]
      created <- svc.register("alice@example.com", "Alice", "pw-12345")
      account  = created.toOption.get
      found   <- svc.login("alice@example.com", "pw-12345")
    yield
      assertEquals(found.map(_.id), Some(account.id))
      assertEquals(account.email, "alice@example.com")

  test("login with a wrong password returns None"):
    for
      svc   <- AuthService.inMemory[IO]
      _     <- svc.register("bob@example.com", "Bob", "right")
      found <- svc.login("bob@example.com", "wrong")
    yield assertEquals(found, None)

  test("login for an unknown email returns None"):
    for
      svc   <- AuthService.inMemory[IO]
      found <- svc.login("nobody@example.com", "whatever")
    yield assertEquals(found, None)

  test("registering a duplicate email is rejected"):
    for
      svc    <- AuthService.inMemory[IO]
      first  <- svc.register("dup@example.com", "First", "pw")
      second <- svc.register("DUP@example.com", "Second", "pw")
    yield
      assert(first.isRight)
      assertEquals(second, Left("email already registered"))

  test("email is normalised, so login is case-insensitive"):
    for
      svc   <- AuthService.inMemory[IO]
      _     <- svc.register("  Carol@Example.com ", "Carol", "pw")
      found <- svc.login("carol@example.com", "pw")
    yield assertEquals(found.map(_.email), Some("carol@example.com"))

  test("findById resolves a registered account and ignores unknown ids"):
    for
      svc     <- AuthService.inMemory[IO]
      created <- svc.register("dora@example.com", "Dora", "pw")
      id       = created.toOption.get.id
      byId    <- svc.findById(id)
    yield assertEquals(byId.map(_.email), Some("dora@example.com"))

  test("empty email or password is rejected"):
    for
      svc      <- AuthService.inMemory[IO]
      noEmail  <- svc.register("   ", "Nobody", "pw")
      noPass   <- svc.register("eve@example.com", "Eve", "")
    yield
      assert(noEmail.isLeft)
      assert(noPass.isLeft)
