/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.player.server

import akka.actor as classic
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.ws.Message
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import de.oliver_heger.linedj.player.engine.actors.EventManagerActor
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, MetadataNotSupported, RadioEvent, RadioMetadataEvent, RadioPlaybackContextCreationFailedEvent, RadioPlaybackErrorEvent, RadioPlaybackProgressEvent, RadioPlaybackStoppedEvent, RadioSource, RadioSourceChangedEvent, RadioSourceReplacementEndEvent, RadioSourceReplacementStartEvent}
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import spray.json.*

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

object MessageActorSpec:
  /** A test radio source. */
  private val TestSource1 = RadioSource("https://radio.example.org/test")

  /** The ID of the first test radio source. */
  private val TestSourceID1 = "theFirstSource"

  /** Another test radio source. */
  private val TestSource2 = RadioSource("https://radio.example.org/anotherTest")

  /** The ID of the alternative test radio source. */
  private val TestSourceID2 = "theOtherSource"

  /** The ID mapping for test sources. */
  private val TestSourceMapping = Map(TestSource1 -> TestSourceID1, TestSource2 -> TestSourceID2)
end MessageActorSpec

/**
  * Test class for [[MessageActor]].
  */
class MessageActorSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers with MockitoSugar:

  import MessageActorSpec.*

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  "MessageActor" should "handle a radio source changed event" in {
    val event = RadioSourceChangedEvent(TestSource1)
    val message = RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, TestSourceID1)
    val helper = new MessageActorTestHelper

    helper.sendEvent(event)
      .expectMessage(message)
  }

  it should "ignore events for unknown radio sources" in {
    val unknownSource = RadioSource("https://unknown.example.org/qmark")
    val helper = new MessageActorTestHelper

    helper.sendEvent(RadioSourceChangedEvent(unknownSource))
      .expectNoMessage()
  }

  it should "handle a replacement source start event" in {
    val event = RadioSourceReplacementStartEvent(TestSource1, TestSource2)
    val message = RadioModel.RadioMessage(RadioModel.MessageTypeReplacementStart, TestSourceID2)
    val helper = new MessageActorTestHelper

    helper.sendEvent(event)
      .expectMessage(message)
  }

  it should "handle a replacement source end event" in {
    val event = RadioSourceReplacementEndEvent(TestSource1)
    val message = RadioModel.RadioMessage(RadioModel.MessageTypeReplacementEnd, TestSourceID1)
    val helper = new MessageActorTestHelper

    helper.sendEvent(event)
      .expectMessage(message)
  }

  it should "handle a playback stopped event" in {
    val event = RadioPlaybackStoppedEvent(TestSource1)
    val message = RadioModel.RadioMessage(RadioModel.MessageTypePlaybackStopped, TestSourceID1)
    val helper = new MessageActorTestHelper

    helper.sendEvent(event)
      .expectMessage(message)
  }

  it should "handle a metadata event with current metadata" in {
    val title = "Artist - Title"
    val metadata = CurrentMetadata(s"StreamTitle='$title';foo=bar")
    val event = RadioMetadataEvent(TestSource1, metadata)
    val message = RadioModel.RadioMessage(RadioModel.MessageTypeTitleInfo, title)
    val helper = new MessageActorTestHelper

    helper.sendEvent(event)
      .expectMessage(message)
  }

  it should "handle a metadata event with unsupported metadata" in {
    val event = RadioMetadataEvent(TestSource1, MetadataNotSupported)
    val message = RadioModel.RadioMessage(RadioModel.MessageTypeTitleInfo, "")
    val helper = new MessageActorTestHelper

    helper.sendEvent(event)
      .expectMessage(message)
  }

  it should "ignore other events" in {
    val helper = new MessageActorTestHelper

    helper.sendEvent(RadioPlaybackErrorEvent(TestSource1))
      .sendEvent(RadioPlaybackContextCreationFailedEvent(TestSource1))
      .sendEvent(RadioPlaybackProgressEvent(TestSource1, 1000L, 20.seconds))
      .expectNoMessage()
  }

  /**
    * A test helper class managing the dependencies of a test instance.
    */
  private class MessageActorTestHelper extends RadioModel.RadioJsonSupport:
    /** The actor for sending radio events. */
    private val eventActor = testKit.spawn(EventManagerActor[RadioEvent]())

    /** The queue where web socket messages are stored. */
    private val messageQueue = new LinkedBlockingQueue[Message]

    createMessageActor()

    /**
      * Simulates an event that is published via the event actor.
      *
      * @param event the event to be published
      * @return this test helper
      */
    def sendEvent(event: RadioEvent): MessageActorTestHelper =
      eventActor ! EventManagerActor.Publish(event)
      this

    /**
      * Returns the next message that has been propagated by the message actor.
      * If no message was received in a timeout, the test fails.
      *
      * @return the next message that was received
      */
    def nextMessage: RadioModel.RadioMessage =
      val message = messageQueue.poll(3, TimeUnit.SECONDS)
      message should not be null
      val content = message.asTextMessage.getStrictText
      val jsonAst = content.parseJson
      jsonAst.convertTo[RadioModel.RadioMessage]

    /**
      * Obtains the next message that has been propagated by the message actor
      * and compares it with the given expected message.
      *
      * @param expMessage the expected message
      * @return this test helper
      */
    def expectMessage(expMessage: RadioModel.RadioMessage): MessageActorTestHelper =
      nextMessage should be(expMessage)
      this

    /**
      * Expects that for a given event no message is generated. Instead of
      * waiting for a timeout, this function generates a specific event and
      * tests whether the next message is the one for this event.
      *
      * @return this test helper
      */
    def expectNoMessage(): MessageActorTestHelper =
      val event = RadioSourceChangedEvent(TestSource2)
      sendEvent(event)
      expectMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, TestSourceID2))

    /**
      * Creates a mock for the radio player. The mock is prepared to handle
      * event listener registrations. New listeners are passed to the event
      * actor. In addition, a radio player configuration with an actor creator
      * is provided.
      *
      * @return the mock radio player
      */
    private def createRadioPlayerMock(): RadioPlayer =
      val creator = ServerConfigTestHelper.actorCreator(testKit.system.classicSystem, Some(testKit))
      val config = ServerConfigTestHelper.defaultServerConfig(creator)

      val player = mock[RadioPlayer]
      when(player.config).thenReturn(config.radioPlayerConfig)
      when(player.addEventListener(any())).thenAnswer((invocation: InvocationOnMock) =>
        val listener = invocation.getArgument(0, classOf[ActorRef[RadioEvent]])
        eventActor ! EventManagerActor.RegisterListener(listener)
      )

      player

    /**
      * Creates a test actor instance and connects the message flow, so that
      * messages sent by the actor can be retrieved from the message queue.
      */
    private def createMessageActor(): Unit =
      implicit val ec: ExecutionContext = testKit.system.executionContext
      implicit val classicSystem: classic.ActorSystem = testKit.system.classicSystem
      val player = createRadioPlayerMock()
      val source = Source.empty[Message]
      val sink = Sink.foreach[Message](messageQueue.offer)
      MessageActor.newMessageFlow(player, TestSourceMapping) foreach { flow =>
        source.via(flow).to(sink).run()
      }
      verify(player, timeout(3000)).addEventListener(any())
