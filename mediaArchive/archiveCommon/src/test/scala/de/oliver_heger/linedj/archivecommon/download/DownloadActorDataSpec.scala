/*
 * Copyright 2015-2021 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.archivecommon.download

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * Test class for ''DownloadActorData''.
 */
class DownloadActorDataSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with Matchers with BeforeAndAfterAll {
  def this() = this(ActorSystem("DownloadActorDataSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
   * Creates an actor reference that can be used in tests.
   * @return the actor reference
   */
  private def actorRef(): ActorRef = {
    val probe = TestProbe()
    probe.ref
  }

  "A DownloadActorData" should "allow adding new download actors" in {
    val client, reader = actorRef()
    val mapping = new DownloadActorData

    mapping.add(reader, client, timestamp = 20150502191354L) should be(mapping)
    mapping.hasActor(reader) shouldBe true
  }

  it should "not claim to have an unknown actor" in {
    val reader = actorRef()
    val mapping = new DownloadActorData

    mapping.hasActor(reader) shouldBe false
  }

  it should "allow removing a non-existing actor" in {
    val ref = actorRef()
    val mapping = new DownloadActorData

    mapping.remove(ref) shouldBe None
  }

  it should "allow removing an existing actor" in {
    val client, reader = actorRef()
    val mapping = new DownloadActorData
    mapping.add(reader, client, 20150502194535L)

    val removedClient = mapping.remove(reader)
    removedClient.get should be(client)
  }

  it should "determine actors with a timeout" in {
    val reader1 = actorRef()
    val reader2 = actorRef()
    val reader3 = actorRef()
    val reader4 = actorRef()
    val mapping = new DownloadActorData
    mapping.add(reader1, testActor, 1000L)
      .add(reader2, testActor, 10000L)
      .add(reader3, testActor, 65000L)
      .add(reader4, testActor, 70000L)

    val timeouts = mapping.findTimeouts(90000, 1.minute)
    timeouts.to(LazyList) should contain only(reader1, reader2)
  }

  it should "also remove timestamps when removing a mapping" in {
    val client, reader = actorRef()
    val mapping = new DownloadActorData
    mapping.add(reader, client, 0)

    mapping remove reader
    mapping.findTimeouts(100000, 10.seconds) shouldBe empty
  }

  it should "not update the timestamp of a non-existing mapping" in {
    val mapping = new DownloadActorData

    mapping.updateTimestamp(actorRef(), 1L) shouldBe false
  }

  it should "allow updating the timestamp of an actor" in {
    val client, reader = actorRef()
    val mapping = new DownloadActorData
    mapping.add(reader, client, 0)

    mapping.updateTimestamp(reader, 95000L) shouldBe true
    mapping.findTimeouts(100000, 10.seconds) shouldBe empty
  }

  it should "find reader actors created for a client" in {
    val reader1, reader2, client = actorRef()
    val mapping = new DownloadActorData

    mapping.add(reader1, client, 20170305172312L)
    mapping.add(reader2, client, 20170305175928L)
    mapping findReadersForClient client should contain only(reader1, reader2)
  }

  it should "return an empty Iterable for an unknown client actor" in {
    val reader, client = actorRef()
    val mapping = new DownloadActorData

    mapping.add(reader, client, 20170305172312L)
    mapping findReadersForClient testActor should have size 0
  }

  it should "also update the client mapping in a remove operation" in {
    val reader, client = actorRef()
    val mapping = new DownloadActorData
    mapping.add(reader, client, 20170305172312L)

    mapping remove reader
    mapping findReadersForClient client should have size 0
  }
}
