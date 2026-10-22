/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.platform.audio2.impl.PlaylistServiceImpl
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerCommands, AudioPlayerState}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Path, Paths}
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object PlaylistWriteStateUpdateServiceSpec:
  /** Path to store the playlist. */
  private val PathPlaylist = Paths get "playlist.json"

  /** Path to store the playlist position. */
  private val PathPosition = Paths get "position.json"

  /** The size of the initial playlist. */
  private val InitialPlaylistSize = 4

  /** The current index in the initial playlist. */
  private val InitialPlaylistIndex = 1

  /** The initial player state with the initial playlist. */
  private val InitialPlayerState =
    createPlayerState(songCount = InitialPlaylistSize, currentIndex = InitialPlaylistIndex)

  /** The default message to initialize the playlist. */
  private val InitialPlaylist = createInitialPlaylist()

  /** A state that is already initialized with a playlist. */
  private val StateWithPlaylist = PlaylistWriteStateUpdateServiceImpl.InitialState.copy(
    initialPlaylist = Some(InitialPlayerState.playlist),
    currentPosition = PlaylistModel.CurrentPlaylistPosition(InitialPlaylistIndex, 0, 0),
    updatedPosition = PlaylistModel.CurrentPlaylistPosition(InitialPlaylistIndex, 1000, 20)
  )

  /** The default configuration for write operations. */
  private val WriteConfig = PlaylistWriteStateUpdateService.PlaylistWriteConfig(PathPlaylist, PathPosition, 30.seconds)

  /**
    * Generates a player state object based on the specified parameters.
    *
    * @param songCount    the number of songs in the playlist
    * @param currentIndex the index of the current song
    * @param seqNo        the sequence number of the playlist
    * @param activated    flag whether the playlist is activated
    * @return the player state object
    */
  private def createPlayerState(songCount: Int,
                                currentIndex: Int = 0,
                                seqNo: Int = 1,
                                activated: Boolean = true): AudioPlayerState =
    createStateFromPlaylist(generatePlaylist(songCount, currentIndex), seqNo, activated)

  /**
    * Generates a test playlist consisting of the given number of test songs.
    *
    * @param length     the length of the playlist
    * @param currentIdx the index of the current song (0-based)
    * @return the resulting playlist
    */
  private def generatePlaylist(length: Int, currentIdx: Int): Playlist =
    @tailrec def moveToCurrent(pl: Playlist): Playlist =
      if pl.playedSongs.lengthCompare(currentIdx) < 0 then
        moveToCurrent(PlaylistServiceImpl.moveForwards(pl).get)
      else pl

    val songs = (0 until length).foldRight(List.empty[String]): (i, lst) =>
      s"song-id-$i" :: lst
    moveToCurrent(Playlist(songs, Nil))

  /**
    * Creates a player state object based on the given parameters
    *
    * @param playlist  the current playlist
    * @param seqNo     the sequence number of the playlist
    * @param activated flag whether the playlist is activated
    * @return the player state object
    */
  private def createStateFromPlaylist(playlist: Playlist,
                                      seqNo: Int = 1,
                                      activated: Boolean = true): AudioPlayerState =
    AudioPlayerState(
      playlist = playlist,
      playlistSeqNo = seqNo,
      playbackActive = true,
      playlistClosed = false,
      playlistActivated = activated
    )

  /**
    * Creates the default _SetPlaylist_ message to set the initial playlist.
    *
    * @return the default initial _SetPlaylist_ message
    */
  private def createInitialPlaylist(): AudioPlayerCommands =
    AudioPlayerCommands.SetPlaylist(playlist = InitialPlayerState.playlist)

  /**
    * Creates a test write file message for the path specified.
    *
    * @param path the path
    * @param idx  an index to generate unique sources
    * @return the test write file message
    */
  private def createWriteFile(path: Path, idx: Int): PlaylistSaveService.WriteOperation =
    val source = Source.single(ByteString(s"Test $idx for $path."))
    PlaylistSaveService.WriteOperation(path, source)

  /**
    * Creates a map with _WriteOperation_ objects for the specified paths.
    *
    * @param idx   an index to generate unique sources
    * @param paths the paths to be contained in the map
    * @return the resulting map
    */
  private def createWriteFileMap(idx: Int, paths: Path*): Map[Path, PlaylistSaveService.WriteOperation] =
    paths.foldLeft(Map.empty[Path, PlaylistSaveService.WriteOperation]): (m, p) =>
      m + (p -> createWriteFile(p, idx))

  /**
    * Convenience method to execute a _State_ object to produce the updated
    * state and an additional result.
    *
    * @param s        the _State_
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: PlaylistWriteStateUpdateServiceImpl.StateUpdate[A],
                             oldState: PlaylistWriteStateUpdateService.PlaylistWriteState =
                             PlaylistWriteStateUpdateServiceImpl.InitialState):
  (PlaylistWriteStateUpdateService.PlaylistWriteState, A) = s(oldState)

  /**
    * Convenience method to modify a _State_ object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the _State_
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: PlaylistWriteStateUpdateServiceImpl.StateUpdate[Unit],
                          oldState: PlaylistWriteStateUpdateService.PlaylistWriteState =
                          PlaylistWriteStateUpdateServiceImpl.InitialState):
  PlaylistWriteStateUpdateService.PlaylistWriteState =
    val (next, _) = updateState(s, oldState)
    next
end PlaylistWriteStateUpdateServiceSpec

/**
  * Test class for [[PlaylistWriteStateUpdateService]].
  */
class PlaylistWriteStateUpdateServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar, FileTestHelper:
  def this() = this(ActorSystem("PlaylistWriteStateUpdateServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    tearDownTestFile()

  import PlaylistWriteStateUpdateServiceSpec.*

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
  private def mockPlaylistService(): PlaylistService[Playlist, String] = mock

  /**
    * Writes the file specified by the given source to disk.
    *
    * @param src    the source
    * @param target the target file to be written
    * @return the future with the write operation
    */
  private def writeFile(src: Source[ByteString, Any], target: Path): Future[IOResult] =
    val sink = FileIO toPath target
    src.runWith(sink)

  /**
    * Creates files for the specified write operations and loads them again via 
    * the default load function. That way it can be tested whether playlist 
    * data is  correctly serialized, and the correct data is produced by the 
    * service.
    *
    * @param writeOperations a sequence with operations to write files
    * @return the playlist obtained from the load function
    */
  private def saveAndLoadPlaylist(writeOperations: Iterable[PlaylistSaveService.WriteOperation]): AudioPlayerCommands =
    val loadFunc = PlaylistLoader.defaultLoaderFunc
    val pathMapping = createTempFiles()

    given ExecutionContext = system.dispatcher

    val writeResult = Future.sequence(writeOperations.map(msg => writeFile(msg.data, pathMapping(msg.target))))
    val transferQueue = new ArrayBlockingQueue[AudioPlayerCommands](1)
    writeResult.foreach: _ =>
      val config = PlaylistLoader.Config(pathMapping(PathPlaylist), pathMapping(PathPosition), Integer.MAX_VALUE)
      loadFunc(config).foreach(_.foreach(cmd => transferQueue.offer(cmd)))

    transferQueue.poll(3, TimeUnit.SECONDS)

  /**
    * Persists the playlist defined by the write operations in the given state
    * object and checks them against the expected playlist.
    *
    * @param state       the write state
    * @param expPlaylist the expected playlist
    * @param expOpCount  the expected number of operations
    */
  private def checkPersistedPlaylist(state: PlaylistWriteStateUpdateService.PlaylistWriteState,
                                     expPlaylist: AudioPlayerCommands,
                                     expOpCount: Int = 2): Unit =
    state.writesToTrigger should have size expOpCount
    saveAndLoadPlaylist(state.writesToTrigger.values) should be(expPlaylist)

  /**
    * Creates a service to save a playlist.
    *
    * @return the save service
    */
  private def saveService(): PlaylistSaveService = PlaylistSaveService(PlaylistServiceImpl)

  "A PlaylistWriteStateUpdateService" should "have a correct initial state" in :
    val state = PlaylistWriteStateUpdateServiceImpl.InitialState

    state.initialPlaylist shouldBe empty
    state.playlistSeqNo shouldBe empty
    state.currentPosition should be(PlaylistModel.CurrentPlaylistPosition(0, 0, 0))
    state.updatedPosition should be(state.currentPosition)
    state.pendingWriteOperations should have size 0
    state.writesToTrigger should have size 0
    state.writesInProgress should have size 0
    state.closeRequested shouldBe false
    state.canClose shouldBe false

  it should "set the initial playlist if no current index is defined" in :
    val playlist = mock[Playlist]
    val plService = mockPlaylistService()
    val PlaylistSize = 28
    val PositionOffset = 20261019229943L
    val TimeOffset = 215848
    when(plService.currentIndex(playlist)).thenReturn(None)
    when(plService.size(playlist)).thenReturn(PlaylistSize)

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.initPlaylist(plService, playlist, PositionOffset, TimeOffset.seconds)
    )
    next.initialPlaylist should be(Some(playlist))
    next.currentPosition should be(PlaylistModel.CurrentPlaylistPosition(PlaylistSize, PositionOffset, TimeOffset))
    next.updatedPosition should be(next.currentPosition)

  it should "set the initial playlist if it has a current index" in :
    val playlist = mock[Playlist]
    val plService = mockPlaylistService()
    val Index = 11
    val PositionOffset = 20261019220517L
    val TimeOffset = 220447
    when(plService.currentIndex(playlist)).thenReturn(Some(Index))

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.initPlaylist(
        plService,
        playlist,
        PositionOffset,
        TimeOffset.seconds
      )
    )
    next.initialPlaylist should be(Some(playlist))
    next.currentPosition should be(PlaylistModel.CurrentPlaylistPosition(Index, PositionOffset, TimeOffset))
    next.updatedPosition should be(next.currentPosition)

  it should "ignore another request to set the initial playlist" in :
    val plService = mockPlaylistService()
    val state = PlaylistWriteStateUpdateServiceImpl.InitialState
      .copy(initialPlaylist = Some(mock[Playlist]))

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.initPlaylist(plService, mock[Playlist], 20261019220712L, 221036.seconds),
      state
    )
    next should be theSameInstanceAs state

  it should "process a player event if the playlist is not activated" in :
    val plService = mockPlaylistService()

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        plService,
        saveService(),
        createPlayerState(InitialPlaylistSize + 5, activated = false),
        WriteConfig
      ),
      StateWithPlaylist
    )

    next should be theSameInstanceAs StateWithPlaylist
    verifyNoInteractions(plService)

  it should "trigger a playlist write operation if the sequence number changes" in :
    val plService = PlaylistServiceImpl
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(1))
    val playerState = createPlayerState(InitialPlaylistSize + 1, currentIndex = InitialPlaylistIndex + 1, seqNo = 2)

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(plService, saveService(), playerState, WriteConfig),
      state
    )

    checkPersistedPlaylist(next, AudioPlayerCommands.SetPlaylist(playerState.playlist))
    next.updatedPosition should be(PlaylistModel.CurrentPlaylistPosition(InitialPlaylistIndex + 1, 0, 0))
    next.playlistSeqNo should be(Some(2))

  it should "ignore a player event if a close request is pending" in :
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(1), closeRequested = true)
    val playerState = createPlayerState(InitialPlaylistSize + 1, currentIndex = InitialPlaylistIndex + 1, seqNo = 2)

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        mockPlaylistService(),
        saveService(),
        playerState,
        WriteConfig
      ),
      state
    )

    next should be theSameInstanceAs state

  it should "ignore a player event if there is no initial playlist" in :
    val state = PlaylistWriteStateUpdateServiceImpl.InitialState.copy(playlistSeqNo = Some(1))
    val playerState = createPlayerState(InitialPlaylistSize + 1, currentIndex = InitialPlaylistIndex + 1, seqNo = 2)

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        mockPlaylistService(),
        saveService(),
        playerState,
        WriteConfig
      ),
      state
    )

    next should be theSameInstanceAs state

  it should "not trigger a write operation for a player event if there is no change" in :
    val playerState = createPlayerState(InitialPlaylistSize, currentIndex = InitialPlaylistIndex)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo))

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        PlaylistServiceImpl,
        saveService(),
        playerState,
        WriteConfig
      ),
      state
    )

    next should be theSameInstanceAs state

  it should "trigger a playlist write operation if there is no seq number" in :
    val playerState = createPlayerState(InitialPlaylistSize + 1, currentIndex = InitialPlaylistIndex + 1)

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        PlaylistServiceImpl,
        saveService(),
        playerState,
        WriteConfig
      ),
      StateWithPlaylist
    )

    checkPersistedPlaylist(next, AudioPlayerCommands.SetPlaylist(playerState.playlist))
    next.updatedPosition should be(PlaylistModel.CurrentPlaylistPosition(InitialPlaylistIndex + 1, 0, 0))
    next.playlistSeqNo should be(Some(playerState.playlistSeqNo))

  it should "not trigger a playlist write operation if the init playlist stays the same" in :
    val playerState = createPlayerState(InitialPlaylistSize, currentIndex = InitialPlaylistIndex)

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        PlaylistServiceImpl,
        saveService(),
        playerState,
        WriteConfig
      ),
      StateWithPlaylist
    )

    next should be theSameInstanceAs StateWithPlaylist

  it should "deal with a player event containing an exhausted playlist" in :
    val playerState = createPlayerState(InitialPlaylistSize, seqNo = 5)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo - 1))
    val plService = mockPlaylistService()
    when(plService.currentIndex(playerState.playlist)).thenReturn(None)
    when(plService.size(playerState.playlist)).thenReturn(InitialPlaylistSize)
    when(plService.toSongList(playerState.playlist))
      .thenReturn(PlaylistServiceImpl.toSongList(playerState.playlist))

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(plService, saveService(), playerState, WriteConfig),
      state
    )

    next.writesToTrigger should have size 2
    saveAndLoadPlaylist(next.writesToTrigger.values) match
      case setPl: AudioPlayerCommands.SetPlaylist =>
        PlaylistServiceImpl.currentIndex(setPl.playlist) should be(None)
      case c => fail("Unexpected command: " + c)

  it should "not write the playlist if there are no changes" in :
    val playerState = createPlayerState(InitialPlaylistSize, currentIndex = InitialPlaylistIndex + 1)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo))

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        PlaylistServiceImpl,
        saveService(),
        playerState,
        WriteConfig
      ),
      state
    )

    next.writesToTrigger.keys should contain only PathPosition
    next.updatedPosition.index should be(InitialPlaylistIndex + 1)

  it should "not write the position if there are no changes" in :
    val playerState = createPlayerState(InitialPlaylistSize + 1, currentIndex = InitialPlaylistIndex)
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(playerState.playlistSeqNo + 1))

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        PlaylistServiceImpl,
        saveService(),
        playerState,
        WriteConfig
      ),
      state
    )

    next.writesToTrigger.keys should contain only PathPlaylist

  it should "only trigger a write for the playlist if none is in progress" in :
    val mapWrites = createWriteFileMap(1, PathPlaylist, PathPosition)
    val mapPending = createWriteFileMap(2, PathPlaylist, PathPosition)
    val playerState = createPlayerState(InitialPlaylistSize + 1, seqNo = 28, currentIndex = InitialPlaylistIndex + 1)
    val state = StateWithPlaylist.copy(
      playlistSeqNo = Some(playerState.playlistSeqNo - 1),
      writesToTrigger = mapWrites,
      pendingWriteOperations = mapPending,
      writesInProgress = Set(PathPlaylist)
    )

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        PlaylistServiceImpl,
        saveService(),
        playerState,
        WriteConfig
      ),
      state
    )

    next.writesToTrigger(PathPlaylist) should be(mapWrites(PathPlaylist))
    next.writesToTrigger(PathPosition) should not be mapWrites(PathPosition)
    next.pendingWriteOperations(PathPosition) should be(mapPending(PathPosition))
    next.pendingWriteOperations(PathPlaylist) should not be mapPending(PathPlaylist)

  it should "only trigger a write for the position if none is in progress" in :
    val mapWrites = createWriteFileMap(1, PathPlaylist, PathPosition)
    val mapPending = createWriteFileMap(2, PathPlaylist, PathPosition)
    val playerState = createPlayerState(InitialPlaylistSize + 1, seqNo = 28, currentIndex = InitialPlaylistIndex + 1)
    val state = StateWithPlaylist.copy(
      playlistSeqNo = Some(playerState.playlistSeqNo - 1),
      writesToTrigger = mapWrites,
      pendingWriteOperations = mapPending,
      writesInProgress = Set(PathPosition)
    )

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        PlaylistServiceImpl,
        saveService(),
        playerState,
        WriteConfig
      ),
      state
    )

    next.writesToTrigger(PathPlaylist) should not be mapWrites(PathPlaylist)
    next.writesToTrigger(PathPosition) should be(mapWrites(PathPosition))
    next.pendingWriteOperations(PathPosition) should not be mapPending(PathPosition)
    next.pendingWriteOperations(PathPlaylist) should be(mapPending(PathPlaylist))

  it should "not trigger a position write if the auto save interval is not reached" in :
    val PosOffset = 20261020214017L
    val TimeOffset = WriteConfig.autoSaveInterval.toSeconds - 1

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playbackProgress(
        saveService(),
        PosOffset,
        TimeOffset.seconds,
        WriteConfig
      ),
      StateWithPlaylist
    )

    next.writesToTrigger should have size 0
    next.updatedPosition should be(PlaylistModel.CurrentPlaylistPosition(InitialPlaylistIndex, PosOffset, TimeOffset))

  it should "ignore a progress event if there is no initial playlist" in :
    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playbackProgress(saveService(), 20261020214114L, 42.seconds, WriteConfig)
    )

    next should be theSameInstanceAs PlaylistWriteStateUpdateServiceImpl.InitialState

  it should "ignore a progress event if a close operation is in progress" in :
    val state = StateWithPlaylist.copy(closeRequested = true)

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playbackProgress(saveService(), 20261020214333L, 24.seconds, WriteConfig),
      state
    )

    next should be theSameInstanceAs state

  it should "trigger a position write if the auto interval is reached" in :
    val PosOffset = 20261020214419L
    val TimeOffset = WriteConfig.autoSaveInterval
    val playerState = createPlayerState(InitialPlaylistSize + 1)
    val expPlaylist = AudioPlayerCommands.SetPlaylist(
      playerState.playlist,
      positionOffset = PosOffset,
      timeOffset = TimeOffset
    )

    val update = for
      _ <- PlaylistWriteStateUpdateServiceImpl.playerStateChange(
        PlaylistServiceImpl,
        saveService(),
        playerState,
        WriteConfig
      )
      _ <- PlaylistWriteStateUpdateServiceImpl.playbackProgress(
        saveService(),
        PosOffset,
        TimeOffset,
        WriteConfig
      )
    yield ()
    val next = modifyState(update, StateWithPlaylist)

    checkPersistedPlaylist(next, expPlaylist)
    next.pendingWriteOperations should have size 0

  it should "check writes in progress when a progress event arrives" in :
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPosition))

    val next = modifyState(
      PlaylistWriteStateUpdateServiceImpl.playbackProgress(
        saveService(),
        20261020214944L,
        WriteConfig.autoSaveInterval,
        WriteConfig
      ),
      state
    )

    next.pendingWriteOperations.keys should contain only PathPosition

  it should "remove a file that was written from the writes in progress" in :
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPlaylist, PathPosition))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.fileWritten(PathPlaylist), state)

    next.writesInProgress should contain only PathPosition

  it should "trigger a new write when a file was written if possible" in :
    val pendingWrites = createWriteFileMap(1, PathPosition, PathPlaylist)
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPosition), pendingWriteOperations = pendingWrites)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.fileWritten(PathPosition), state)

    next.pendingWriteOperations should have size 1
    next.pendingWriteOperations(PathPlaylist) should be(pendingWrites(PathPlaylist))
    next.writesToTrigger should have size 1
    next.writesToTrigger(PathPosition) should be(pendingWrites(PathPosition))
    next.canClose shouldBe false

  it should "answer a close request if all files have been written" in :
    val state = StateWithPlaylist.copy(closeRequested = true, writesInProgress = Set(PathPlaylist))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.fileWritten(PathPlaylist), state)

    next.canClose shouldBe true

  it should "only set the canClose flag if a close request is pending" in :
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPlaylist))

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.fileWritten(PathPlaylist), state)

    next.canClose shouldBe false

  it should "ignore a close request if there is already one" in :
    val state = StateWithPlaylist.copy(closeRequested = true)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.closeRequest(saveService(), WriteConfig), state)

    next should be(state)

  it should "answer a close request directly if possible" in :
    val state = StateWithPlaylist.copy(updatedPosition = StateWithPlaylist.currentPosition)

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.closeRequest(saveService(), WriteConfig), state)

    next.closeRequested shouldBe true
    next.canClose shouldBe true

  it should "not answer a close request if there are writes in progress" in :
    val state = StateWithPlaylist.copy(
      updatedPosition = StateWithPlaylist.currentPosition,
      writesInProgress = Set(PathPosition)
    )

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.closeRequest(saveService(), WriteConfig), state)

    next.closeRequested shouldBe true
    next.canClose shouldBe false

  it should "not answer a close request if there are writes to trigger" in :
    val state = StateWithPlaylist.copy(
      updatedPosition = StateWithPlaylist.currentPosition,
      writesToTrigger = createWriteFileMap(1, PathPlaylist)
    )

    val next = modifyState(PlaylistWriteStateUpdateServiceImpl.closeRequest(saveService(), WriteConfig), state)

    next.canClose shouldBe false

  it should "write an updated position when receiving a close request" in :
    val playerState = createPlayerState(InitialPlaylistSize + 1)
    val service = PlaylistWriteStateUpdateServiceImpl
    val s1 = modifyState(
      service.playerStateChange(PlaylistServiceImpl, saveService(), playerState, WriteConfig),
      StateWithPlaylist
    ).copy(updatedPosition = StateWithPlaylist.updatedPosition)

    val next = modifyState(service.closeRequest(saveService(), WriteConfig), s1)
    val playlist = saveAndLoadPlaylist(next.writesToTrigger.values)

    playlist match
      case setPl: AudioPlayerCommands.SetPlaylist =>
        setPl.positionOffset should be(StateWithPlaylist.updatedPosition.position)
        setPl.timeOffset should be(StateWithPlaylist.updatedPosition.time.seconds)
      case cmd => fail("Unexpected command: " + cmd)
    next.canClose shouldBe false

  it should "return the same state if there are no write operations" in :
    val (next, ops) = updateState(PlaylistWriteStateUpdateServiceImpl.fileWriteOperations(WriteConfig))

    next should be theSameInstanceAs PlaylistWriteStateUpdateServiceImpl.InitialState
    ops should have size 0

  it should "return operations to be passed to the save service" in :
    val msgMap = createWriteFileMap(1, PathPlaylist, PathPosition)
    val state = StateWithPlaylist.copy(writesToTrigger = msgMap)

    val (next, ops) = updateState(PlaylistWriteStateUpdateServiceImpl.fileWriteOperations(WriteConfig), state)

    ops should contain theSameElementsAs msgMap.values
    next.writesToTrigger should have size 0
    next.writesInProgress should contain only(PathPosition, PathPlaylist)
    next.currentPosition should be(state.updatedPosition)

  it should "update the current position only for a write of the position file" in :
    val msgMap = createWriteFileMap(1, PathPlaylist)
    val state = StateWithPlaylist.copy(writesToTrigger = msgMap)

    val (next, _) = updateState(PlaylistWriteStateUpdateServiceImpl.fileWriteOperations(WriteConfig), state)

    next.currentPosition should be(state.currentPosition)

  it should "handle a state change notification from the audio player" in :
    val plService = PlaylistServiceImpl
    val state = StateWithPlaylist.copy(playlistSeqNo = Some(1))
    val playerState = createPlayerState(InitialPlaylistSize + 1, currentIndex = InitialPlaylistIndex + 1, seqNo = 2)

    val (next, ops) = updateState(
      PlaylistWriteStateUpdateServiceImpl.handlePlayerStateChange(plService, saveService(), playerState, WriteConfig),
      state
    )

    saveAndLoadPlaylist(ops.writes) should be(AudioPlayerCommands.SetPlaylist(playerState.playlist))
    ops.closePossible shouldBe false
    next.playlistSeqNo should be(Some(2))
    next.writesToTrigger should have size 0

  it should "handle a playback progress event" in :
    val PosOffset = 20261020220318L
    val TimeOffset = WriteConfig.autoSaveInterval
    val playerState = createPlayerState(InitialPlaylistSize + 1)
    val expPlaylist = AudioPlayerCommands.SetPlaylist(
      playerState.playlist,
      positionOffset = PosOffset,
      timeOffset = TimeOffset
    )
    val service = PlaylistWriteStateUpdateServiceImpl

    val update = for
      _ <- service.playerStateChange(PlaylistServiceImpl, saveService(), playerState, WriteConfig)
      msg <- service.handlePlaybackProgress(saveService(), PosOffset, TimeOffset, WriteConfig)
    yield msg
    val (next, ops) = updateState(update, StateWithPlaylist)

    saveAndLoadPlaylist(ops.writes) should be(expPlaylist)
    next.pendingWriteOperations should have size 0

  it should "handle a file written notification" in :
    val state = StateWithPlaylist.copy(writesInProgress = Set(PathPosition), closeRequested = true)

    val (next, ops) = updateState(
      PlaylistWriteStateUpdateServiceImpl.handleFileWritten(PathPosition, WriteConfig),
      state
    )

    ops.writes should have size 0
    ops.closePossible shouldBe true
    next.writesInProgress should have size 0
    next.canClose shouldBe true

  it should "handle a close request" in :
    val state = StateWithPlaylist.copy(updatedPosition = StateWithPlaylist.currentPosition)

    val (next, ops) = updateState(
      PlaylistWriteStateUpdateServiceImpl.handleCloseRequest(saveService(), WriteConfig),
      state
    )

    next.closeRequested shouldBe true
    next.canClose shouldBe true
    ops.writes should have size 0
    ops.closePossible shouldBe true
