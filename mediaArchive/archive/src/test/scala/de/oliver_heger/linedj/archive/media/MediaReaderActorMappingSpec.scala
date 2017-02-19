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
    system.shutdown()
    system awaitTermination 10.seconds
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
   * @param time the current time for the add operation
   * @return the mapping object
   */
  private def addMapping(mapping: MediaReaderActorMapping, client: ActorRef, reader: ActorRef,
                         time: Long = 0): MediaReaderActorMapping = {
    mapping.add(client -> Option(reader), time)
  }

  "A MediaReaderActorMapping" should "allow adding new mappings" in {
    val client, reader = actorRef()
    val mapping = new MediaReaderActorMapping

    addMapping(mapping, client, reader, 20150502191354L) should be(mapping)
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

    mapping.remove(ref) shouldBe 'empty
  }

  it should "allow removing an existing mapping" in {
    val client, reader = actorRef()
    val mapping = new MediaReaderActorMapping
    addMapping(mapping, client, reader, 20150502194535L)

    mapping.remove(client).get should be(reader)
  }

  it should "determine mappings with a timeout" in {
    val client1, reader1 = actorRef()
    val client2, reader2 = actorRef()
    val client3, reader3 = actorRef()
    val client4, reader4 = actorRef()
    val mapping = new MediaReaderActorMapping
    mapping.add(client1 -> Some(reader1), 1000L)
      .add(client2 -> Some(reader2), 10000L)
      .add(client3 -> Some(reader3), 65000L)
      .add(client4 -> Some(reader4), 70000L)

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

    mapping.add(client -> None, 20150916215901L)
    mapping hasActor client shouldBe true
    mapping remove client shouldBe 'empty
  }
}
