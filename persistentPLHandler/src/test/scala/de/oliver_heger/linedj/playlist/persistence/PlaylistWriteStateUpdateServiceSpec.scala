/*
 * Copyright 2015-2019 The Developers Team.
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

import java.nio.file.{Path, Paths}

import akka.actor.{ActorSystem, Props}
import akka.stream.scaladsl.{FileIO, Source}
import akka.stream.{ActorMaterializer, IOResult}
import akka.testkit.{TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, SetPlaylist}
import de.oliver_heger.linedj.playlist.persistence.LoadPlaylistActor.LoadPlaylistData
import de.oliver_heger.linedj.playlist.persistence.PlaylistFileWriterActor.WriteFile
import de.oliver_heger.linedj.playlist.persistence.PlaylistStateWriterActorSpec._
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object PlaylistWriteStateUpdateServiceSpec {
  /** Path to store the playlist. */
  private val PathPlaylist = Paths get "playlist.json"

  /** Path to store the playlist position. */
  private val PathPosition = Paths get "position.json"

  /** The size of the initial playlist. */
  private val InitialPlaylistSize = 4

  /** The current index in the initial playlist. */
  private val InitialPlaylistIndex = 1

  /** The default message to initialize the playlist. */
  private val InitialPlaylist = createInitialPlaylist()

  /** A state that is already initialized with a playlist. */
  private val StateWithPlaylist = PlaylistWriteStateUpdateServiceImpl.InitialState
    .copy(initialPlaylist = Some(InitialPlaylist.playlist),
      currentPosition = CurrentPlaylistPosition(InitialPlaylistIndex, 0, 0),
      updatedPosition = CurrentPlaylistPosition(InitialPlaylistIndex, 1000, 20))

  /** The default configuration for write operations. */
  private val WriteConfig = PlaylistWriteConfig(PathPlaylist, PathPosition, 30.seconds)

  /**
    * Generates a player state object based on the specified parameters.
    *
    * @param songCount    the number of songs in the playlist
    * @param currentIndex the index of the current song
    * @param seqNo        the sequence number of the playlist
    * @param activated    flag whether the playlist is activated
    * @return the player state object
    */
  private def createPlayerState(songCount: Int, currentIndex: Int = 0, seqNo: Int = 1,
                                activated: Boolean = true): AudioPlayerState =
    createStateFromPlaylist(generatePlaylist(songCount, currentIndex), seqNo, activated)

  /**
    * Creates a player state object based on the given parameters
    *
    * @param playlist  the current playlist
    * @param seqNo     the sequence number of the playlist
    * @param activated flag whether the playlist is activated
    * @return the player state object
    */
  private def createStateFromPlaylist(playlist: Playlist, seqNo: Int = 1,
                                      activated: Boolean = true): AudioPlayerState =
    AudioPlayerState(playlist = playlist, playlistSeqNo = seqNo, playbackActive = true,
      playlistClosed = false, playlistActivated = activated)

  /**
    * Creates the default ''SetPlaylist'' message to set the initial playlist.
    *
    * @return the default initial ''SetPlaylist'' message
    */
  private def createInitialPlaylist(): SetPlaylist =
    SetPlaylist(playlist = createPlayerState(songCount = InitialPlaylistSize,
      currentIndex = InitialPlaylistIndex).playlist)

  /**
    * Creates a test write file message for the path specified.
    *
    * @param path the path
    * @param idx  an index to generate unique sources
    * @return the test write file message
    */
  private def createWriteFile(path: Path, idx: Int): WriteFile = {
    val source = Source.single(ByteString(s"Test $idx for $path."))
    WriteFile(source, path)
  }

  /**
    * Creates a map with ''WriteFile'' objects for the specified paths.
    *
    * @param idx   an index to generate unique sources
    * @param paths the paths to be contained in the map
    * @return the resulting map
    */
  private def createWriteFileMap(idx: Int, paths: Path*): Map[Path, WriteFile] =
    paths.foldLeft(Map.empty[Path, WriteFile]) { (m, p) =>
      m + (p -> createWriteFile(p, idx))
    }

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: PlaylistWriteStateUpdateServiceImpl.StateUpdate[A],
                             oldState: PlaylistWriteState =
                             PlaylistWriteStateUpdateServiceImpl.InitialState):
  (PlaylistWriteState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: PlaylistWriteStateUpdateServiceImpl.StateUpdate[Unit],
                          oldState: PlaylistWriteState =
                          PlaylistWriteStateUpdateServiceImpl.InitialState):
  PlaylistWriteState = {
    val (next, _) = updateState(s, oldState)
    next
  }
}

/**
  * Test class for ''PlaylistWriteStateUpdateService''.
  */
class PlaylistWriteStateUpdateServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar with FileTestHelper {
  def this() = this(ActorSystem("PlaylistWriteStateUpdateServiceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  import PlaylistWriteStateUpdateServiceSpec._

  /**
    * Creates a map that assigns the test paths for the playlist and the
    * position data to temporary files.
    *
    * @return the mapping from standard paths to temporary paths
    */
  private def createTempFiles(): Map[Path, Path] =
    Map(PathPlaylist -> createFileReference(), PathPosition -> createFileReference())

  /**
    * Creates a mock playlist service.
    *
    * @return the mock playlist service
    */
  private def mockPlaylistService(): PlaylistService[Playlist, MediaFileID] =
    mock[PlaylistService[Playlist, MediaFileID]]

  /**
    * Writes the file specified by the given source to disk.
    *
    * @param src    the source
    * @param target the target file to be written
    * @param mat    the materializer
    * @return the future with the write operation
    */
  private def writeFile(src: Source[ByteString, Any], target: Path)
                       (implicit mat: ActorMaterializer): Future[IOResult] = {
    val sink = FileIO toPath target
    src.runWith(sink)
  }

  /**
    * Creates files for the specified write messages and loads them again via a
    * load actor. That way it can be tested whether playlist data is correctly
    * serialized and the correct data is produced by the service.
    *
    * @param writeMessages a sequence with messages to write files
    * @return the playlist obtained from the load actor
    */
  private def saveAndLoadPlaylist(writeMessages: Iterable[WriteFile]): SetPlaylist = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext = system.dispatcher
    val msgBus = new MessageBusTestImpl()
    val loadActor = system.actorOf(Props[LoadPlaylistActor])
    val pathMapping = createTempFiles()

    val writeResult = Future.sequence(writeMessages.map(msg =>
      writeFile(msg.source, pathMapping(msg.target))))
    writeResult foreach { _ =>
      loadActor ! LoadPlaylistData(pathMapping(PathPlaylist), pathMapping(PathPosition),
        Integer.MAX_VALUE, msgBus)
    }
    msgBus.expectMessageType[LoadedPlaylist].setPlaylist
  }

  /**
    * Persists the playlist defined by the write messages in the given state
    * object and checks them against the expected playlist.
    *
    * @param state       the write state
    * @param expPlaylist the expected playlist
    * @param expMsgCount the expected number of messages
    */
  private def checkPersistedPlaylist(state: PlaylistWriteState, expPlaylist: SetPlaylist,
                                     expMsgCount: Int = 2): Unit = {
    state.writesToTrigger should have size expMsgCount
    saveAndLoadPlaylist(state.writesToTrigger.values) should be(expPlaylist)
  }

  "A PlaylistWriteStateUpdateService" should "have a correct initial state" in {
    val state = PlaylistWriteStateUpdateServiceImpl.InitialState

    state.initialPlaylist shouldBe 'empty
    state.playlistSeqNo shouldBe 'empty
    state.currentPosition should be(CurrentPlaylistPosition(0, 0, 0))
    state.updatedPosition should be(state.currentPosition)
    state.pendingWriteOperations should have size 0
    state.writesToTrigger should have size 0
    state.writesInProgress should have size 0
    state.closeRequest shouldBe 'empty
    state.canClose shouldBe false
  }

  it should "set the initial playlist if no current index is defined" in {
    val playlist = mock[Playlist]
    val plService = mockPlaylistService()
    val PlaylistSize = 28
    val PositionOffset = 20180527215835L
    val TimeOffset = 215848
    when(plService.currentIndex(playlist)).thenReturn(None)
    when(plService.size(playlist)).thenReturn(PlaylistSize)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.initPlaylist(plService, playlist,
      PositionOffset, TimeOffset))
    next.initialPlaylist should be(Some(playlist))
    next.currentPosition should be(CurrentPlaylistPosition(PlaylistSize, PositionOffset,
      TimeOffset))
    next.updatedPosition should be(next.currentPosition)
  }

  it should "set the initial playlist if it has a current index" in {
    val playlist = mock[Playlist]
    val plService = mockPlaylistService()
    val Index = 11
    val PositionOffset = 20180527220436L
    val TimeOffset = 220447
    when(plService.currentIndex(playlist)).thenReturn(Some(Index))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.initPlaylist(plService, playlist,
      PositionOffset, TimeOffset))
    next.initialPlaylist should be(Some(playlist))
    next.currentPosition should be(CurrentPlaylistPosition(Index, PositionOffset, TimeOffset))
    next.updatedPosition should be(next.currentPosition)
  }

  it should "ignore another request to set the initial playlist" in {
    val plService = mockPlaylistService()
    val state = PlaylistWriteStateUpdateServiceImpl.InitialState
      .copy(initialPlaylist = Some(mock[Playlist]))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.initPlaylist(plService,
      mock[Playlist], 20180527221030L, 221036), state)
    next should be theSameInstanceAs state
  }

  it should "process a player event if the playlist is not activated" in {
    val plService = mockPlaylistService()

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(plService,
      createPlayerState(InitialPlaylistSize + 5, activated = false), WriteConfig),
      StateWithPlaylist)
    next should be theSameInstanceAs StateWithPlaylist
    verifyZeroInteractions(plService)
  }

  it should "trigger a playlist write operation if the sequence number changes" in {
    val plService = PlaylistService
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(1))
    val playerState = createPlayerState(InitialPlaylistSize + 1,
      currentIndex = InitialPlaylistIndex + 1, seqNo = 2)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(plService,
      playerState, WriteConfig), state)
    checkPersistedPlaylist(next, SetPlaylist(playerState.playlist))
    next.updatedPosition should be(CurrentPlaylistPosition(InitialPlaylistIndex + 1, 0, 0))
    next.playlistSeqNo should be(Some(2))
  }

  it should "write the checksum of media file IDs if defined" in {
    val plService = PlaylistService
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(1))
    val playlist = generatePlaylistWithChecksum(InitialPlaylistSize + 1,
      currentIdx = InitialPlaylistIndex + 1, checksum = "someChecksum")
    val playerState = createStateFromPlaylist(playlist, seqNo = 2)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(plService,
      playerState, WriteConfig), state)
    checkPersistedPlaylist(next, SetPlaylist(playerState.playlist))
    next.updatedPosition should be(CurrentPlaylistPosition(InitialPlaylistIndex + 1, 0, 0))
    next.playlistSeqNo should be(Some(2))
  }

  it should "ignore a player event if a close request is pending" in {
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(1),
      closeRequest = Some(TestProbe().ref))
    val playerState = createPlayerState(InitialPlaylistSize + 1,
      currentIndex = InitialPlaylistIndex + 1, seqNo = 2)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl
      .playerStateChange(mockPlaylistService(), playerState, WriteConfig), state)
    next should be theSameInstanceAs state
  }

  it should "ignore a player event if there is no initial playlist" in {
    val state = PlaylistWriteStateUpdateServiceImpl.InitialState
      .copy(playlistSeqNo = Some(1))
    val playerState = createPlayerState(InitialPlaylistSize + 1,
      currentIndex = InitialPlaylistIndex + 1, seqNo = 2)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl
      .playerStateChange(mockPlaylistService(), playerState, WriteConfig), state)
    next should be theSameInstanceAs state
  }

  it should "not trigger a write operation for a player event if there is no change" in {
    val playerState = createPlayerState(InitialPlaylistSize, currentIndex = InitialPlaylistIndex)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl
      .playerStateChange(PlaylistService, playerState, WriteConfig), state)
    next should be theSameInstanceAs state
  }

  it should "trigger a playlist write operation if there is no seq number" in {
    val playerState = createPlayerState(InitialPlaylistSize + 1,
      currentIndex = InitialPlaylistIndex + 1)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl
      .playerStateChange(PlaylistService, playerState, WriteConfig), StateWithPlaylist)
    checkPersistedPlaylist(next, SetPlaylist(playerState.playlist))
    next.updatedPosition should be(CurrentPlaylistPosition(InitialPlaylistIndex + 1, 0, 0))
    next.playlistSeqNo should be(Some(playerState.playlistSeqNo))
  }

  it should "not trigger a playlist write operation if the init playlist stays the same" in {
    val playerState = createPlayerState(InitialPlaylistSize, currentIndex = InitialPlaylistIndex)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(PlaylistService,
      playerState, WriteConfig), StateWithPlaylist)
    next should be theSameInstanceAs StateWithPlaylist
  }

  it should "deal with a player event containing an exhausted playlist" in {
    val playerState = createPlayerState(InitialPlaylistSize, seqNo = 5)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo - 1))
    val plService = mockPlaylistService()
    when(plService.currentIndex(playerState.playlist)).thenReturn(None)
    when(plService.size(playerState.playlist)).thenReturn(InitialPlaylistSize)
    when(plService.toSongList(playerState.playlist))
      .thenReturn(PlaylistService.toSongList(playerState.playlist))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(plService,
      playerState, WriteConfig), state)
    next.writesToTrigger should have size 2
    val setPl = saveAndLoadPlaylist(next.writesToTrigger.values)
    PlaylistService.currentIndex(setPl.playlist) should be(None)
  }

  it should "not write the playlist if there are no changes" in {
    val playerState = createPlayerState(InitialPlaylistSize,
      currentIndex = InitialPlaylistIndex + 1)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(PlaylistService,
      playerState, WriteConfig), state)
    next.writesToTrigger.keys should contain only PathPosition
    next.updatedPosition.index should be(InitialPlaylistIndex + 1)
  }

  it should "not write the position if there are no changes" in {
    val playerState = createPlayerState(InitialPlaylistSize + 1,
      currentIndex = InitialPlaylistIndex)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo + 1))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(PlaylistService,
      playerState, WriteConfig), state)
    next.writesToTrigger.keys should contain only PathPlaylist
  }

  it should "only trigger a write for the playlist if none is in progress" in {
    val mapWrites = createWriteFileMap(1, PathPlaylist, PathPosition)
    val mapPending = createWriteFileMap(2, PathPlaylist, PathPosition)
    val playerState = createPlayerState(InitialPlaylistSize + 1, seqNo = 28,
      currentIndex = InitialPlaylistIndex + 1)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo - 1),
      writesToTrigger = mapWrites, pendingWriteOperations = mapPending,
      writesInProgress = Set(PathPlaylist))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(PlaylistService,
      playerState, WriteConfig), state)
    next.writesToTrigger(PathPlaylist) should be(mapWrites(PathPlaylist))
    next.writesToTrigger(PathPosition) should not be mapWrites(PathPosition)
    next.pendingWriteOperations(PathPosition) should be(mapPending(PathPosition))
    next.pendingWriteOperations(PathPlaylist) should not be mapPending(PathPlaylist)
  }

  it should "only trigger a write for the position if none is in progress" in {
    val mapWrites = createWriteFileMap(1, PathPlaylist, PathPosition)
    val mapPending = createWriteFileMap(2, PathPlaylist, PathPosition)
    val playerState = createPlayerState(InitialPlaylistSize + 1, seqNo = 28,
      currentIndex = InitialPlaylistIndex + 1)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo - 1),
      writesToTrigger = mapWrites, pendingWriteOperations = mapPending,
      writesInProgress = Set(PathPosition))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playerStateChange(PlaylistService,
      playerState, WriteConfig), state)
    next.writesToTrigger(PathPlaylist) should not be mapWrites(PathPlaylist)
    next.writesToTrigger(PathPosition) should be(mapWrites(PathPosition))
    next.pendingWriteOperations(PathPosition) should not be mapPending(PathPosition)
    next.pendingWriteOperations(PathPlaylist) should be(mapPending(PathPlaylist))
  }

  it should "not trigger a position write if the auto save interval is not reached" in {
    val PosOffset = 20180531211504L
    val TimeOffset = WriteConfig.autoSaveInterval.toSeconds - 1

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.playbackProgress(PosOffset,
      TimeOffset, WriteConfig), StateWithPlaylist)
    next.writesToTrigger should have size 0
    next.updatedPosition should be(CurrentPlaylistPosition(InitialPlaylistIndex, PosOffset,
      TimeOffset))
  }

  it should "ignore a progress event if there is no initial playlist" in {
    val next = modifyState(PlaylistWriteStateUpdateServiceImpl
      .playbackProgress(20180531212112L, 42, WriteConfig))
    next should be theSameInstanceAs PlaylistWriteStateUpdateServiceImpl.InitialState
  }

  it should "ignore a progress event if a close operation is in progress" in {
    val state = StateWithPlaylist.copy(closeRequest = Some(TestProbe().ref))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl
      .playbackProgress(20180531212417L, 24, WriteConfig), state)
    next should be theSameInstanceAs state
  }

  it should "trigger a position write if the auto interval is reached" in {
    val PosOffset = 20180601204850L
    val TimeOffset = WriteConfig.autoSaveInterval.toSeconds
    val playerState = createPlayerState(InitialPlaylistSize + 1)
    val expPlaylist = SetPlaylist(playerState.playlist, positionOffset = PosOffset,
      timeOffset = TimeOffset)

    val update = for {_ <- PlaylistWriteStateUpdateServiceImpl
      .playerStateChange(PlaylistService, playerState, WriteConfig)
                      _ <- PlaylistWriteStateUpdateServiceImpl.playbackProgress(PosOffset,
                        TimeOffset,
                        WriteConfig)
    } yield ()
    val next = modifyState(update, StateWithPlaylist)
    checkPersistedPlaylist(next, expPlaylist)
    next.pendingWriteOperations should have size 0
  }

  it should "check writes in progress when a progress event arrives" in {
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPosition))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl
      .playbackProgress(20180601211405L, WriteConfig.autoSaveInterval.toSeconds,
        WriteConfig), state)
    next.pendingWriteOperations.keys should contain only PathPosition
  }

  it should "remove a file that was written from the writes in progress" in {
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPlaylist, PathPosition))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.fileWritten(PathPlaylist), state)
    next.writesInProgress should contain only PathPosition
  }

  it should "trigger a new write when a file was written if possible" in {
    val pendingWrites = createWriteFileMap(1, PathPosition, PathPlaylist)
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPosition),
      pendingWriteOperations = pendingWrites)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.fileWritten(PathPosition), state)
    next.pendingWriteOperations should have size 1
    next.pendingWriteOperations(PathPlaylist) should be(pendingWrites(PathPlaylist))
    next.writesToTrigger should have size 1
    next.writesToTrigger(PathPosition) should be(pendingWrites(PathPosition))
    next.canClose shouldBe false
  }

  it should "answer a close request if all files have been written" in {
    val state = StateWithPlaylist.copy(closeRequest = Some(TestProbe().ref),
      writesInProgress = Set(PathPlaylist))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.fileWritten(PathPlaylist), state)
    next.canClose shouldBe true
  }

  it should "only set the canClose flag if a close request is pending" in {
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPlaylist))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.fileWritten(PathPlaylist), state)
    next.canClose shouldBe false
  }

  it should "ignore a close request if there is already one" in {
    val state = StateWithPlaylist.copy(closeRequest = Some(TestProbe().ref))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.closeRequest(TestProbe().ref,
      WriteConfig), state)
    next should be(state)
  }

  it should "answer a close request directly if possible" in {
    val client = TestProbe().ref
    val state = StateWithPlaylist.copy(updatedPosition = StateWithPlaylist.currentPosition)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.closeRequest(client,
      WriteConfig), state)
    next.closeRequest should be(Some(client))
    next.canClose shouldBe true
  }

  it should "not answer a close request if there are writes in progress" in {
    val client = TestProbe().ref
    val state = StateWithPlaylist.copy(updatedPosition = StateWithPlaylist.currentPosition,
      writesInProgress = Set(PathPosition))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.closeRequest(client, WriteConfig),
      state)
    next.closeRequest should be(Some(client))
    next.canClose shouldBe false
  }

  it should "not answer a close request if there are writes to trigger" in {
    val state = StateWithPlaylist.copy(updatedPosition = StateWithPlaylist.currentPosition,
      writesToTrigger = createWriteFileMap(1, PathPlaylist))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.closeRequest(TestProbe().ref,
      WriteConfig), state)
    next.canClose shouldBe false
  }

  it should "write an updated position when receiving a close request" in {
    val playerState = createPlayerState(InitialPlaylistSize + 1)
    val service = PlaylistWriteStateUpdateServiceImpl
    val s1 = modifyState(service.playerStateChange(PlaylistService, playerState, WriteConfig),
      StateWithPlaylist).copy(updatedPosition = StateWithPlaylist.updatedPosition)

    val next = modifyState(service.closeRequest(TestProbe().ref, WriteConfig), s1)
    val playlist = saveAndLoadPlaylist(next.writesToTrigger.values)
    playlist.positionOffset should be(StateWithPlaylist.updatedPosition.positionOffset)
    playlist.timeOffset should be(StateWithPlaylist.updatedPosition.timeOffset)
    next.canClose shouldBe false
  }

  it should "return the same state if there are no write messages" in {
    val (next, messages) = updateState(PlaylistWriteStateUpdateServiceImpl
      .fileWriteMessages(WriteConfig))

    next should be theSameInstanceAs PlaylistWriteStateUpdateServiceImpl.InitialState
    messages should have size 0
  }

  it should "return messages to be sent to the file writer actor" in {
    val msgMap = createWriteFileMap(1, PathPlaylist, PathPosition)
    val state = StateWithPlaylist.copy(writesToTrigger = msgMap)

    val (next, messages) = updateState(PlaylistWriteStateUpdateServiceImpl
      .fileWriteMessages(WriteConfig), state)
    messages should contain only (msgMap.values.toSeq: _*)
    next.writesToTrigger should have size 0
    next.writesInProgress should contain only(PathPosition, PathPlaylist)
    next.currentPosition should be(state.updatedPosition)
  }

  it should "update the current position only for a write of the position file" in {
    val msgMap = createWriteFileMap(1, PathPlaylist)
    val state = StateWithPlaylist.copy(writesToTrigger = msgMap)

    val (next, _) = updateState(PlaylistWriteStateUpdateServiceImpl
      .fileWriteMessages(WriteConfig), state)
    next.currentPosition should be(state.currentPosition)
  }

  it should "return an Option with the actor to send a close ACK message" in {
    val closeActor = TestProbe().ref
    val state = StateWithPlaylist.copy(closeRequest = Some(closeActor))

    val (next, act) = updateState(PlaylistWriteStateUpdateServiceImpl.closeActor(), state)
    act should be(Some(closeActor))
    next should be(state)
  }

  it should "handle a state change notification from the audio player" in {
    val plService = PlaylistService
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(1))
    val playerState = createPlayerState(InitialPlaylistSize + 1,
      currentIndex = InitialPlaylistIndex + 1, seqNo = 2)

    val (next, messages) = updateState(PlaylistWriteStateUpdateServiceImpl
      .handlePlayerStateChange(plService, playerState, WriteConfig), state)
    saveAndLoadPlaylist(messages.writes) should be(SetPlaylist(playerState.playlist))
    messages.closeAck shouldBe 'empty
    next.playlistSeqNo should be(Some(2))
    next.writesToTrigger should have size 0
  }

  it should "handle a playback progress event" in {
    val PosOffset = 20180604181640L
    val TimeOffset = WriteConfig.autoSaveInterval.toSeconds
    val playerState = createPlayerState(InitialPlaylistSize + 1)
    val expPlaylist = SetPlaylist(playerState.playlist, positionOffset = PosOffset,
      timeOffset = TimeOffset)
    val service = PlaylistWriteStateUpdateServiceImpl

    val update = for {_ <- service.playerStateChange(PlaylistService, playerState, WriteConfig)
                      msg <- service.handlePlaybackProgress(PosOffset, TimeOffset, WriteConfig)
    } yield msg
    val (next, messages) = updateState(update, StateWithPlaylist)
    saveAndLoadPlaylist(messages.writes) should be(expPlaylist)
    next.pendingWriteOperations should have size 0
  }

  it should "handle a file written notification" in {
    val closeActor = TestProbe().ref
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPosition),
      closeRequest = Some(closeActor))

    val (next, messages) = updateState(PlaylistWriteStateUpdateServiceImpl
      .handleFileWritten(PathPosition, WriteConfig), state)
    messages.writes should have size 0
    messages.closeAck should be(Some(closeActor))
    next.writesInProgress should have size 0
    next.canClose shouldBe true
  }

  it should "handle a close request" in {
    val client = TestProbe().ref
    val state = StateWithPlaylist.copy(updatedPosition = StateWithPlaylist.currentPosition)

    val (next, messages) = updateState(PlaylistWriteStateUpdateServiceImpl
      .handleCloseRequest(client, WriteConfig), state)
    next.closeRequest should be(Some(client))
    next.canClose shouldBe true
    messages.writes should have size 0
    messages.closeAck should be(Some(client))
  }
}
