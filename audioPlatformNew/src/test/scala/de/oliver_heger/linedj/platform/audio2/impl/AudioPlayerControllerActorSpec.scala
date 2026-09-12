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

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.audio2.impl.AudioPlayerActor.AudioPlayerCommand
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerCommands, AudioPlayerState, PlaybackProgress}
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.player.engine.stream.{LineWriterStage, PausePlaybackStage}
import org.apache.commons.configuration2.BaseHierarchicalConfiguration
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.KillSwitch
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.compiletime.uninitialized
import scala.concurrent.duration.*

object AudioPlayerControllerActorSpec:
  /**
    * A data class that holds information about the creation of an audio
    * player actor. This is used by the test class to keep track on all created
    * actor instances, obtain test probes for them and validate their
    * configuration.
    *
    * @param probeAudioPlayerActor the probe representing the player actor
    * @param audioPlayerConfig     the config used to create the actor
    */
  private case class AudioPlayerActorCreation(probeAudioPlayerActor: TestProbe[AudioPlayerActor.AudioPlayerCommand],
                                              audioPlayerConfig: AudioPlayerActor.Config)

  /**
    * Generates an ID for a test song based on the given index.
    *
    * @param idx the index of the test song
    * @return the ID for this test song
    */
  private def songID(idx: Int): String = s"song_$idx.mp3"

  /**
    * Creates a playlist with the given range of song IDs for the pending and
    * already played songs.
    *
    * @param pending the number of pending songs
    * @param played  the number of already played songs (in reverse order)
    * @return the test playlist
    */
  private def createPlaylist(pending: Int, played: Int): Playlist =
    val playedStart = played + 1
    Playlist(
      pendingSongs = (playedStart until playedStart + pending).map(songID).toList,
      playedSongs = (0 until played).reverse.map(songID).toList
    )
end AudioPlayerControllerActorSpec

/**
  * Test class for [[AudioPlayerControllerActor]].
  */
class AudioPlayerControllerActorSpec extends ScalaTestWithActorTestKit, AnyFlatSpecLike, Matchers, MockitoSugar,
  Eventually:

  import AudioPlayerControllerActorSpec.*

  "An AudioPlayerController" should "cancel the message bus registration on shutdown" in :
    val helper = new ControllerTestHelper

    helper.stopControllerActor()
      .verifyControllerActorStopped()
      .verifyMessageBusUnregistration()

  it should "publish its initial state when it starts up" in :
    val helper = new ControllerTestHelper

    helper.initialState should be(AudioPlayerState.Initial)

  it should "create a correct config for the audio player actor" in :
    val playlist = createPlaylist(pending = 1, played = 0)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist))
      .expectAudioPlayerCreation()

    val config = helper.fetchAudioPlayerConfig()
    config.optBufferFunc shouldBe empty

  it should "process a SetPlaylist command with the close flag set to true" in :
    val playlist = createPlaylist(pending = 2, played = 2)
    val PositionOffset = 50123L
    val helper = new ControllerTestHelper

    helper.sendCommand(
        AudioPlayerCommands.SetPlaylist(playlist, positionOffset = PositionOffset, timeOffset = 100.millis),
        expectPlayerCreation = true
      ).expectAudioPlayerCommand(
        AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs.head, optOffset = Some(PositionOffset))
      ).expectAudioPlayerCommand(
        AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs(1), optOffset = None)
      ).expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playlistSeqNo should not be PlaylistService.SeqNoInitial
        state.playbackActive shouldBe false
        state.playlistClosed shouldBe true
        state.playlistActivated shouldBe true

  it should "process a SetPlaylist command with the close flag not set" in :
    val playlist = createPlaylist(pending = 2, played = 0)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist, closePlaylist = false), expectPlayerCreation = true)
      .expectAudioPlayerCommand(
        AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs.head, optOffset = None)
      ).expectAudioPlayerCommand(
        AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs(1), optOffset = None)
      ).expectNoAudioPlayerCommand()
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playlistSeqNo should not be PlaylistService.SeqNoInitial
        state.playbackActive shouldBe false
        state.playlistClosed shouldBe false
        state.playlistActivated shouldBe true

  it should "process a SetPlaylist command with a playlist without pending songs" in :
    val playlist = createPlaylist(pending = 0, played = 2)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playlistSeqNo should not be PlaylistService.SeqNoInitial
        state.playbackActive shouldBe false
        state.playlistClosed shouldBe true
        state.playlistActivated shouldBe false

  it should "not change the playlist sequence number if an equivalent playlist is set" in :
    val firstPlaylist = createPlaylist(pending = 2, played = 1)
    val equivalentPlaylist = PlaylistServiceImpl.moveBackwards(firstPlaylist).get
    var firstSeqNo = PlaylistService.SeqNoInitial
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(firstPlaylist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(3)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        firstSeqNo = state.playlistSeqNo
        firstSeqNo should not be PlaylistService.SeqNoInitial

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(equivalentPlaylist), expectPlayerCreation = true)
      .expectAudioPlayerState: state =>
        state.playlistSeqNo should be(firstSeqNo)
        state.playlist should be(equivalentPlaylist)

  it should "reset the audio player when setting a new playlist, and songs are pending" in :
    val firstPlaylist = Playlist(pendingSongs = List(songID(1)), playedSongs = Nil)
    val secondPlaylist = Playlist(pendingSongs = List(songID(2)), playedSongs = Nil)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(firstPlaylist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(1)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(secondPlaylist))
      .expectAudioPlayerCommand(AudioPlayerCommand.Stop)
      .expectAudioPlayerCreation()
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
    helper.fetchAudioPlayerConfig().initPlaybackState should be(PausePlaybackStage.PlaybackState.PlaybackPaused)

  it should "not reset the audio player if the current playlist is not activated" in :
    val firstPlaylist = Playlist(pendingSongs = Nil, playedSongs = Nil)
    val secondPlaylist = Playlist(pendingSongs = List(songID(1)), playedSongs = Nil)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(firstPlaylist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(secondPlaylist))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(1)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectNoAudioPlayerCommand()

  it should "append songs to an activated playlist" in :
    val firstSongs = List(songID(1), songID(2))
    val appendSongs = List(songID(3), songID(4))
    val playlist = Playlist(pendingSongs = firstSongs, playedSongs = Nil)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist, closePlaylist = false),
        expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(1)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerState: state =>
        state.playlist.pendingSongs should be(firstSongs)
        state.playlistActivated shouldBe true

    helper.sendCommand(AudioPlayerCommands.AppendPlaylist(appendSongs))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(3)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(4)))
      .expectAudioPlayerState: state =>
        state.playlist.pendingSongs should be(firstSongs ++ appendSongs)
        state.playlistSeqNo should be(2)
        state.playlistActivated shouldBe true

  it should "ignore an AppendPlaylist command if the playlist is closed" in :
    val playlist = createPlaylist(pending = 1, played = 0)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs.head))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlistClosed shouldBe true

    helper.sendCommand(AudioPlayerCommands.AppendPlaylist(List(songID(99))))
      .expectNoAudioPlayerCommand()
      .expectNoAudioPlayerState()

  it should "append songs to a playlist which is not activated without passing them to the player" in :
    val appendSongs = List(songID(2), songID(3))
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.AppendPlaylist(appendSongs))
      .expectNoAudioPlayerCommand()
      .expectAudioPlayerState: state =>
        state.playlist.pendingSongs should be(appendSongs)
        state.playlistActivated shouldBe false

  it should "process a start playback command" in :
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.StartAudioPlayback, expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.StartPlayback)
      .expectAudioPlayerState: state =>
        state.playlist should be(Playlist(Nil, Nil))
        state.playlistSeqNo should be(PlaylistService.SeqNoInitial)
        state.playbackActive shouldBe true
        state.playlistClosed shouldBe false
        state.playlistActivated shouldBe true

  it should "start playback only if it is not yet active" in :
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.StartAudioPlayback, expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.StartPlayback)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe true
      .sendCommand(AudioPlayerCommands.StartAudioPlayback)
      .expectNoAudioPlayerCommand()
      .expectNoAudioPlayerState()

  it should "keep the playback state when resetting the audio player" in :
    val firstPlaylist = Playlist(pendingSongs = List(songID(1)), playedSongs = Nil)
    val secondPlaylist = Playlist(pendingSongs = List(songID(2)), playedSongs = Nil)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(firstPlaylist), expectPlayerCreation = true)
      .sendCommand(AudioPlayerCommands.StartAudioPlayback)

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(secondPlaylist))
      .expectAudioPlayerCreation()
    helper.fetchAudioPlayerConfig().initPlaybackState should be(PausePlaybackStage.PlaybackState.PlaybackPossible)

  it should "process a stop playback command" in :
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.StartAudioPlayback, expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.StartPlayback)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe true
      .sendCommand(AudioPlayerCommands.StopAudioPlayback)
      .expectAudioPlayerCommand(AudioPlayerCommand.StopPlayback)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe false

  it should "stop playback only if it is active" in :
    val playlist = createPlaylist(pending = 1, played = 0)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs.head))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe false
      .sendCommand(AudioPlayerCommands.StopAudioPlayback)
      .expectNoAudioPlayerCommand()
      .expectNoAudioPlayerState()

  it should "move to the next song on a media file completed event" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2), songID(3), songID(4)),
      playedSongs = List(songID(1))
    )
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(3)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(4)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playbackActive shouldBe false

    helper.sendCommand(AudioPlayerCommands.StartAudioPlayback)
      .expectAudioPlayerCommand(AudioPlayerCommand.StartPlayback)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe true

    helper.fetchAudioPlayerConfig().playlistCallback(AudioPlayerActor.PlaylistEvent.MediaFileEnded(songID(2)))
    helper.expectAudioPlayerState: state =>
      state.playbackActive shouldBe true
      state.playlist.pendingSongs should be(List(songID(3), songID(4)))
      state.playlist.playedSongs should be(List(songID(2), songID(1)))

  it should "not publish a playback state update if the completed media file is not the current song" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2), songID(3), songID(4)),
      playedSongs = List(songID(1))
    )
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(3)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(4)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playbackActive shouldBe false

    helper.fetchAudioPlayerConfig().playlistCallback(AudioPlayerActor.PlaylistEvent.MediaFileEnded(songID(3)))
    helper.expectNoAudioPlayerState()

  it should "stop playback when the last song of the playlist is completed" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2)),
      playedSongs = List(songID(1))
    )
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playlistClosed shouldBe true
        state.playbackActive shouldBe false

    helper.sendCommand(AudioPlayerCommands.StartAudioPlayback)
      .expectAudioPlayerCommand(AudioPlayerCommand.StartPlayback)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe true

    helper.fetchAudioPlayerConfig().playlistCallback(AudioPlayerActor.PlaylistEvent.MediaFileEnded(songID(2)))
    helper.expectAudioPlayerState: state =>
      state.playbackActive shouldBe false
      state.playlistClosed shouldBe true
      state.playlist.pendingSongs shouldBe empty
      state.playlist.playedSongs should be(List(songID(2), songID(1)))

  it should "not reset the playback flag if the last song of an open playlist is completed" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2)),
      playedSongs = List(songID(1))
    )
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist, closePlaylist = false),
        expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerState: state =>
        state.playlistClosed shouldBe false
        state.playbackActive shouldBe false

    helper.sendCommand(AudioPlayerCommands.StartAudioPlayback)
      .expectAudioPlayerCommand(AudioPlayerCommand.StartPlayback)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe true

    helper.fetchAudioPlayerConfig().playlistCallback(AudioPlayerActor.PlaylistEvent.MediaFileEnded(songID(2)))
    helper.expectAudioPlayerState: state =>
      state.playbackActive shouldBe true
      state.playlistClosed shouldBe false
      state.playlist.pendingSongs shouldBe empty
      state.playlist.playedSongs should be(List(songID(2), songID(1)))

  it should "move to the next song on a media file failed event" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2), songID(3), songID(4)),
      playedSongs = List(songID(1))
    )
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(3)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(4)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playbackActive shouldBe false

    helper.sendCommand(AudioPlayerCommands.StartAudioPlayback)
      .expectAudioPlayerCommand(AudioPlayerCommand.StartPlayback)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe true

    helper.fetchAudioPlayerConfig().playlistCallback(
      AudioPlayerActor.PlaylistEvent.MediaFileFailed(songID(2), new Exception("Playback failed"))
    )
    helper.expectAudioPlayerState: state =>
      state.playbackActive shouldBe true
      state.playlist.pendingSongs should be(List(songID(3), songID(4)))
      state.playlist.playedSongs should be(List(songID(2), songID(1)))

  it should "not publish a playback state update if the failed media file is not the current song" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2), songID(3), songID(4)),
      playedSongs = List(songID(1))
    )
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(3)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(4)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playbackActive shouldBe false

    helper.fetchAudioPlayerConfig().playlistCallback(
      AudioPlayerActor.PlaylistEvent.MediaFileFailed(songID(3), new Exception("Playback failed"))
    )
    helper.expectNoAudioPlayerState()

  it should "stop playback when the last song of the playlist fails" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2)),
      playedSongs = List(songID(1))
    )
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)
        state.playlistClosed shouldBe true
        state.playbackActive shouldBe false

    helper.sendCommand(AudioPlayerCommands.StartAudioPlayback)
      .expectAudioPlayerCommand(AudioPlayerCommand.StartPlayback)
      .expectAudioPlayerState: state =>
        state.playbackActive shouldBe true

    helper.fetchAudioPlayerConfig().playlistCallback(
      AudioPlayerActor.PlaylistEvent.MediaFileFailed(songID(2), new Exception("Playback failed"))
    )
    helper.expectAudioPlayerState: state =>
      state.playbackActive shouldBe false
      state.playlistClosed shouldBe true
      state.playlist.pendingSongs shouldBe empty
      state.playlist.playedSongs should be(List(songID(2), songID(1)))

  it should "skip the current media file" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2), songID(3), songID(4)),
      playedSongs = List(songID(1))
    )
    val killSwitch = mock[KillSwitch]
    val helper = new ControllerTestHelper
    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)

    helper.fetchAudioPlayerConfig().playlistCallback(
      AudioPlayerActor.PlaylistEvent.MediaFileStarted(songID(2), killSwitch)
    )
    helper.sendCommand(AudioPlayerCommands.SkipCurrentSource)

    verify(killSwitch, Mockito.timeout(1000)).shutdown()

  it should "reset the kill switch for the current source after it was triggered" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2), songID(3), songID(4)),
      playedSongs = List(songID(1))
    )
    val killSwitch = mock[KillSwitch]
    val helper = new ControllerTestHelper
    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)

    helper.fetchAudioPlayerConfig().playlistCallback(
      AudioPlayerActor.PlaylistEvent.MediaFileStarted(songID(2), killSwitch)
    )
    helper.sendCommand(AudioPlayerCommands.SkipCurrentSource)
      .sendCommand(AudioPlayerCommands.SkipCurrentSource)
    helper.fetchAudioPlayerConfig().playlistCallback(
      AudioPlayerActor.PlaylistEvent.MediaFileEnded(songID(2))
    )
    helper.expectAudioPlayerState: state =>
      state.playlist.pendingSongs.head should be(songID(3))

    verify(killSwitch, times(1)).shutdown()

  it should "ignore a media file started event if it is not for the current song in the playlist" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2), songID(3), songID(4)),
      playedSongs = List(songID(1))
    )
    val killSwitch = mock[KillSwitch]
    val helper = new ControllerTestHelper
    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)

    helper.fetchAudioPlayerConfig().playlistCallback(
      AudioPlayerActor.PlaylistEvent.MediaFileStarted(songID(3), killSwitch)
    )
    helper.sendCommand(AudioPlayerCommands.SkipCurrentSource)
    helper.fetchAudioPlayerConfig().playlistCallback(
      AudioPlayerActor.PlaylistEvent.MediaFileEnded(songID(2))
    )
    helper.expectAudioPlayerState: state =>
      state.playlist.pendingSongs.head should be(songID(3))

    verifyNoInteractions(killSwitch)

  it should "publish playback progress objects for processed audio chunks" in :
    val playlist = createPlaylist(pending = 1, played = 0)
    val chunk1 = LineWriterStage.PlayedAudioChunk(1000, 1.second)
    val chunk2 = LineWriterStage.PlayedAudioChunk(2000, 2.seconds)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs.head))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)

    val config = helper.fetchAudioPlayerConfig()
    config.progressCallback(chunk1)
    helper.expectPlaybackProgress: progress =>
      progress.bytesProcessed should be(chunk1.size)
      progress.playbackTime should be(chunk1.duration)

    config.progressCallback(chunk2)
    helper.expectPlaybackProgress: progress =>
      progress.bytesProcessed should be(chunk1.size + chunk2.size)
      progress.playbackTime should be(chunk1.duration + chunk2.duration)

  it should "aggregate playback progress until the time delta reaches one second" in :
    val playlist = createPlaylist(pending = 1, played = 0)
    val chunk1 = LineWriterStage.PlayedAudioChunk(1000, 500.millis)
    val chunk2 = LineWriterStage.PlayedAudioChunk(2000, 500.millis)
    val chunk3 = LineWriterStage.PlayedAudioChunk(3000, 1.second)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs.head))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)

    val config = helper.fetchAudioPlayerConfig()
    config.progressCallback(chunk1)
    helper.expectPlaybackProgress: progress =>
      progress.bytesProcessed should be(chunk1.size)
      progress.playbackTime should be(chunk1.duration)

    config.progressCallback(chunk2)
    config.progressCallback(chunk3)
    helper.expectPlaybackProgress: progress =>
      progress.bytesProcessed should be(chunk1.size + chunk2.size + chunk3.size)
      progress.playbackTime should be(chunk1.duration + chunk2.duration + chunk3.duration)

  it should "add the configured playback offsets to the playback progress" in :
    val playlist = createPlaylist(pending = 1, played = 0)
    val PositionOffset = 5000L
    val TimeOffset = 1.second
    val chunk = LineWriterStage.PlayedAudioChunk(1000, 250.millis)
    val helper = new ControllerTestHelper

    helper.sendCommand(
        AudioPlayerCommands.SetPlaylist(playlist, positionOffset = PositionOffset, timeOffset = TimeOffset),
        expectPlayerCreation = true
      )
      .expectAudioPlayerCommand(
        AudioPlayerCommand.AppendToPlaylist(playlist.pendingSongs.head, Some(PositionOffset)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)

    helper.fetchAudioPlayerConfig().progressCallback(chunk)
    helper.expectPlaybackProgress: progress =>
      progress.bytesProcessed should be(PositionOffset + chunk.size)
      progress.playbackTime should be(TimeOffset + chunk.duration)

  it should "reset the playback progress counters when the next media file starts" in :
    val playlist = Playlist(
      pendingSongs = List(songID(2), songID(3)),
      playedSongs = List(songID(1))
    )
    val chunk1 = LineWriterStage.PlayedAudioChunk(1000, 250.millis)
    val chunk2 = LineWriterStage.PlayedAudioChunk(1500, 300.millis)
    val helper = new ControllerTestHelper

    helper.sendCommand(AudioPlayerCommands.SetPlaylist(playlist), expectPlayerCreation = true)
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(2)))
      .expectAudioPlayerCommand(AudioPlayerCommand.AppendToPlaylist(songID(3)))
      .expectAudioPlayerCommand(AudioPlayerCommand.ClosePlaylist)
      .expectAudioPlayerState: state =>
        state.playlist should be(playlist)

    val config = helper.fetchAudioPlayerConfig()
    config.progressCallback(chunk1)
    helper.expectPlaybackProgress: progress =>
      progress.bytesProcessed should be(chunk1.size)
      progress.playbackTime should be(chunk1.duration)

    config.playlistCallback(AudioPlayerActor.PlaylistEvent.MediaFileEnded(songID(2)))
    helper.expectAudioPlayerState: state =>
      state.playlist.pendingSongs should be(List(songID(3)))

    config.playlistCallback(AudioPlayerActor.PlaylistEvent.MediaFileStarted(songID(3), mock[KillSwitch]))
    config.progressCallback(chunk2)
    helper.expectPlaybackProgress: progress =>
      progress.bytesProcessed should be(chunk2.size)
      progress.playbackTime should be(chunk2.duration)

  /**
    * A test helper class that manages a controller instance to be tested and
    * its dependencies.
    */
  private class ControllerTestHelper:
    /** The message bus used by the test controller. */
    private val messageBus: MessageBusTestImpl = new MessageBusTestImpl

    /** A queue to record audio player actor creations. */
    private val actorCreationQueue = new LinkedBlockingQueue[AudioPlayerActorCreation]

    /** Stores data about the latest audio player actor. */
    private var audioPlayerCreation: AudioPlayerActorCreation = uninitialized

    /** The mock factory for the audio player actor. */
    private val audioPlayerActorFactory = createAudioPlayerActorFactory()

    /** Mock for the archive service. */
    private val archiveService = mock[ArchiveService]

    /** The simulated platform configuration. */
    private val platformConfig = new BaseHierarchicalConfiguration

    /** Stores the state published by the controller when it starts up. */
    private var initialPlayerState: AudioPlayerState = uninitialized

    /** The controller actor to be tested. */
    private val controllerActor = createController()

    /**
      * Returns the state that was published by the controller when it started
      * up.
      *
      * @return the initial state of the controller
      */
    def initialState: AudioPlayerState = initialPlayerState

    /**
      * Sends the `Stop` command to the actor under test.
      *
      * @return this test helper
      */
    def stopControllerActor(): ControllerTestHelper =
      controllerActor ! AudioPlayerControllerActor.Stop
      this

    /**
      * Sends a specific command to the controller via the message bus.
      * Optionally, the function can be instructed to expect the creation of
      * an audio player child actor.
      *
      * @param command              the command to send
      * @param expectPlayerCreation flag to expect an actor creation
      * @return this test helper
      */
    def sendCommand(command: AudioPlayerCommands, expectPlayerCreation: Boolean = false): ControllerTestHelper =
      // The registration on the message bus happens asynchronously; so make sure that it is done.
      eventually(Timeout(Span(3, Seconds)), Interval(Span(50, Millis))):
        messageBus.currentListeners should not be empty
      messageBus.publishDirectly(command)
      if expectPlayerCreation then
        expectAudioPlayerCreation()
      this

    /**
      * Returns the configuration used for the creation of the latest audio
      * player actor.
      *
      * @return the configuration for the audio player actor
      */
    def fetchAudioPlayerConfig(): AudioPlayerActor.Config =
      audioPlayerCreation should not be null
      audioPlayerCreation.audioPlayerConfig

    /**
      * Expects that an audio player actor instance has been created.
      * Information about the new instance is stored in a field of this test
      * helper, so that it can be validated, and that the messages sent to the
      * actor can be tested.
      *
      * @return this test helper
      */
    def expectAudioPlayerCreation(): ControllerTestHelper =
      val newCreation = actorCreationQueue.poll(3, TimeUnit.SECONDS)
      newCreation should not be null
      audioPlayerCreation = newCreation
      this

    /**
      * Verifies that the controller has unregistered itself from the message 
      * bus.
      *
      * @return this test helper
      */
    def verifyMessageBusUnregistration(): ControllerTestHelper =
      messageBus.currentListeners shouldBe empty
      this

    /**
      * Tests that the test actor instance has been stopped.
      *
      * @return this test helper
      */
    def verifyControllerActorStopped(): ControllerTestHelper =
      val watcherProbe = testKit.createDeadLetterProbe()
      watcherProbe.expectTerminated(controllerActor)
      this

    /**
      * Expects that the given command has been sent to the audio player actor
      * instance.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectAudioPlayerCommand(command: AudioPlayerActor.AudioPlayerCommand): ControllerTestHelper =
      audioPlayerCreation.probeAudioPlayerActor.expectMessage(command)
      this

    /**
      * Checks that no message was sent to the audio player actor for a certain
      * period. This function also works if no audio player actor has been
      * created.
      *
      * @return this test helper
      */
    def expectNoAudioPlayerCommand(): ControllerTestHelper =
      if audioPlayerCreation == null then
        actorCreationQueue.poll(100, TimeUnit.MILLISECONDS) should be(null)
      else
        audioPlayerCreation.probeAudioPlayerActor.expectNoMessage(200.millis)
      this

    /**
      * Expects that an [[AudioPlayerState]] message has been published on the
      * message bus. The message is fetched from the bus and passed to the
      * provided check function.
      *
      * @param check the function verifying the properties of the state message
      * @return this test helper
      */
    def expectAudioPlayerState(check: AudioPlayerState => Unit): ControllerTestHelper =
      val state = messageBus.expectMessageType[AudioPlayerState]
      check(state)
      this

    /**
      * Expects that a [[PlaybackProgress]] message has been published on the
      * message bus. The message is fetched from the bus and passed to the
      * provided check function.
      *
      * @param check the function verifying the properties of the progress
      *              message
      * @return this test helper
      */
    def expectPlaybackProgress(check: PlaybackProgress => Unit): ControllerTestHelper =
      val progress = messageBus.expectMessageType[PlaybackProgress]
      check(progress)
      this

    /**
      * Checks that no [[AudioPlayerState]] message was published on the message
      * bus for a certain period.
      *
      * @return this test helper
      */
    def expectNoAudioPlayerState(): ControllerTestHelper =
      messageBus.expectNoMessage(200.millis)
      this

    /**
      * Creates a mock for the factory of the audio player actor. The mock is
      * prepared to create a behavior that is backed by a test probe. The probe
      * is recorded in a queue, so that it can be obtained and used to inspect
      * messages sent to the child audio player actor.
      *
      * @return the mock audio player actor factory
      */
    private def createAudioPlayerActorFactory(): AudioPlayerActor.Factory =
      val factory = mock[AudioPlayerActor.Factory]
      when(factory.apply(any())).thenAnswer((invocation: InvocationOnMock) =>
        val playerConfig: AudioPlayerActor.Config = invocation.getArgument(0)
        playerConfig.archiveService should be(archiveService)
        playerConfig.lineCreatorFunc should be(LineWriterStage.DefaultLineCreatorFunc)
        val probe = testKit.createTestProbe[AudioPlayerActor.AudioPlayerCommand]()
        actorCreationQueue.offer(AudioPlayerActorCreation(probe, playerConfig))
        Behaviors.monitor(probe.ref, Behaviors.ignore))
      factory

    /**
      * Creates a controller instance to be tested.
      *
      * @return the test controller instance
      */
    private def createController(): ActorRef[AudioPlayerControllerActor.AudioPlayerControllerCommand] =
      val configService = mock[ConfigService]
      when(configService.config).thenReturn(platformConfig)
      val behavior = AudioPlayerControllerActor.newInstance(
        messageBus = messageBus,
        archiveService = archiveService,
        configService = configService,
        audioPlayerFactory = audioPlayerActorFactory
      )
      val ref = testKit.spawn(behavior)
      // The actor publishes its initial state as part of its setup; consume it, so that
      // it does not interfere with the message expectations in the test cases.
      initialPlayerState = messageBus.expectMessageType[AudioPlayerState]
      ref
