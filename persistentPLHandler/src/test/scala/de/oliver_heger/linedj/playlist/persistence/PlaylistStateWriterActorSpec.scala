/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.StateTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, SetPlaylist}
import de.oliver_heger.linedj.player.engine.{AudioSource, PlaybackProgressEvent}
import de.oliver_heger.linedj.playlist.persistence.PlaylistFileWriterActor.{FileWritten, WriteFile}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.time.LocalDateTime
import scala.concurrent.duration.*

object PlaylistStateWriterActorSpec extends PlaylistTestHelper:
  /** Name of the file with playlist items. */
  private val PlaylistFileName = "playlist.json"

  /** Name of the file with position information. */
  private val PositionFileName = "position.json"

  /** The auto-save interval used by the test actor. */
  private val AutoSaveInterval = 2.minutes

  /** The configuration object for the test actor. */
  private val WriteConfig = PlaylistWriteConfig(Paths get PlaylistFileName,
    Paths get PositionFileName, AutoSaveInterval)

  /** A test audio source used in events. */
  private val TestAudioSource = AudioSource("testSource", 20171215, 42, 11)

  /** A timestamp used for events. */
  private val TimeStamp = LocalDateTime.of(2017, 12, 15, 21, 35)

  /**
    * Creates a ''WriteFile'' message based on the given index.
    *
    * @param idx the index
    * @return the message
    */
  private def createWriteFile(idx: Int): WriteFile =
    WriteFile(Source.single(ByteString(idx.toString)),
      if idx % 2 == 0 then WriteConfig.pathPlaylist else WriteConfig.pathPosition)

  /**
    * Creates a playback progress event with the relevant parts.
    *
    * @param posOfs  the position offset
    * @param timeOfs the time offset
    * @return the progress event
    */
  private def createProgressEvent(posOfs: Long, timeOfs: Long): PlaybackProgressEvent =
    PlaybackProgressEvent(bytesProcessed = posOfs, playbackTime = timeOfs.seconds,
      currentSource = TestAudioSource, time = TimeStamp)

/**
  * Test class for ''PlaylistStateWriterActor''.
  */
class PlaylistStateWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("PlaylistStateWriterActorSpec"))

  import PlaylistStateWriterActorSpec._

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "A PlaylistStateWriterActor" should "create correct Props" in:
    val props = PlaylistStateWriterActor(WriteConfig)

    classOf[PlaylistStateWriterActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(WriteConfig))

  it should "use a default state update service" in:
    val actorRef = TestActorRef[PlaylistStateWriterActor](PlaylistStateWriterActor(WriteConfig))

    actorRef.underlyingActor.updateService should be(PlaylistWriteStateUpdateServiceImpl)

  it should "store the initial playlist" in:
    val playlist = mock[Playlist]
    val PosOfs = 20180607220325L
    val TimeOfs = 220335
    val state1 = mock[PlaylistWriteState]
    val helper = new WriterActorTestHelper

    helper.stub((), state1) { svc =>
      svc.initPlaylist(PlaylistService, playlist, PosOfs, TimeOfs)
    }
      .stub(WriteStateTransitionMessages(Nil, None), mock[PlaylistWriteState]) { svc =>
        svc.handlePlaybackProgress(PosOfs + 100, TimeOfs + 1, WriteConfig)
      }
      .sendInitPlaylist(SetPlaylist(playlist, positionOffset = PosOfs, timeOffset = TimeOfs))
      .send(createProgressEvent(PosOfs + 100, TimeOfs + 1))
      .expectStateUpdates(PlaylistWriteStateUpdateServiceImpl.InitialState, state1)

  it should "handle an audio player state update event" in:
    val playerState = mock[AudioPlayerState]
    val nextState = mock[PlaylistWriteState]
    val PosOfs = 20180607222036L
    val TimeOfs = 222045
    val write1 = createWriteFile(1)
    val write2 = createWriteFile(2)
    val messages = WriteStateTransitionMessages(List(write1, write2), None)
    val helper = new WriterActorTestHelper

    helper.stub(messages, nextState) { svc =>
      svc.handlePlayerStateChange(PlaylistService, playerState, WriteConfig)
    }
      .stub(WriteStateTransitionMessages(Nil, None), mock[PlaylistWriteState]) { svc =>
        svc.handlePlaybackProgress(PosOfs, TimeOfs, WriteConfig)
      }
      .send(playerState)
      .expectWriteOperation(write1)
      .expectWriteOperation(write2)
      .send(createProgressEvent(PosOfs, TimeOfs))
      .expectStateUpdates(PlaylistWriteStateUpdateServiceImpl.InitialState, nextState)

  it should "handle file written notifications" in:
    val write = createWriteFile(1)
    val otherPath = Paths get "someOtherPath.json"
    val stateTemp = mock[PlaylistWriteState]
    val messages1 = WriteStateTransitionMessages(List(write), None)
    val messages2 = WriteStateTransitionMessages(Nil, Some(testActor))
    val helper = new WriterActorTestHelper

    helper.stub(messages1, stateTemp) { svc => svc.handleFileWritten(write.target, WriteConfig) }
      .stub(messages2, mock[PlaylistWriteState]) { svc =>
        svc.handleFileWritten(otherPath, WriteConfig)
      }
      .send(FileWritten(write.target, None))
      .expectWriteOperation(write)
      .send(FileWritten(otherPath, None))
      .expectNoWriteOperation()
      .expectStateUpdates(PlaylistWriteStateUpdateServiceImpl.InitialState, stateTemp)
      .expectCloseAck()

  it should "handle a close request" in:
    val write = createWriteFile(2)
    val messages = WriteStateTransitionMessages(List(write), Some(testActor))
    val helper = new WriterActorTestHelper

    helper.stub(messages, mock[PlaylistWriteState]) { svc =>
      svc.handleCloseRequest(testActor, WriteConfig)
    }.sendCloseRequest()
      .expectWriteOperation(write)
      .expectCloseAck()

  /**
    * Helper class managing a test instance and its dependencies.
    */
  private class WriterActorTestHelper
    extends StateTestHelper[PlaylistWriteState, PlaylistWriteStateUpdateService]:
    override val updateService: PlaylistWriteStateUpdateService = mock[PlaylistWriteStateUpdateService]

    /** Test probe for the file writer child actor. */
    private val probeFileWriter = TestProbe()

    /** The actor to be tested. */
    private val writerActor = createTestActor()

    /**
      * Sends the specified message directly to the test actor's ''receive''
      * method.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): WriterActorTestHelper =
      writerActor receive msg
      this

    /**
      * Sends a message with the initial playlist to the test actor. This
      * message notifies the actor about the playlist loaded from disk. It is
      * needed to determine whether further updates need to be persisted.
      *
      * @param msg the init message to be sent
      * @return this test helper
      */
    def sendInitPlaylist(msg: SetPlaylist): WriterActorTestHelper =
      send(msg)
      this

    /**
      * Expects that the given state updates were done.
      *
      * @param states the states passed to the update service
      * @return this test helper
      */
    def expectStateUpdates(states: PlaylistWriteState*): WriterActorTestHelper =
      states foreach { s =>
        nextUpdatedState().get should be(s)
      }
      this

    /**
      * Checks that no message was sent to the file writer actor.
      *
      * @return this test helper
      */
    def expectNoWriteOperation(): WriterActorTestHelper =
      val testMsg = new Object
      probeFileWriter.ref ! testMsg
      probeFileWriter.expectMsg(testMsg)
      this

    /**
      * Expects that the specified write message was sent to the file writer
      * actor.
      *
      * @param write the expected write operation
      * @return this test helper
      */
    def expectWriteOperation(write: WriteFile): WriterActorTestHelper =
      expectWriteOperation() should be(write)
      this

    /**
      * Expects that a write operation was triggered and returns the
      * corresponding message.
      *
      * @return the ''WriteFile'' message
      */
    def expectWriteOperation(): PlaylistFileWriterActor.WriteFile =
      probeFileWriter.expectMsgType[PlaylistFileWriterActor.WriteFile]

    /**
      * Sends a close request to the test actor. Note: This request is sent via
      * ''!'' rather than passed directly to the receive function.
      *
      * @return this test helper
      */
    def sendCloseRequest(): WriterActorTestHelper =
      writerActor ! CloseRequest
      this

    /**
      * Checks that an Ack message for a close request is received.
      *
      * @return this test helper
      */
    def expectCloseAck(): WriterActorTestHelper =
      expectMsg(CloseAck(writerActor))
      this

    /**
      * Creates a test actor instance with a child actor factory implementation
      * that returns the probe for the file writer actor.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[PlaylistStateWriterActor] =
      TestActorRef(Props(new PlaylistStateWriterActor(updateService, WriteConfig)
        with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() should be(classOf[PlaylistFileWriterActor])
          p.args shouldBe empty
          probeFileWriter.ref
        }
      }))


