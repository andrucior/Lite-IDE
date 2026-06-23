package com.liteide.service

import cats.effect.IO
import munit.CatsEffectSuite

import com.liteide.domain.Role
import com.liteide.domain.Ids.{DocumentId, UserId}

/** Tests for the private-by-default access model: owners and added members can see/access a
  * document, everyone else cannot.
  */
final class DocumentServiceSpec extends CatsEffectSuite:

  test("listFor returns only the caller's own and shared documents"):
    val owner   = UserId.random
    val other   = UserId.random
    for
      svc <- DocumentService.inMemory[IO]
      a   <- svc.create("a", "", owner)
      _   <- svc.create("b", "", other)
      ownerDocs <- svc.listFor(owner)
      otherDocs <- svc.listFor(other)
    yield
      assertEquals(ownerDocs.map(_.id), List(a.id))
      assertEquals(otherDocs.map(_.title), List("b"))

  test("a non-member has no access role; the owner is Owner"):
    val owner   = UserId.random
    val outsider = UserId.random
    for
      svc <- DocumentService.inMemory[IO]
      doc <- svc.create("doc", "", owner)
      asOwner    <- svc.accessRole(doc.id, owner)
      asOutsider <- svc.accessRole(doc.id, outsider)
    yield
      assertEquals(asOwner, Some(Role.Owner))
      assertEquals(asOutsider, None)

  test("accessRole is None for an unknown document"):
    for
      svc  <- DocumentService.inMemory[IO]
      role <- svc.accessRole(DocumentId.random, UserId.random)
    yield assertEquals(role, None)

  test("addMember grants access and makes the doc visible to the member"):
    val owner  = UserId.random
    val friend = UserId.random
    for
      svc <- DocumentService.inMemory[IO]
      doc <- svc.create("shared", "", owner)
      res <- svc.addMember(doc.id, owner, friend, Role.Editor)
      role <- svc.accessRole(doc.id, friend)
      visible <- svc.listFor(friend)
    yield
      assertEquals(res, Right(()))
      assertEquals(role, Some(Role.Editor))
      assertEquals(visible.map(_.id), List(doc.id))

  test("addMember is rejected for outsiders, non-owner members, Owner role, self, and duplicates"):
    val owner    = UserId.random
    val friend   = UserId.random
    val member   = UserId.random
    val outsider = UserId.random
    for
      svc <- DocumentService.inMemory[IO]
      doc <- svc.create("doc", "", owner)
      _          <- svc.addMember(doc.id, owner, member, Role.Editor)
      // An outsider can't even tell the document exists.
      byOutsider <- svc.addMember(doc.id, outsider, friend, Role.Editor)
      // A member who isn't the owner is told they lack the right.
      byMember   <- svc.addMember(doc.id, member, friend, Role.Editor)
      grantOwner <- svc.addMember(doc.id, owner, friend, Role.Owner)
      self       <- svc.addMember(doc.id, owner, owner, Role.Editor)
      _          <- svc.addMember(doc.id, owner, friend, Role.Editor)
      duplicate  <- svc.addMember(doc.id, owner, friend, Role.Observer)
    yield
      assertEquals(byOutsider, Left("no such document"))
      assertEquals(byMember, Left("only the owner can change permissions"))
      assertEquals(grantOwner, Left("cannot grant the Owner role"))
      assertEquals(self, Left("you are already the owner"))
      assertEquals(duplicate, Left("user is already a member"))

  test("removeRole revokes a member's access"):
    val owner  = UserId.random
    val friend = UserId.random
    for
      svc <- DocumentService.inMemory[IO]
      doc <- svc.create("doc", "", owner)
      _   <- svc.addMember(doc.id, owner, friend, Role.Editor)
      _   <- svc.removeRole(doc.id, owner, friend)
      role <- svc.accessRole(doc.id, friend)
      visible <- svc.listFor(friend)
    yield
      assertEquals(role, None)
      assertEquals(visible, Nil)

  test("setRole changes an existing member's role but rejects non-owners"):
    val owner  = UserId.random
    val friend = UserId.random
    val intruder = UserId.random
    for
      svc <- DocumentService.inMemory[IO]
      doc <- svc.create("doc", "", owner)
      _   <- svc.addMember(doc.id, owner, friend, Role.Editor)
      changed <- svc.setRole(doc.id, owner, friend, Role.Observer)
      role    <- svc.accessRole(doc.id, friend)
      denied  <- svc.setRole(doc.id, intruder, friend, Role.Editor)
    yield
      assertEquals(changed, Right(()))
      assertEquals(role, Some(Role.Observer))
      assertEquals(denied, Left("no such document"))
