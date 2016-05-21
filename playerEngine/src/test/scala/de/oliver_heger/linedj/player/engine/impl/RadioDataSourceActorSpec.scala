/*
 * Copyright 2015-2016 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.impl

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.FileReaderActor.EndOfFile
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileReaderActor}
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig, RadioSource}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import scala.concurrent.duration._

object RadioDataSourceActorSpec {
  /** Constant for an URI for test radio streams. */
  private val StreamUri = "http://test.radio.stream/test"

  /** The default extension for radio stream URIs. */
  private val DefaultExt = ".mus"

  /** The MP3 default extension for ''RadioSource'' messages. */
  private val Mp3Ext = Some("mp3")

  /** A test configuration used when creating the test actor. */
  private val Config = PlayerConfig(mediaManagerActor = null, actorCreator = (props, name) => null)

  /** A request for audio data. */
  private val DataRequest = PlaybackActor.GetAudioData(512)

  /**
    * Generates the URI of a test radio stream. The URI is made unique by
    * applying an index. Also, it can have an extension or not.
    *
    * @param index         the index
    * @param withExtension a flag whether the URI should have an extension
    * @return the generated URI
    */
  private def streamUri(index: Int, withExtension: Boolean = true): String = {
    val prefix = StreamUri + index
    if (withExtension) prefix + DefaultExt else prefix
  }

  /**
    * Generates an audio source based on the given index.
    *
    * @param index the index
    * @param withExtension a flag whether the URI should have an extension
    * @return the audio source
    */
  private def audioSource(index: Int, withExtension: Boolean = true): AudioSource =
    AudioSource(streamUri(index, withExtension), Long.MaxValue, 0, 0)

  /**
    * Creates an object with audio data.
    *
    * @param data the data of the source
    * @return the source with audio data
    */
  private def audioData(data: String = FileTestHelper.TestData): ArraySourceImpl =
    new ArraySourceImpl(FileTestHelper.toBytes(data), data.length)
}

/**
  * Test class for ''RadioDataSourceActor''.
  */
class RadioDataSourceActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers {

  import RadioDataSourceActorSpec._

  def this() = this(ActorSystem("RadioDataSourceActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Polls data from a queue with a timeout. Fails if no data is received
    * within the timeout.
    *
    * @param queue the queue to be polled
    * @tparam E the element type
    * @return the polled element
    */
  private def pollQueue[E <: AnyRef](queue: LinkedBlockingQueue[E]): E = {
    val elem = queue.poll(3, TimeUnit.SECONDS)
    elem should not be null
    elem
  }

  "A RadioDataSourceActor" should "create a source reader when passed a new data source" in {
    val uri = streamUri(1)
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()

    actor ! RadioSource(uri)
    helper.expectChildCreation().checkProps(uri, actor)
  }

  it should "return EoF when asked for data before a source is created" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()

    actor ! PlaybackActor.GetAudioData(42)
    expectMsg(FileReaderActor.EndOfFile(null))
  }

  /**
    * Checks whether an ''AudioSource'' is correctly created and passed to the
    * playback actor.
    *
    * @param audioStreamUri the URI of the audio stream
    * @param expUri         the expected URI of the data source
    * @param ext            an optional default file extension
    */
  private def checkAudioSource(audioStreamUri: String, expUri: String, ext: Option[String] =
  Mp3Ext): Unit = {
    def createSource(uri: String): AudioSource = AudioSource(uri, 0, 0, 0)

    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! RadioSource(audioStreamUri, ext)
    val creation = helper.expectChildCreation()

    creation.tell(actor, createSource(audioStreamUri))
    actor ! PlaybackActor.GetAudioSource
    expectMsg(createSource(expUri))
  }

  it should "provide an AudioSource for the current stream" in {
    val uri = streamUri(1)
    checkAudioSource(uri, uri)
  }

  it should "append a file extension to the audio source URI if possible" in {
    val uri = streamUri(1, withExtension = false)
    checkAudioSource(uri, uri + "." + Mp3Ext.get)
  }

  it should "handle strange URIs for audio sources without slashes" in {
    val uri = "music"
    checkAudioSource(uri, uri + "." + Mp3Ext.get)
  }

  it should "not modify the audio source if no default extension is provided" in {
    val uri = streamUri(1, withExtension = false)
    checkAudioSource(uri, uri, None)
  }

  it should "park an audio source request until the source becomes available" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! PlaybackActor.GetAudioSource

    val src = audioSource(1)
    actor ! RadioSource(src.uri)
    helper.expectChildCreationAndAudioSource(actor, src)
    expectMsg(src)
  }

  it should "send the updated audio source to a pending request" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! PlaybackActor.GetAudioSource

    val src = audioSource(1, withExtension = false)
    actor ! RadioSource(src.uri, Some(DefaultExt drop 1))
    helper.expectChildCreationAndAudioSource(actor, src)
    expectMsg(audioSource(1))
  }

  it should "reset the newSource flag when serving a pending audio source request" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! PlaybackActor.GetAudioSource
    val src = audioSource(1)
    actor ! RadioSource(src.uri)
    val clientCreation = helper.expectChildCreationAndAudioSource(actor, src)
    expectMsg(src)

    actor ! DataRequest
    clientCreation.probe.expectMsg(DataRequest)
  }

  it should "ignore an audio source not sent from the current child actor" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! PlaybackActor.GetAudioSource

    val src = audioSource(1)
    actor ! RadioSource(src.uri)
    actor ! audioSource(28)
    val creation = helper.expectChildCreation()
    creation.tell(actor, src)
    expectMsg(src)
  }

  it should "ignore an audio source sent before a radio source" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()

    actor receive audioSource(28)
    val src = audioSource(1)
    actor ! RadioSource(src.uri)
    val creation = helper.expectChildCreation()
    creation.tell(actor, src)
    actor ! PlaybackActor.GetAudioSource
    expectMsg(src)
  }

  it should "reject a GetAudioSource request if one is pending" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! PlaybackActor.GetAudioSource

    actor ! PlaybackActor.GetAudioSource
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(PlaybackActor.GetAudioSource)
    errMsg.errorText should be("Request for audio source already pending!")
  }

  it should "handle a request for audio data" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    val childCreation = helper.handleAudioSourceRequest(actor, 1)

    actor ! DataRequest
    childCreation.probe.expectMsg(DataRequest)
  }

  it should "reject a request for audio data if none is pending" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    helper.handleAudioSourceRequest(actor, 1)
    actor ! DataRequest

    actor ! DataRequest
    val errMsg = expectMsgType[PlaybackProtocolViolation]
    errMsg.msg should be(DataRequest)
    errMsg.errorText should be("Request for audio data already pending!")
  }

  it should "pass audio data to the client when it arrives" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    val childCreation = helper.handleAudioSourceRequest(actor, 1)
    actor ! DataRequest

    val data = audioData()
    childCreation.tell(actor, data)
    expectMsg(data)
  }

  it should "ignore audio data not sent from the current source reader actor" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    helper.handleAudioSourceRequest(actor, 1)
    actor ! DataRequest

    actor ! audioData()
    actor ! DataRequest
    expectMsgType[PlaybackProtocolViolation]
  }

  it should "ignore audio data that was not requested" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    val childCreation = helper.handleAudioSourceRequest(actor, 1)

    actor.receive(audioData(), childCreation.probe.ref)
  }

  it should "reset a pending data request when it was served" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    val childCreation = helper.handleAudioSourceRequest(actor, 1)
    actor ! DataRequest
    val data = audioData()
    childCreation.tell(actor, data)
    expectMsg(data)

    childCreation.tell(actor, data)
    val data2 = new ArraySourceImpl("Some data".getBytes, 8)
    actor ! DataRequest
    childCreation.tell(actor, data2)
    expectMsg(data2)
  }

  it should "close the current source when a new one is received" in {
    val uri1 = streamUri(1)
    val uri2 = streamUri(2)
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()

    actor ! RadioSource(uri1)
    val childCreation1 = helper.expectChildCreation()
    childCreation1.checkProps(uri1, actor)
    actor ! RadioSource(uri2)
    val childCreation2 = helper.expectChildCreation()
    childCreation2.checkProps(uri2, actor)
    childCreation1.probe.expectMsg(CloseRequest)

    actor ! PlaybackActor.GetAudioSource
    childCreation1.tell(actor, audioSource(1))
    val src = audioSource(2)
    childCreation2.tell(actor, src)
    expectMsg(src)
  }

  it should "continue playback with the next source" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! PlaybackActor.GetAudioSource
    actor ! RadioSource(streamUri(1))
    helper.expectChildCreationAndAudioSource(actor, audioSource(1))
    expectMsgType[AudioSource]

    val src = audioSource(2)
    actor ! RadioSource(src.uri)
    val childCreation = helper.expectChildCreationAndAudioSource(actor, src)
    actor ! DataRequest
    expectMsg(FileReaderActor.EndOfFile(null))
    actor ! PlaybackActor.GetAudioSource
    expectMsg(src)
    actor ! DataRequest
    childCreation.probe.expectMsg(DataRequest)
    val data = audioData()
    childCreation.tell(actor, data)
    expectMsg(data)
  }

  it should "answer a pending data request with EoF when a new source is added" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    helper.handleAudioSourceRequest(actor, 1)
    actor ! DataRequest

    actor ! RadioSource(streamUri(2))
    expectMsg(EndOfFile(null))
    // check whether pending request was reset
    actor ! DataRequest
    expectMsg(EndOfFile(null))
  }

  it should "stop a child actor when it sends a CloseAck" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    val src1 = audioSource(1)
    actor ! RadioSource(src1.uri)
    val childCreation = helper.expectChildCreationAndAudioSource(actor, src1)
    actor ! RadioSource(streamUri(2))
    childCreation.probe.expectMsg(CloseRequest)

    childCreation.tell(actor, CloseAck(childCreation.probe.ref))
    val probe = TestProbe()
    probe watch childCreation.probe.ref
    probe.expectMsgType[Terminated]
  }

  it should "reset the current source when the reader actor dies" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    val childCreation = helper.handleAudioSourceRequest(actor, 1)

    actor ! DataRequest
    system stop childCreation.probe.ref
    expectMsg(EndOfFile(null))
  }

  it should "not react on Terminated messages from older sources" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! RadioSource(streamUri(1))
    val childCreation = helper.expectChildCreation()
    val childCreation2 = helper.handleAudioSourceRequest(actor, 2)
    childCreation.probe.expectMsg(CloseRequest)

    childCreation.tell(actor, CloseAck(childCreation.probe.ref))
    actor ! DataRequest
    childCreation2.probe.expectMsg(DataRequest)
  }

  it should "send a CloseAck immediately if there is no current source reader" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! CloseRequest

    expectMsg(CloseAck(actor))
  }

  it should "propagate a CloseRequest to the current source reader" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! RadioSource(streamUri(1))
    val childCreation = helper.expectChildCreation()

    actor ! CloseRequest
    childCreation.probe.expectMsg(CloseRequest)
    expectNoMsg(100.milliseconds)
  }

  it should "not send a CloseAck before all Ack from source readers are received" in {
    val messages = new LinkedBlockingQueue[ReceivedMessage]
    val listener = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case m => messages put ReceivedMessage(m, System.nanoTime())
      }
    }))
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    actor ! RadioSource(streamUri(1))
    val childCreation1 = helper.expectChildCreation()
    actor ! RadioSource(streamUri(2))
    val childCreation2 = helper.expectChildCreation()
    actor ! RadioSource(streamUri(3))
    val childCreation3 = helper.expectChildCreationAndAudioSource(actor, audioSource(3))
    messages.clear() // in case of any startup messages

    actor.tell(CloseRequest, listener)
    actor ! CloseAck(childCreation1.probe.ref)
    actor ! CloseAck(childCreation2.probe.ref)
    // check that other messages are ignored
    actor.tell(PlaybackActor.GetAudioSource, listener)
    val lastAckTime = System.nanoTime()
    actor ! CloseAck(childCreation3.probe.ref)
    val msg = pollQueue(messages)
    msg.msg should be(CloseAck(actor))
    msg.at should be > lastAckTime
  }

  it should "handle a ClearBuffer message" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()
    val src = audioSource(1)
    actor ! RadioSource(src.uri)
    val childCreation = helper.expectChildCreationAndAudioSource(actor, src)

    actor ! RadioDataSourceActor.ClearSourceBuffer
    childCreation.probe.expectMsg(StreamBufferActor.ClearBuffer)
  }

  it should "ignore a ClearBuffer message if there is no current source reader" in {
    val helper = new RadioDataSourceActorTestHelper
    val actor = helper.createTestActor()

    actor receive RadioDataSourceActor.ClearSourceBuffer
  }

  it should "create correct Props" in {
    val props = RadioDataSourceActor(Config)

    classOf[RadioDataSourceActor] isAssignableFrom props.actorClass() shouldBe true
    classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
    props.args should have length 1
    props.args.head should be(Config)
  }

  /**
    * A test helper class managing dependencies of the test actor.
    */
  private class RadioDataSourceActorTestHelper {
    /** A queue for querying data about child actors created by the test actor. */
    private val childCreationQueue = new LinkedBlockingQueue[ChildActorCreation]

    /**
      * Creates a test actor instance.
      *
      * @return the test actor ref
      */
    def createTestActor(): TestActorRef[RadioDataSourceActor] = TestActorRef(createProps())

    /**
      * Expects that a child is created by a test actor and returns the
      * corresponding data.
      *
      * @return the data object about the child creation
      */
    def expectChildCreation(): ChildActorCreation =
      pollQueue(childCreationQueue)

    /**
      * Expects the creation of a child actor and simulates an AudioSource
      * message from this child actor.
      *
      * @param actor the test actor
      * @param src   the audio source to be sent by the child
      * @return the data object about the child creation
      */
    def expectChildCreationAndAudioSource(actor: ActorRef, src: AudioSource): ChildActorCreation = {
      val creation = expectChildCreation()
      creation.tell(actor, src)
      creation
    }

    /**
      * Expects a request for an audio source and answers it with the specified
      * source.
      *
      * @param actor  the test actor
      * @param srcIdx the index of the audio source
      * @return the data object about the child creation
      */
    def handleAudioSourceRequest(actor: ActorRef, srcIdx: Int): ChildActorCreation = {
      val src = audioSource(srcIdx)
      actor ! RadioSource(src.uri)
      val creation = expectChildCreationAndAudioSource(actor, src)
      actor ! PlaybackActor.GetAudioSource
      expectMsgType[AudioSource]
      creation
    }

    /**
      * Creates the ''Props'' for the test actor.
      *
      * @return the ''Props'' for the test actor
      */
    private def createProps(): Props =
      Props(new RadioDataSourceActor(Config) with ChildActorFactory {
        /**
          * @inheritdoc This implementation returns a test probe. The probe and
          *             the passed ''Props'' are stored in the child creation
          *             queue.
          */
        override def createChildActor(p: Props): ActorRef = {
          val childProbe = TestProbe()
          childCreationQueue put ChildActorCreation(p, childProbe)
          childProbe.ref
        }
      })
  }

  /**
    * A data class for storing information about a child actor that has been
    * created. For each child to be created a test probe is returned. This
    * probe and the properties are stored in an instance of this class.
    *
    * @param props the creation properties of the child actor
    * @param probe the test probe returned as child actor
    */
  private case class ChildActorCreation(props: Props, probe: TestProbe) {
    /**
      * Checks the properties against the specified stream URI.
      *
      * @param streamUri the expected URI of the stream reference
      * @param testActor the test actor
      */
    def checkProps(streamUri: String, testActor: ActorRef): Unit = {
      classOf[SourceStreamReaderActor].isAssignableFrom(props.actorClass()) shouldBe true
      classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
      props.args should have size 3
      props.args.head should be(Config)
      props.args(1).asInstanceOf[StreamReference].uri should be(streamUri)
      props.args(2) should be(testActor)
    }

    /**
      * Sends the specified message to the given target actor using the
      * associated test probe as sender.
      *
      * @param target the target actor
      * @param msg    the message to be sent
      */
    def tell(target: ActorRef, msg: Any): Unit = {
      target.tell(msg, probe.ref)
    }
  }

}

/**
  * A data class used to record the messages received by an actor.
  *
  * @param msg the message
  * @param at  the time the message was received
  */
private case class ReceivedMessage(msg: Any, at: Long)
