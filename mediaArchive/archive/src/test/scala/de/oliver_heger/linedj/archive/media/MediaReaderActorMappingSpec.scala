/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
 * Test class for ''MediaReaderActorMapping''.
 */
class MediaReaderActorMappingSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
FlatSpecLike with Matchers with BeforeAndAfterAll {
  def this() = this(ActorSystem("MediaReaderActorMappingSpec"))

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

  /**
   * Convenience method to add an actor mapping to a test object.
   * @param mapping the test mapping object
   * @param client the actor to be passed to the client
   * @param reader the optional underlying reader actor
   * @param caller the caller actor to be stored in the mapping
   * @param time the current time for the add operation
   * @return the mapping object
   */
  private def addMapping(mapping: MediaReaderActorMapping, client: ActorRef, reader: ActorRef,
                         caller: ActorRef = testActor, time: Long = 0):
  MediaReaderActorMapping = {
    mapping.add(client -> Option(reader), caller, time)
  }

  "A MediaReaderActorMapping" should "allow adding new mappings" in {
    val client, reader = actorRef()
    val mapping = new MediaReaderActorMapping

    addMapping(mapping, client, reader, time = 20150502191354L) should be(mapping)
    mapping.hasActor(client) shouldBe true
  }

  it should "not claim to have an unknown mapping" in {
    val client = actorRef()
    val mapping = new MediaReaderActorMapping

    mapping.hasActor(client) shouldBe false
  }

  it should "allow removing a non-existing mapping" in {
    val ref = actorRef()
    val mapping = new MediaReaderActorMapping

    mapping.remove(ref) shouldBe (None, None)
  }

  it should "allow removing an existing mapping" in {
    val client, reader = actorRef()
    val mapping = new MediaReaderActorMapping
    addMapping(mapping, client, reader, time = 20150502194535L)

    val (removedReader, removedClient) = mapping.remove(client)
    removedReader.get should be(reader)
    removedClient.get should be(testActor)
  }

  it should "determine mappings with a timeout" in {
    val client1, reader1 = actorRef()
    val client2, reader2 = actorRef()
    val client3, reader3 = actorRef()
    val client4, reader4 = actorRef()
    val mapping = new MediaReaderActorMapping
    mapping.add(client1 -> Some(reader1), testActor, 1000L)
      .add(client2 -> Some(reader2), testActor, 10000L)
      .add(client3 -> Some(reader3), testActor, 65000L)
      .add(client4 -> Some(reader4), testActor, 70000L)

    val timeouts = mapping.findTimeouts(90000, 1.minute)
    timeouts.toStream should contain only(client1, client2)
  }

  it should "also remove timestamps when removing a mapping" in {
    val client, reader = actorRef()
    val mapping = new MediaReaderActorMapping
    addMapping(mapping, client, reader)

    mapping remove client
    mapping.findTimeouts(100000, 10.seconds) shouldBe 'empty
  }

  it should "not update the timestamp of a non-existing mapping" in {
    val mapping = new MediaReaderActorMapping

    mapping.updateTimestamp(actorRef(), 1L) shouldBe false
  }

  it should "allow updating the timestamp of an actor" in {
    val client, reader = actorRef()
    val mapping = new MediaReaderActorMapping
    addMapping(mapping, client, reader)

    mapping.updateTimestamp(client, 95000L) shouldBe true
    mapping.findTimeouts(100000, 10.seconds) shouldBe 'empty
  }

  it should "support mappings with an undefined underlying actor" in {
    val client = actorRef()
    val mapping = new MediaReaderActorMapping

    mapping.add(client -> None, testActor, 20150916215901L)
    mapping hasActor client shouldBe true
    mapping remove client shouldBe (None, Some(testActor))
  }

  it should "find reader actors created for a client" in {
    val reader1, reader2, client = actorRef()
    val mapping = new MediaReaderActorMapping

    mapping.add(reader1 -> None, client, 20170305172312L)
    mapping.add(reader2 -> None, client, 20170305175928L)
    mapping findReadersForClient client should contain only(reader1, reader2)
  }

  it should "return an empty Iterable for an unknown client actor" in {
    val reader, client = actorRef()
    val mapping = new MediaReaderActorMapping

    mapping.add(reader -> None, client, 20170305172312L)
    mapping findReadersForClient testActor should have size 0
  }

  it should "also update the client mapping in a remove operation" in {
    val reader, client = actorRef()
    val mapping = new MediaReaderActorMapping
    mapping.add(reader -> None, client, 20170305172312L)

    mapping remove reader
    mapping findReadersForClient client should have size 0
  }
}
