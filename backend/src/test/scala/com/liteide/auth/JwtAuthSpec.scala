package com.liteide.auth

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.duration.*

import munit.FunSuite

import com.liteide.domain.Ids.UserId

final class JwtAuthSpec extends FunSuite:

  private val secret = "test-secret"
  private val epoch  = Instant.parse("2026-01-01T00:00:00Z")

  private def at(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

  test("a freshly issued token verifies back to the same user id"):
    val jwt   = JwtAuth(secret, 1.hour, at(epoch))
    val user  = UserId.random
    val token = jwt.issue(user)
    assertEquals(jwt.verify(token), Some(user))

  test("a token signed with a different secret is rejected"):
    val issuer   = JwtAuth(secret, 1.hour, at(epoch))
    val attacker = JwtAuth("other-secret", 1.hour, at(epoch))
    assertEquals(attacker.verify(issuer.issue(UserId.random)), None)

  test("a tampered token is rejected"):
    val jwt   = JwtAuth(secret, 1.hour, at(epoch))
    val token = jwt.issue(UserId.random)
    assertEquals(jwt.verify(token + "x"), None)

  test("garbage is rejected without throwing"):
    val jwt = JwtAuth(secret, 1.hour, at(epoch))
    assertEquals(jwt.verify("not.a.jwt"), None)

  test("an expired token is rejected"):
    val token    = JwtAuth(secret, 1.hour, at(epoch)).issue(UserId.random)
    val laterJwt = JwtAuth(secret, 1.hour, at(epoch.plusSeconds(2.hours.toSeconds)))
    assertEquals(laterJwt.verify(token), None)
