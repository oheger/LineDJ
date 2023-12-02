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

package de.oliver_heger.linedj.platform.audio.actors

import de.oliver_heger.linedj.platform.audio.*
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerFunction
import de.oliver_heger.linedj.platform.bus.{ComponentID, Identifiable}
import de.oliver_heger.linedj.player.engine.facade.{AudioPlayer, PlayerControl}
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourceFinishedEvent, AudioSourcePlaylistInfo, PlaybackProgressEvent}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.test.MessageBusTestImpl
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.{Duration, LocalDateTime}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

object AudioPlayerControllerSpec:
  /** A test medium ID. */
  private val TestMedium = MediumID("testMedium", None)

  /**
    * Generates the URI of a test song.
    *
    * @param idx the index of the test song
    * @return the URI for this test song
    */
  private def songUri(idx: Int): String = s"song_$idx.mp3"

  /**
    * Creates an ID for an audio file based on the given index.
    *
    * @param idx the index
    * @return the ''MediaFileID'' for this audio file
    */
  private def createFileID(idx: Int): MediaFileID =
    MediaFileID(TestMedium, songUri(idx))

  /**
    * Creates a playlist info object based on the given index.
    *
    * @param idx     the index
    * @param posOfs  optional position offset
    * @param timeOfs optional time offset
    * @return the ''AudioSourcePlaylistInfo'' for this index
    */
  private def createPlaylistInfo(idx: Int, posOfs: Long = 0, timeOfs: Long = 0):
  AudioSourcePlaylistInfo = AudioSourcePlaylistInfo(createFileID(idx), posOfs, timeOfs)

  /**
    * Creates a test audio source object based on the given index.
    *
    * @param idx the index
    * @return the ''AudioSource'' for this index
    */
  private def createAudioSource(idx: Int): AudioSource =
    AudioSource(songUri(idx), 4000 + idx * 100, idx, idx)

  /**
    * Converts the specified list of playlist info objects to a list of file
    * IDs.
    *
    * @param sources the list of source objects
    * @return the list with corresponding file IDs
    */
  private def extractIDs(sources: List[AudioSourcePlaylistInfo]): List[MediaFileID] =
    sources map (_.sourceID)

/**
  * Test class for ''AudioPlayerController''.
  */
class AudioPlayerControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import AudioPlayerControllerSpec._

  /**
    * Checks whether the provided time is close to the current time. This is
    * needed to check timestamps in events.
    *
    * @param time the time to be checked
    */
  private def checkCurrentTime(time: LocalDateTime): Unit =
    val timeDelta = Duration.between(time, LocalDateTime.now())
    timeDelta.getSeconds should be < 3L

  /**
    * Checks the time stamp of the specified event. It is checked whether the
    * event's time is close to the current time.
    *
    * @param event the event to be checked
    * @return the checked event
    */
  private def checkEventTime(event: AudioPlayerStateChangedEvent):
  AudioPlayerStateChangedEvent =
    checkCurrentTime(event.time)
    event

  "An AudioPlayerController" should "have an initial state" in:
    val helper = new ControllerTestHelper

    val state = helper.lastState
    state.playbackActive shouldBe false
    state.playlistClosed shouldBe false
    state.playlist.playedSongs shouldBe empty
    state.playlist.pendingSongs shouldBe empty
    state.playlistSeqNo should be(PlaylistService.SeqNoInitial)
    state.playlistActivated shouldBe false

  it should "process a SetPlaylist message with the close flag set to true" in:
    val PosOfs = 20180102
    val TimeOffset = 321456
    val playedSongs = List(createPlaylistInfo(2), createPlaylistInfo(1))
    val pendingSongs = List(createPlaylistInfo(3, PosOfs, TimeOffset), createPlaylistInfo(4))
    val helper = new ControllerTestHelper

    helper send SetPlaylist(Playlist(playedSongs = List(createFileID(2), createFileID(1)),
      pendingSongs = List(createFileID(3), createFileID(4))), positionOffset = PosOfs,
      timeOffset = TimeOffset)
    val state = checkEventTime(helper.lastStateEvent).state
    state.playbackActive shouldBe false
    state.playlistClosed shouldBe true
    state.playlistActivated shouldBe true
    state.playlist.playedSongs should be(extractIDs(playedSongs))
    state.playlist.pendingSongs should be(extractIDs(pendingSongs))
    state.playlistSeqNo should not be PlaylistService.SeqNoInitial

    val io = Mockito.inOrder(helper.audioPlayer)
    pendingSongs foreach (s => io.verify(helper.audioPlayer).addToPlaylist(s))
    io.verify(helper.audioPlayer).closePlaylist()
    verifyNoMoreInteractions(helper.audioPlayer)
    helper.expectProgressEvent(3, PosOfs, TimeOffset)

  it should "process a SetPlaylist message with the close flag set to false" in:
    val playedSongs = List(createPlaylistInfo(2), createPlaylistInfo(1))
    val pendingSongs = List(createPlaylistInfo(3), createPlaylistInfo(4))
    val helper = new ControllerTestHelper

    helper send SetPlaylist(Playlist(playedSongs = List(createFileID(2), createFileID(1)),
      pendingSongs = List(createFileID(3), createFileID(4))), closePlaylist = false)
    val state = checkEventTime(helper.lastStateEvent).state
    state.playbackActive shouldBe false
    state.playlistClosed shouldBe false
    state.playlistActivated shouldBe true
    state.playlist.playedSongs should be(extractIDs(playedSongs))
    state.playlist.pendingSongs should be(extractIDs(pendingSongs))
    state.playlistSeqNo should not be PlaylistService.SeqNoInitial

    pendingSongs foreach (s => verify(helper.audioPlayer).addToPlaylist(s))
    verifyNoMoreInteractions(helper.audioPlayer)
    helper.expectProgressEvent(3, 0, 0)

  it should "process a SetPlaylist message with an empty list of pending songs" in:
    val helper = new ControllerTestHelper

    helper send SetPlaylist(Playlist(playedSongs = List(createFileID(2), createFileID(1)),
      pendingSongs = Nil), closePlaylist = false)
    val state = checkEventTime(helper.lastStateEvent).state
    state.playlistActivated shouldBe false
    helper.expectNoProgressEvent()

  it should "not change the playlist seq number if the playlist content does not change" in:
    val playlist1 = Playlist(pendingSongs = List(createFileID(3), createFileID(4)),
      playedSongs = List(createFileID(2), createFileID(1)))
    val playlist2 = PlaylistService.moveBackwards(playlist1).get
    val helper = new ControllerTestHelper
    val state1 = helper.send(SetPlaylist(playlist1)).lastState

    val state2 = helper.send(SetPlaylist(playlist2, positionOffset = 100,
      timeOffset = 11)).lastState
    state2.playlistSeqNo should be(state1.playlistSeqNo)

  it should "reset the audio player when setting a new playlist, and songs are pending" in:
    val helper = new ControllerTestHelper
    helper send SetPlaylist(Playlist(pendingSongs = List(createFileID(1)),
      playedSongs = Nil))
    reset(helper.audioPlayer)
    val nextSource = createPlaylistInfo(2, 100, 200)

    helper send SetPlaylist(Playlist(pendingSongs = List(nextSource.sourceID), playedSongs = Nil),
      positionOffset = nextSource.skip, timeOffset = nextSource.skipTime)
    val io = Mockito.inOrder(helper.audioPlayer)
    io.verify(helper.audioPlayer).reset()
    io.verify(helper.audioPlayer).addToPlaylist(nextSource)

  it should "reset the audio player when setting a new playlist, and played songs exist" in:
    val helper = new ControllerTestHelper
    helper.send(SetPlaylist(Playlist(pendingSongs = List(createFileID(1)),
      playedSongs = Nil), closePlaylist = false))
      .send(AudioSourceFinishedEvent(createAudioSource(1)))
    reset(helper.audioPlayer)
    val nextSource = createPlaylistInfo(2)

    helper send SetPlaylist(Playlist(pendingSongs = List(nextSource.sourceID), playedSongs = Nil))
    val io = Mockito.inOrder(helper.audioPlayer)
    io.verify(helper.audioPlayer).reset()
    io.verify(helper.audioPlayer).addToPlaylist(nextSource)

  it should "not reset the audio player if the current playlist is not activated" in:
    val helper = new ControllerTestHelper

    helper.send(AppendPlaylist(List(createFileID(1)), activate = false))
      .send(SetPlaylist(Playlist(pendingSongs = List(createFileID(2)), playedSongs = Nil)))
    verify(helper.audioPlayer, never()).reset()

  it should "process a start playback command" in:
    val delay = 3.seconds
    val helper = new ControllerTestHelper

    helper send StartAudioPlayback(delay)
    verify(helper.audioPlayer).startPlayback(delay)
    helper.lastState should be(AudioPlayerState(playlist = Playlist(Nil, Nil),
      playbackActive = true, playlistClosed = false, playlistActivated = true,
      playlistSeqNo = PlaylistService.SeqNoInitial))

  it should "start playback only if it is not yet active" in:
    val helper = new ControllerTestHelper
    helper send StartAudioPlayback()
    val lastEvent = helper.lastStateEvent

    helper send StartAudioPlayback()
    verify(helper.audioPlayer).startPlayback(PlayerControl.NoDelay)
    helper.lastStateEvent should be(lastEvent)

  it should "start playback after a reset if it was active" in:
    val helper = new ControllerTestHelper
    helper.send(SetPlaylist(Playlist(pendingSongs = List(createFileID(1)),
      playedSongs = Nil)))
      .send(StartAudioPlayback())
    reset(helper.audioPlayer)

    helper send SetPlaylist(Playlist(pendingSongs = List(createFileID(2)), playedSongs = Nil))
    verify(helper.audioPlayer).startPlayback()

  it should "not start playback after a reset if it was inactive" in:
    val helper = new ControllerTestHelper
    helper send SetPlaylist(Playlist(pendingSongs = List(createFileID(1)), playedSongs = Nil))

    helper send SetPlaylist(Playlist(pendingSongs = List(createFileID(2)), playedSongs = Nil))
    verify(helper.audioPlayer, never()).startPlayback()

  it should "process a stop playback command" in:
    val delay = 1.second
    val helper = new ControllerTestHelper

    helper.send(StartAudioPlayback())
      .send(StopAudioPlayback(delay))
    verify(helper.audioPlayer).stopPlayback(delay)
    helper.lastState.playbackActive shouldBe false

  it should "stop playback only if it is active" in:
    val helper = new ControllerTestHelper
    val initEvent = helper.lastStateEvent

    helper send StopAudioPlayback()
    verifyNoInteractions(helper.audioPlayer)
    helper.lastStateEvent should be(initEvent)

  it should "process a skip current source command" in:
    val helper = new ControllerTestHelper

    helper.send(SetPlaylist(Playlist(pendingSongs = List(createFileID(1),
      createFileID(2)), playedSongs = Nil)))
      .send(StartAudioPlayback())
      .send(SkipCurrentSource)
    verify(helper.audioPlayer).skipCurrentSource()

  it should "skip the current source only if playback is active" in:
    val helper = new ControllerTestHelper
    val initEvent = helper.lastStateEvent

    helper send SkipCurrentSource
    verifyNoInteractions(helper.audioPlayer)
    helper.lastStateEvent should be(initEvent)

  it should "allow appending songs to the playlist" in:
    val firstSong = createPlaylistInfo(1)
    val appendSongs = List(createPlaylistInfo(2), createPlaylistInfo(3))
    val allSongs = firstSong :: appendSongs
    val helper = new ControllerTestHelper

    helper.send(SetPlaylist(Playlist(playedSongs = List(createFileID(20)),
      pendingSongs = List(firstSong.sourceID)), closePlaylist = false))
      .send(AppendPlaylist(extractIDs(appendSongs)))
    val state = checkEventTime(helper.lastStateEvent).state
    state.playlist.pendingSongs should be(extractIDs(allSongs))
    state.playlistSeqNo should be > 1
    state.playlistActivated shouldBe true
    val io = Mockito.inOrder(helper.audioPlayer)
    allSongs foreach (s => io.verify(helper.audioPlayer).addToPlaylist(s))
    verifyNoMoreInteractions(helper.audioPlayer)

  it should "allow appending songs and closing the playlist" in:
    val appendSongs = List(createPlaylistInfo(1), createPlaylistInfo(2))
    val helper = new ControllerTestHelper

    helper send AppendPlaylist(extractIDs(appendSongs), closePlaylist = true)
    helper.lastState.playlist.pendingSongs should be(extractIDs(appendSongs))
    helper.lastState.playlistActivated shouldBe true
    val io = Mockito.inOrder(helper.audioPlayer)
    appendSongs foreach (s => io.verify(helper.audioPlayer).addToPlaylist(s))
    io.verify(helper.audioPlayer).closePlaylist()

  it should "not append songs to the playlist if it is already closed" in:
    val helper = new ControllerTestHelper
    helper send SetPlaylist(Playlist(playedSongs = Nil,
      pendingSongs = List(createFileID(1), createFileID(2))))
    val event = helper.lastStateEvent

    helper send AppendPlaylist(songs = List(createFileID(3)))
    verify(helper.audioPlayer, never()).addToPlaylist(createPlaylistInfo(3))
    helper.lastStateEvent should be(event)

  it should "allow appending songs, but not activating the playlist" in:
    val appendSongs = List(createPlaylistInfo(1), createPlaylistInfo(2))
    val helper = new ControllerTestHelper

    helper send AppendPlaylist(extractIDs(appendSongs), activate = false)
    helper.lastState.playlist.pendingSongs should be(extractIDs(appendSongs))
    helper.lastState.playlistActivated shouldBe false
    verify(helper.audioPlayer, never()).addToPlaylist(any())

  it should "ignore the activate flag when appending songs and the playlist is to be closed" in:
    val appendSongs = List(createPlaylistInfo(1), createPlaylistInfo(2))
    val helper = new ControllerTestHelper

    helper send AppendPlaylist(extractIDs(appendSongs), closePlaylist = true, activate = false)
    helper.lastState.playlistActivated shouldBe true
    val io = Mockito.inOrder(helper.audioPlayer)
    appendSongs foreach (s => io.verify(helper.audioPlayer).addToPlaylist(s))
    io.verify(helper.audioPlayer).closePlaylist()

  it should "ignore the activate flag when appending songs and the playlist is activated" in:
    val song1 = createPlaylistInfo(1)
    val song2 = createPlaylistInfo(2)
    val helper = new ControllerTestHelper
    helper send AppendPlaylist(List(song1.sourceID))

    helper send AppendPlaylist(List(song2.sourceID), activate = false)
    helper.lastState.playlistActivated shouldBe true
    verify(helper.audioPlayer).addToPlaylist(song2)

  it should "pass all songs to the engine when the playlist is activated" in:
    val firstSongs = List(createPlaylistInfo(1), createPlaylistInfo(2))
    val nextSongs = List(createPlaylistInfo(3), createPlaylistInfo(4))
    val helper = new ControllerTestHelper
    helper send AppendPlaylist(extractIDs(firstSongs), activate = false)

    helper send AppendPlaylist(extractIDs(nextSongs))
    helper.lastState.playlistActivated shouldBe true
    val io = Mockito.inOrder(helper.audioPlayer)
    (firstSongs ++ nextSongs) foreach (s => io.verify(helper.audioPlayer).addToPlaylist(s))

  it should "pass all songs to the engine when playback starts" in:
    val appendSongs = List(createPlaylistInfo(1), createPlaylistInfo(2))
    val helper = new ControllerTestHelper
    helper send AppendPlaylist(extractIDs(appendSongs), activate = false)

    helper send StartAudioPlayback()
    appendSongs foreach (s => verify(helper.audioPlayer).addToPlaylist(s))
    helper.lastState.playlistActivated shouldBe true

  it should "move to the next song on receiving a source finished event" in:
    val playedSong = createFileID(1)
    val pending = List(createFileID(2), createFileID(3), createFileID(4))
    val helper = new ControllerTestHelper

    helper.send(SetPlaylist(Playlist(playedSongs = List(playedSong), pendingSongs = pending)))
      .send(StartAudioPlayback())
      .send(AudioSourceFinishedEvent(createAudioSource(2)))
    val state = checkEventTime(helper.lastStateEvent).state
    state.playbackActive shouldBe true
    state.playlist.playedSongs should be(List(pending.head, playedSong))
    state.playlist.pendingSongs should be(pending.tail)

  it should "ignore a source finished event for an unexpected source" in:
    val helper = new ControllerTestHelper
    helper.send(StartAudioPlayback())
      .send(SetPlaylist(Playlist(playedSongs = Nil, pendingSongs = List(createFileID(1)))))
    val event = helper.lastStateEvent

    helper send AudioSourceFinishedEvent(createAudioSource(2))
    helper.lastStateEvent should be(event)

  it should "reset the playback flag at the end of the playlist" in:
    val helper = new ControllerTestHelper

    helper.send(StartAudioPlayback())
      .send(SetPlaylist(Playlist(playedSongs = Nil, pendingSongs = List(createFileID(1)))))
      .send(AudioSourceFinishedEvent(createAudioSource(1)))
    helper.lastState.playbackActive shouldBe false

  it should "only reset the playback flag at the end of a closed playlist" in:
    val helper = new ControllerTestHelper

    helper.send(AppendPlaylist(List(createFileID(1))))
      .send(StartAudioPlayback())
      .send(AudioSourceFinishedEvent(createAudioSource(1)))
    helper.lastState.playbackActive shouldBe true

  it should "ignore a source finished event before the last reset" in:
    val helper = new ControllerTestHelper
    helper.send(AppendPlaylist(List(createFileID(1), createFileID(2))))
      .send(StartAudioPlayback())
      .send(SetPlaylist(Playlist(playedSongs = Nil, pendingSongs = List(createFileID(3)))))
    val event = helper.lastStateEvent

    helper send AudioSourceFinishedEvent(createAudioSource(3),
      time = LocalDateTime.now().minusMinutes(1))
    helper.lastStateEvent should be(event)

  it should "create a correct un-registration object for a registration" in:
    val id = ComponentID()
    val reg = AudioPlayerStateChangeRegistration(id, null)

    val unReg = reg.unRegistration
    unReg should be(AudioPlayerStateChangeUnregistration(id))

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class ControllerTestHelper extends Identifiable:
    /** The mock for the audio player. */
    val audioPlayer: AudioPlayer = mock[AudioPlayer]

    /** The test message bus. */
    private val messageBus = new MessageBusTestImpl

    /** Stores the latest change event received from the test controller. */
    private val lastEvent = new AtomicReference[AudioPlayerStateChangedEvent]

    /** The test controller instance. */
    private val controller = createTestController()

    /**
      * Passes the specified message to the test controller. This simulates
      * a message publication via the message bus.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): ControllerTestHelper =
      controller receive msg
      this

    /**
      * Returns the last state change event received by the test consumer.
      * Fails if no event was received.
      *
      * @return the last state change event
      */
    def lastStateEvent: AudioPlayerStateChangedEvent =
      val stateEvent = lastEvent.get()
      stateEvent should not be null
      stateEvent

    /**
      * Returns the last state for which an event was received.
      *
      * @return the last state of the audio player
      */
    def lastState: AudioPlayerState =
      lastStateEvent.state

    /**
      * Expects a progress event to be published on the message bus with the
      * provided parameters.
      *
      * @param songIdx the index of the song the event is about
      * @param posOfs  the position offset
      * @param timeOfs the time offset
      * @return this test helper
      */
    def expectProgressEvent(songIdx: Int, posOfs: Long, timeOfs: Long): ControllerTestHelper =
      val event = messageBus.expectMessageType[PlaybackProgressEvent]
      event.currentSource.uri should be(songUri(songIdx))
      event.currentSource.isLengthUnknown shouldBe true
      event.currentSource.skip should be(posOfs)
      event.currentSource.skipTime should be(timeOfs)
      event.bytesProcessed should be(posOfs)
      event.playbackTime.toSeconds should be(timeOfs)
      checkCurrentTime(event.time)
      this

    /**
      * Expects that no progress event is published on the message bus.
      *
      * @return this test helper
      */
    def expectNoProgressEvent(): ControllerTestHelper =
      messageBus.expectNoMessage(100.millis)
      this

    /**
      * Creates a test controller instance and initializes the test consumer.
      *
      * @return the test controller
      */
    private def createTestController(): AudioPlayerController =
      val ctrl = new AudioPlayerController(audioPlayer, messageBus)
      val func: ConsumerFunction[AudioPlayerStateChangedEvent] = e => lastEvent.set(e)
      ctrl receive AudioPlayerStateChangeRegistration(componentID, func)
      ctrl

