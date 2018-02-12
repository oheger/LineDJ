/*
 * Copyright 2015-2018 The Developers Team.
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

import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, SetPlaylist}
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.player.engine.{AudioSource, PlaybackProgressEvent}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

object PlaylistStateWriterActorSpec extends PlaylistTestHelper {
  /** Name of the file with playlist items. */
  private val PlaylistFileName = "playlist.json"

  /** Name of the file with position information. */
  private val PositionFileName = "position.json"

  /** The auto-save interval used by the test actor. */
  private val AutoSaveInterval = 2.minutes

  /** A test audio source used in events. */
  private val TestAudioSource = AudioSource("testSource", 20171215, 42, 11)

  /** A timestamp used for events. */
  private val TimeStamp = LocalDateTime.of(2017, 12, 15, 21, 35)

  /**
    * Generates a player state object based on the specified parameters.
    *
    * @param songCount    the number of songs in the playlist
    * @param currentIndex the index of the current song
    * @param seqNo        the sequence number of the playlist
    * @param activated    flag whether the playlist is activated
    * @return the player state object
    */
  def createPlayerState(songCount: Int, currentIndex: Int = 0, seqNo: Int = 1,
                        activated: Boolean = true): AudioPlayerState = {
    val playlist = generatePlaylist(songCount, currentIndex)
    AudioPlayerState(playlist = playlist, playbackActive = true, playlistClosed = false,
      playlistSeqNo = seqNo, playlistActivated = activated)
  }

  /**
    * Creates a playback progress event with the relevant parts.
    *
    * @param posOfs  the position offset
    * @param timeOfs the time offset
    * @return the progress event
    */
  def createProgressEvent(posOfs: Long, timeOfs: Long): PlaybackProgressEvent =
    PlaybackProgressEvent(bytesProcessed = posOfs, playbackTime = timeOfs,
      currentSource = TestAudioSource, time = TimeStamp)
}

/**
  * Test class for ''PlaylistStateWriterActor''.
  */
class PlaylistStateWriterActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("PlaylistStateWriterActorSpec"))

  import PlaylistStateWriterActorSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  "A PlaylistStateWriterActor" should "create correct Props" in {
    val pathPlaylist = Paths get "playlist.json"
    val pathPosition = Paths get "position.json"
    val autoSave = 3.minutes

    val props = PlaylistStateWriterActor(pathPlaylist, pathPosition, autoSave)
    classOf[PlaylistStateWriterActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(pathPlaylist, pathPosition, autoSave))
  }

  it should "store the playlist if there is a state change" in {
    val state = createPlayerState(8)
    val helper = new WriterActorTestHelper

    helper.sendInitState()
      .send(state)
      .expectAndHandleWriteOperation()
      .expectPersistedPlaylist(state.playlist)
      .expectNoWriteOperation()
  }

  it should "not store a playlist that has not yet been activated" in {
    val state = createPlayerState(4, activated = false)
    val helper = new WriterActorTestHelper

    helper.sendInitState()
      .send(state)
      .expectNoWriteOperation()
  }

  it should "store position data if the playlist index is changed" in {
    val state = createPlayerState(4, currentIndex = 1)
    val helper = new WriterActorTestHelper
    helper.sendInitState().send(createPlayerState(4))
      .expectAndHandleWriteOperation()

    helper.send(state)
      .expectAndHandleWriteOperation()
      .expectPersistedPlaylist(state.playlist)
      .expectNoWriteOperation()
  }

  it should "write the playlist position on receiving a relevant progress event" in {
    val SongCount = 8
    val Index = 2
    val PosOffset = 5000
    val helper = new WriterActorTestHelper

    helper.sendInitState()
      .send(createPlayerState(8, currentIndex = 2))
      .expectAndHandleWriteOperation().expectAndHandleWriteOperation()
      .send(createProgressEvent(PosOffset, AutoSaveInterval.toSeconds))
      .expectAndHandleWriteOperation()
      .expectPersistedPlaylist(generateSetPlaylist(SongCount, Index, PosOffset,
        AutoSaveInterval.toSeconds))
  }

  it should "only write the playlist on receiving a progress event if there is a change" in {
    val helper = new WriterActorTestHelper

    helper.send(createPlayerState(4, currentIndex = 2))
      .send(createProgressEvent(11111, AutoSaveInterval.toSeconds - 1))
      .expectNoWriteOperation()
  }

  it should "ignore progress events before the initial state was set" in {
    val helper = new WriterActorTestHelper

    helper.send(createProgressEvent(1234, AutoSaveInterval.toSeconds))
      .expectNoWriteOperation()
  }

  it should "serialize file write operations" in {
    val SongCount = 8
    val Index = 3
    val Offset = 65536
    val helper = new WriterActorTestHelper

    helper.sendInitState()
      .send(createPlayerState(SongCount / 2, currentIndex = 1))
      .skipWriteOperation().skipWriteOperation()
      .send(createPlayerState(SongCount, currentIndex = Index, seqNo = 2))
      .expectNoWriteOperation()
      .sendWriteConfirmationForPlaylist()
      .expectAndHandleWriteOperation()
      .send(createProgressEvent(Offset, 2 * AutoSaveInterval.toSeconds))
      .sendWriteConfirmationForPosition()
      .expectAndHandleWriteOperation()
      .expectPersistedPlaylist(generateSetPlaylist(SongCount, Index, Offset,
        2 * AutoSaveInterval.toSeconds))
  }

  it should "answer a close request if no actions are pending" in {
    val helper = new WriterActorTestHelper

    helper.sendInitState()
      .send(createProgressEvent(1234, AutoSaveInterval.toSeconds))
      .skipWriteOperation().sendWriteConfirmationForPosition()
      .sendCloseRequest()
      .expectCloseAck()
  }

  it should "not send a close ack before write operations have completed" in {
    val helper = new WriterActorTestHelper

    helper.sendInitState()
      .send(createPlayerState(4, currentIndex = 1))
      .sendCloseRequest()
      .expectNoCloseAck()
      .skipWriteOperation().skipWriteOperation()
      .sendWriteConfirmationForPlaylist().sendWriteConfirmationForPosition()
      .expectCloseAck()
  }

  it should "store an updated position when receiving a close request" in {
    val SongCount = 8
    val Index = 5
    val TimeOffset = AutoSaveInterval.toSeconds / 2
    val PosOffset = 5000
    val helper = new WriterActorTestHelper

    helper.sendInitState()
      .send(createPlayerState(SongCount, currentIndex = Index))
      .expectAndHandleWriteOperation().expectAndHandleWriteOperation()
      .send(createProgressEvent(PosOffset, TimeOffset))
      .sendCloseRequest()
      .expectNoCloseAck()
      .expectAndHandleWriteOperation()
      .expectCloseAck()
      .expectPersistedPlaylist(generateSetPlaylist(SongCount, Index, PosOffset, TimeOffset))
  }

  it should "not accept updates after a close request has been received" in {
    val state = createPlayerState(4, currentIndex = 2)
    val helper = new WriterActorTestHelper

    helper.sendInitState()
      .send(state)
      .sendCloseRequest()
      .expectAndHandleWriteOperation()
      .send(createPlayerState(5))
      .send(createProgressEvent(1, AutoSaveInterval.toSeconds))
      .expectAndHandleWriteOperation()
      .expectCloseAck()
      .expectNoWriteOperation()
  }

  /**
    * Helper class managing a test instance and its dependencies.
    */
  private class WriterActorTestHelper {
    /** Test probe for the file writer child actor. */
    private val probeFileWriter = TestProbe()

    /** The path to the file with playlist information. */
    private val pathPlaylist = initPlaylistFile(PlaylistFileName)

    /** The path to the file with position information. */
    private val pathPosition = initPlaylistFile(PositionFileName)

    /** The actor for loading playlist information. */
    private lazy val loaderActor = createLoaderActor()

    /** A message bus for interaction with the loader actor. */
    private lazy val messageBus = new MessageBusTestImpl

    /** The actor to be tested. */
    private val writerActor = createTestActor()

    /**
      * Sends the specified message directly to the test actor's ''receive''
      * method.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): WriterActorTestHelper = {
      writerActor receive msg
      this
    }

    /**
      * Sends a message with the initial playlist state to the test actor. The
      * first state message should cause the actor to initialize itself; this
      * state should not be written to disk.
      *
      * @return this test helper
      */
    def sendInitState(): WriterActorTestHelper = {
      send(createPlayerState(0, seqNo = 0))
      this
    }

    /**
      * Checks that no message was sent to the file writer actor.
      *
      * @return this test helper
      */
    def expectNoWriteOperation(): WriterActorTestHelper = {
      val testMsg = new Object
      probeFileWriter.ref ! testMsg
      probeFileWriter.expectMsg(testMsg)
      this
    }

    /**
      * Expects that a write operation was triggered and returns the
      * corresponding message.
      *
      * @return the ''WriteFile'' message
      */
    def expectWriteOperation(): PlaylistFileWriterActor.WriteFile =
      probeFileWriter.expectMsgType[PlaylistFileWriterActor.WriteFile]

    /**
      * Expects that a write operation has been triggered, but ignores the
      * concrete parameters. Just returns this test helper, allowing fluent
      * syntax in test cases.
      *
      * @return this test helper
      */
    def skipWriteOperation(): WriterActorTestHelper = {
      expectWriteOperation()
      this
    }

    /**
      * Expects that a write operation was triggered and writes the data to
      * disk, so that it can be loaded later on.
      *
      * @return this test helper
      */
    def expectAndHandleWriteOperation(): WriterActorTestHelper = {
      val writeMsg = expectWriteOperation()
      List(pathPosition, pathPlaylist) should contain(writeMsg.target)
      implicit val mat: ActorMaterializer = ActorMaterializer()
      val sink = FileIO.toPath(writeMsg.target)
      val futResult = writeMsg.source.runWith(sink)
      Await.result(futResult, 3.seconds)
      sendWriteConfirmation(writeMsg.target)
    }

    /**
      * Loads the playlist persisted by the test actor using a loader actor.
      *
      * @return the ''SetPlaylist'' command read by the loader actor
      */
    def loadPlaylist(): SetPlaylist = {
      loaderActor ! LoadPlaylistActor.LoadPlaylistData(pathPlaylist, pathPosition,
        Integer.MAX_VALUE, messageBus)
      messageBus.expectMessageType[LoadedPlaylist].setPlaylist
    }

    /**
      * Loads the playlist persisted by the test actor and compares it
      * against the specified object. Here it is expected that no position
      * information has been written.
      *
      * @param playlist the ''Playlist''
      * @return this test helper
      */
    def expectPersistedPlaylist(playlist: Playlist): WriterActorTestHelper =
      expectPersistedPlaylist(SetPlaylist(playlist))

    /**
      * Loads the playlist persisted by the test actor and compares it against
      * the expected set playlist command.
      *
      * @param cmd the expected playlist command
      * @return this test helper
      */
    def expectPersistedPlaylist(cmd: SetPlaylist): WriterActorTestHelper = {
      loadPlaylist() should be(cmd)
      this
    }

    /**
      * Sends a confirmation to the test actor that the playlist file has been
      * written.
      *
      * @return this test helper
      */
    def sendWriteConfirmationForPlaylist(): WriterActorTestHelper =
      sendWriteConfirmation(pathPlaylist)

    /**
      * Sends a confirmation to the test actor that the position file has been
      * written.
      *
      * @return this test helper
      */
    def sendWriteConfirmationForPosition(): WriterActorTestHelper =
      sendWriteConfirmation(pathPosition)

    /**
      * Sends a close request to the test actor. Note: This request is sent via
      * ''!'' rather than passed directly to the recieve function.
      *
      * @return this test helper
      */
    def sendCloseRequest(): WriterActorTestHelper = {
      writerActor ! CloseRequest
      this
    }

    /**
      * Checks that an Ack message for a close request is received.
      *
      * @return this test helper
      */
    def expectCloseAck(): WriterActorTestHelper = {
      expectMsg(CloseAck(writerActor))
      this
    }

    /**
      * Checks that no close Ack message has been received.
      *
      * @return this test helper
      */
    def expectNoCloseAck(): WriterActorTestHelper = {
      expectNoMessage(500.millis)
      this
    }

    /**
      * Sends a confirmation message that the specified file has been
      * written.
      *
      * @param path the path to the file that was written
      * @return this test helper
      */
    private def sendWriteConfirmation(path: Path): WriterActorTestHelper = {
      send(PlaylistFileWriterActor.FileWritten(path, None))
    }

    /**
      * Creates an actor for loading playlist information.
      *
      * @return the loader actor
      */
    private def createLoaderActor(): ActorRef =
      system.actorOf(Props[LoadPlaylistActor])

    /**
      * Creates a test actor instance with a child actor factory implementation
      * that returns the probe for the file writer actor.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[PlaylistStateWriterActor] =
      TestActorRef(Props(new PlaylistStateWriterActor(pathPlaylist, pathPosition,
        AutoSaveInterval) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() should be(classOf[PlaylistFileWriterActor])
          p.args shouldBe 'empty
          probeFileWriter.ref
        }
      }))

    /**
      * Initializes a temporary file to be used for saving playlist data. It is
      * ensured that the file does not yet exist.
      *
      * @param name the name of the file
      * @return the path to this temporary file
      */
    private def initPlaylistFile(name: String): Path = {
      val path = createPathInDirectory(name)
      if (Files exists path) {
        Files delete path
      }
      path
    }
  }

}
