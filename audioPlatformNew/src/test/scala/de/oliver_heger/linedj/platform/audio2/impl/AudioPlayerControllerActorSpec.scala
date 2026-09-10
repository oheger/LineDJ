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
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerCommands, AudioPlayerState}
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.player.engine.stream.LineWriterStage
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
      .verifyMessageBusUnregistration()
      .verifyControllerActorStopped()

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

    /** The controller actor to be tested. */
    private val controllerActor = createController()

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
      * period.
      *
      * @return this test helper
      */
    def expectNoAudioPlayerCommand(): ControllerTestHelper =
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
      testKit.spawn(behavior)
