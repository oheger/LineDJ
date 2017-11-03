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

package de.oliver_heger.linedj.platform.audio.impl

import java.time.{Duration, LocalDateTime}
import java.util.concurrent.atomic.AtomicReference

import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerFunction
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourceFinishedEvent, AudioSourcePlaylistInfo}
import de.oliver_heger.linedj.player.engine.facade.{AudioPlayer, PlayerControl}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

object AudioPlayerControllerSpec {
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
    * Creates a playlist info object based on the given index.
    *
    * @param idx the index
    * @return the ''AudioSourcePlaylistInfo'' for this index
    */
  private def createPlaylistInfo(idx: Int): AudioSourcePlaylistInfo =
    AudioSourcePlaylistInfo(MediaFileID(TestMedium, songUri(idx)), idx, idx)

  /**
    * Creates a test audio source object based on the given index.
    *
    * @param idx the index
    * @return the ''AudioSource'' for this index
    */
  private def createAudioSource(idx: Int): AudioSource =
    AudioSource(songUri(idx), 4000 + idx * 100, idx, idx)
}

/**
  * Test class for ''AudioPlayerController''.
  */
class AudioPlayerControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import AudioPlayerControllerSpec._

  /**
    * Checks the time stamp of the specified event. It is checked whether the
    * event's time is close to the current time.
    *
    * @param event the event to be checked
    * @return the checked event
    */
  private def checkEventTime(event: AudioPlayerStateChangedEvent):
  AudioPlayerStateChangedEvent = {
    val timeDelta = Duration.between(event.time, LocalDateTime.now())
    timeDelta.getSeconds should be < 3L
    event
  }

  "An AudioPlayerController" should "have an initial state" in {
    val helper = new ControllerTestHelper

    val state = helper.lastState
    state.playbackActive shouldBe false
    state.playlistClosed shouldBe false
    state.playedSongs shouldBe 'empty
    state.pendingSongs shouldBe 'empty
  }

  it should "process a SetPlaylist message with the close flag set to true" in {
    val playedSongs = List(createPlaylistInfo(2), createPlaylistInfo(1))
    val pendingSongs = List(createPlaylistInfo(3), createPlaylistInfo(4))
    val helper = new ControllerTestHelper

    helper send SetPlaylist(playedSongs = playedSongs, pendingSongs = pendingSongs)
    val state = checkEventTime(helper.lastStateEvent).state
    state.playbackActive shouldBe false
    state.playlistClosed shouldBe true
    state.playedSongs should be(playedSongs)
    state.pendingSongs should be(pendingSongs)

    val io = Mockito.inOrder(helper.audioPlayer)
    pendingSongs foreach (s => io.verify(helper.audioPlayer).addToPlaylist(s))
    io.verify(helper.audioPlayer).closePlaylist()
    verifyNoMoreInteractions(helper.audioPlayer)
  }

  it should "process a SetPlaylist message with the close flag set to false" in {
    val playedSongs = List(createPlaylistInfo(2), createPlaylistInfo(1))
    val pendingSongs = List(createPlaylistInfo(3), createPlaylistInfo(4))
    val helper = new ControllerTestHelper

    helper send SetPlaylist(playedSongs = playedSongs, pendingSongs = pendingSongs,
      closePlaylist = false)
    val state = checkEventTime(helper.lastStateEvent).state
    state.playbackActive shouldBe false
    state.playlistClosed shouldBe false
    state.playedSongs should be(playedSongs)
    state.pendingSongs should be(pendingSongs)

    pendingSongs foreach (s => verify(helper.audioPlayer).addToPlaylist(s))
    verifyNoMoreInteractions(helper.audioPlayer)
  }

  it should "reset the audio player when setting a new playlist and songs are pending" in {
    val helper = new ControllerTestHelper
    helper send SetPlaylist(pendingSongs = List(createPlaylistInfo(1)), playedSongs = Nil)
    reset(helper.audioPlayer)
    val nextSource = createPlaylistInfo(2)

    helper send SetPlaylist(pendingSongs = List(nextSource), playedSongs = Nil)
    val io = Mockito.inOrder(helper.audioPlayer)
    io.verify(helper.audioPlayer).reset()
    io.verify(helper.audioPlayer).addToPlaylist(nextSource)
  }

  it should "reset the audio player when setting a new playlist and played songs exist" in {
    val helper = new ControllerTestHelper
    helper send SetPlaylist(playedSongs = List(createPlaylistInfo(1)), pendingSongs = Nil,
      closePlaylist = false)
    reset(helper.audioPlayer)
    val nextSource = createPlaylistInfo(2)

    helper send SetPlaylist(pendingSongs = List(nextSource), playedSongs = Nil)
    val io = Mockito.inOrder(helper.audioPlayer)
    io.verify(helper.audioPlayer).reset()
    io.verify(helper.audioPlayer).addToPlaylist(nextSource)
  }

  it should "process a start playback command" in {
    val delay = 3.seconds
    val helper = new ControllerTestHelper

    helper send StartAudioPlayback(delay)
    verify(helper.audioPlayer).startPlayback(delay)
    helper.lastState should be(AudioPlayerState(pendingSongs = Nil, playedSongs = Nil,
      playbackActive = true, playlistClosed = false))
  }

  it should "start playback only if it is not yet active" in {
    val helper = new ControllerTestHelper
    helper send StartAudioPlayback()
    val lastEvent = helper.lastStateEvent

    helper send StartAudioPlayback()
    verify(helper.audioPlayer).startPlayback(PlayerControl.NoDelay)
    helper.lastStateEvent should be(lastEvent)
  }

  it should "process a stop playback command" in {
    val delay = 1.second
    val helper = new ControllerTestHelper
    val initState = helper.lastState

    helper.send(StartAudioPlayback())
      .send(StopAudioPlayback(delay))
    verify(helper.audioPlayer).stopPlayback(delay)
    helper.lastState should be(initState)
  }

  it should "stop playback only if it is active" in {
    val helper = new ControllerTestHelper
    val initEvent = helper.lastStateEvent

    helper send StopAudioPlayback()
    verifyZeroInteractions(helper.audioPlayer)
    helper.lastStateEvent should be(initEvent)
  }

  it should "process a skip current source command" in {
    val helper = new ControllerTestHelper

    helper.send(SetPlaylist(pendingSongs = List(createPlaylistInfo(1), createPlaylistInfo(2)),
      playedSongs = Nil))
      .send(StartAudioPlayback())
      .send(SkipCurrentSource)
    verify(helper.audioPlayer).skipCurrentSource()
  }

  it should "skip the current source only if playback is active" in {
    val helper = new ControllerTestHelper
    val initEvent = helper.lastStateEvent

    helper send SkipCurrentSource
    verifyZeroInteractions(helper.audioPlayer)
    helper.lastStateEvent should be(initEvent)
  }

  it should "allow appending songs to the playlist" in {
    val firstSong = createPlaylistInfo(1)
    val appendSongs = List(createPlaylistInfo(2), createPlaylistInfo(3))
    val allSongs = firstSong :: appendSongs
    val helper = new ControllerTestHelper

    helper.send(SetPlaylist(playedSongs = List(createPlaylistInfo(20)),
      pendingSongs = List(firstSong), closePlaylist = false))
      .send(AppendPlaylist(appendSongs))
    val state = checkEventTime(helper.lastStateEvent).state
    state.pendingSongs should be(allSongs)
    val io = Mockito.inOrder(helper.audioPlayer)
    allSongs foreach (s => io.verify(helper.audioPlayer).addToPlaylist(s))
    verifyNoMoreInteractions(helper.audioPlayer)
  }

  it should "allow appending songs and closing the playlist" in {
    val appendSongs = List(createPlaylistInfo(1), createPlaylistInfo(2))
    val helper = new ControllerTestHelper

    helper send AppendPlaylist(appendSongs, closePlaylist = true)
    helper.lastState.pendingSongs should be(appendSongs)
    val io = Mockito.inOrder(helper.audioPlayer)
    appendSongs foreach (s => io.verify(helper.audioPlayer).addToPlaylist(s))
    io.verify(helper.audioPlayer).closePlaylist()
  }

  it should "not append songs to the playlist if it is already closed" in {
    val helper = new ControllerTestHelper
    helper send SetPlaylist(playedSongs = Nil,
      pendingSongs = List(createPlaylistInfo(1), createPlaylistInfo(2)))
    val event = helper.lastStateEvent

    helper send AppendPlaylist(songs = List(createPlaylistInfo(3)))
    verify(helper.audioPlayer, never()).addToPlaylist(createPlaylistInfo(3))
    helper.lastStateEvent should be(event)
  }

  it should "move to the next song on receiving a source finished event" in {
    val playedSong = createPlaylistInfo(1)
    val pending = List(createPlaylistInfo(2), createPlaylistInfo(3), createPlaylistInfo(4))
    val helper = new ControllerTestHelper

    helper.send(SetPlaylist(playedSongs = List(playedSong), pendingSongs = pending))
      .send(StartAudioPlayback())
      .send(AudioSourceFinishedEvent(createAudioSource(2)))
    val state = checkEventTime(helper.lastStateEvent).state
    state.playbackActive shouldBe true
    state.playedSongs should be(List(pending.head, playedSong))
    state.pendingSongs should be(pending.tail)
  }

  it should "ignore a source finished event for an unexpected source" in {
    val helper = new ControllerTestHelper
    helper.send(StartAudioPlayback())
      .send(SetPlaylist(playedSongs = Nil, pendingSongs = List(createPlaylistInfo(1))))
    val event = helper.lastStateEvent

    helper send AudioSourceFinishedEvent(createAudioSource(2))
    helper.lastStateEvent should be(event)
  }

  it should "reset the playback flag at the end of the playlist" in {
    val helper = new ControllerTestHelper

    helper.send(StartAudioPlayback())
      .send(SetPlaylist(playedSongs = Nil, pendingSongs = List(createPlaylistInfo(1))))
      .send(AudioSourceFinishedEvent(createAudioSource(1)))
    helper.lastState.playbackActive shouldBe false
  }

  it should "only reset the playback flag at the end of a closed playlist" in {
    val helper = new ControllerTestHelper

    helper.send(AppendPlaylist(List(createPlaylistInfo(1))))
      .send(StartAudioPlayback())
      .send(AudioSourceFinishedEvent(createAudioSource(1)))
    helper.lastState.playbackActive shouldBe true
  }

  it should "ignore a source finished event before the last reset" in {
    val helper = new ControllerTestHelper
    helper.send(AppendPlaylist(List(createPlaylistInfo(1), createPlaylistInfo(2))))
      .send(StartAudioPlayback())
      .send(SetPlaylist(playedSongs = Nil, pendingSongs = List(createPlaylistInfo(3))))
    val event = helper.lastStateEvent

    helper send AudioSourceFinishedEvent(createAudioSource(3),
      time = LocalDateTime.now().minusMinutes(1))
    helper.lastStateEvent should be(event)
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class ControllerTestHelper extends Identifiable {
    /** The mock for the audio player. */
    val audioPlayer: AudioPlayer = mock[AudioPlayer]

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
    def send(msg: Any): ControllerTestHelper = {
      controller receive msg
      this
    }

    /**
      * Returns the last state change event received by the test consumer.
      * Fails if no event was received.
      *
      * @return the last state change event
      */
    def lastStateEvent: AudioPlayerStateChangedEvent = {
      val stateEvent = lastEvent.get()
      stateEvent should not be null
      stateEvent
    }

    /**
      * Returns the last state for which an event was received.
      *
      * @return the last state of the audio player
      */
    def lastState: AudioPlayerState =
      lastStateEvent.state

    /**
      * Creates a test controller instance and initializes the test consumer.
      *
      * @return the test controller
      */
    private def createTestController(): AudioPlayerController = {
      val ctrl = new AudioPlayerController(audioPlayer)
      val func: ConsumerFunction[AudioPlayerStateChangedEvent] = e => lastEvent.set(e)
      ctrl receive AudioPlayerStateChangeRegistration(componentID, func)
      ctrl
    }
  }

}
