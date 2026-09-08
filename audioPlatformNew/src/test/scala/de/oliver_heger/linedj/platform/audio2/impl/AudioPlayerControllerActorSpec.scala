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
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
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

  /**
    * A test helper class that manages a controller instance to be tested and
    * its dependencies.
    */
  private class ControllerTestHelper:
    /** The message bus used by the test controller. */
    val messageBus: MessageBusTestImpl = new MessageBusTestImpl

    /** A queue to record audio player actor creations. */
    private val actorCreationQueue = new LinkedBlockingQueue[TestProbe[AudioPlayerActor.AudioPlayerCommand]]

    /** Test probe for the current audio player actor. */
    private var audioPlayerActor: TestProbe[AudioPlayerActor.AudioPlayerCommand] = uninitialized

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
      * Verifies that the audio player actor has been created with a correct
      * configuration. The function checks some basic properties of the
      * configuration. It also returns the object, so that further checks can
      * be done.
      *
      * @return the configuration for the audio player actor
      */
    def fetchAudioPlayerConfig(): AudioPlayerActor.Config =
      val captConfig = ArgumentCaptor.forClass(classOf[AudioPlayerActor.Config])
      verify(audioPlayerActorFactory).apply(captConfig.capture())
      captConfig.getValue.archiveService should be(archiveService)
      captConfig.getValue.lineCreatorFunc should be(LineWriterStage.DefaultLineCreatorFunc)
      captConfig.getValue

    /**
      * Expects that an audio player actor instance has been created. The new
      * instance is stored in a field of this test helper, so that the messages
      * sent to it can be tested.
      *
      * @return this test helper
      */
    def expectAudioPlayerCreation(): ControllerTestHelper =
      val newProbe = actorCreationQueue.poll(3, TimeUnit.SECONDS)
      newProbe should not be null
      audioPlayerActor = newProbe
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
      audioPlayerActor.expectMessage(command)
      this

    /**
      * Checks that no message was sent to the audio player actor for a certain
      * period.
      *
      * @return this test helper
      */
    def expectNoAudioPlayerCommand(): ControllerTestHelper =
      audioPlayerActor.expectNoMessage(200.millis)
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
        val probe = testKit.createTestProbe[AudioPlayerActor.AudioPlayerCommand]()
        actorCreationQueue.offer(probe)
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
