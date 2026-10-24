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

import de.oliver_heger.linedj.StateTestHelper
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerCommands, AudioPlayerState, PlaybackProgress}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.{Done, actor as classic}
import org.apache.pekko.actor.Actor.Receive
import org.apache.pekko.actor.DeadLetter
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.{Future, Promise}

object PlaylistSaveActorSpec:
  /** The ID returned by the message bus registration. */
  private val MessageBusListenerID = 42

  /** A test config to write playlist data. */
  private val TestWriteConfig = PlaylistWriteStateUpdateService.PlaylistWriteConfig(
    pathPlaylist = Paths.get("playlist.lst"),
    pathPosition = Paths.get("position.pos"),
    autoSaveInterval = 2.minutes
  )

  /**
    * Constant for an actions object that does not trigger any actions.
    */
  private val NoActions = PlaylistWriteStateUpdateService.WriteStateTransitionActions(Nil, false)

  /**
    * Creates a write operation based on the given index.
    *
    * @param idx the index
    * @return the operation
    */
  private def createWriteOperation(idx: Int): PlaylistSaveService.WriteOperation =
    PlaylistSaveService.WriteOperation(
      data = Source.single(ByteString(idx.toString)),
      target = if idx % 2 == 0 then TestWriteConfig.pathPlaylist else TestWriteConfig.pathPosition
    )
end PlaylistSaveActorSpec

/**
  * Test class for [[PlaylistSaveActor]].
  */
class PlaylistSaveActorSpec extends ScalaTestWithActorTestKit, AnyFlatSpecLike, Matchers, MockitoSugar:

  import PlaylistSaveActorSpec.*

  "A PlaylistSaveActor" should "store the initial playlist" in :
    val playlist = mock[Playlist]
    val PosOfs = 20261021212804L
    val TimeOfs = 92.seconds
    val state1 = mock[PlaylistWriteStateUpdateService.PlaylistWriteState]
    val initialPlaylistCmd = AudioPlayerCommands.SetPlaylist(playlist, positionOffset = PosOfs, timeOffset = TimeOfs)
    val helper = new SaveActorTestHelper

    helper.stub((), state1): svc =>
      svc.initPlaylist(helper.playlistService, playlist, PosOfs, TimeOfs)
    .stub(NoActions, mock[PlaylistWriteStateUpdateService.PlaylistWriteState]): svc =>
      svc.handlePlaybackProgress(helper.saveService, PosOfs + 100, TimeOfs + 1.second, TestWriteConfig)
    .publishOnMessageBus(initialPlaylistCmd)
    .publishOnMessageBus(PlaybackProgress(PosOfs + 100, TimeOfs + 1.second))
    .expectStateUpdates(PlaylistWriteStateUpdateServiceImpl.InitialState, state1)

  it should "handle an audio player state update event" in :
    val playerState = mock[AudioPlayerState]
    val nextState = mock[PlaylistWriteStateUpdateService.PlaylistWriteState]
    val PosOfs = 20261023220010L
    val TimeOfs = 3.minutes
    val helper = new SaveActorTestHelper

    helper.stub(NoActions, nextState): svc =>
      svc.handlePlayerStateChange(helper.playlistService, helper.saveService, playerState, TestWriteConfig)
    .stub(NoActions, mock[PlaylistWriteStateUpdateService.PlaylistWriteState]): svc =>
      svc.handlePlaybackProgress(helper.saveService, PosOfs, TimeOfs, TestWriteConfig)
    .publishOnMessageBus(playerState)
    .publishOnMessageBus(PlaybackProgress(PosOfs, TimeOfs))
    .expectStateUpdates(PlaylistWriteStateUpdateServiceImpl.InitialState, nextState)

  it should "handle file written notifications" in :
    val write1 = createWriteOperation(1)
    val write2 = createWriteOperation(2)
    val promiseWrite1 = Promise[Done]()
    val promiseWrite2 = Promise[Done]()
    val stateTemp1 = mock[PlaylistWriteStateUpdateService.PlaylistWriteState]
    val stateTemp2 = mock[PlaylistWriteStateUpdateService.PlaylistWriteState]
    val actions = PlaylistWriteStateUpdateService.WriteStateTransitionActions(List(write1, write2), false)
    val PosOfs = 20261024160825L
    val TimeOfs = 2.minutes
    val helper = new SaveActorTestHelper

    helper.stub(actions, stateTemp1): svc =>
      svc.handlePlaybackProgress(helper.saveService, PosOfs, TimeOfs, TestWriteConfig)
    .stub(NoActions, stateTemp2): svc =>
      svc.handleFileWritten(write1.target, TestWriteConfig)
    .stub(NoActions, mock[PlaylistWriteStateUpdateService.PlaylistWriteState]): svc =>
      svc.handleFileWritten(write2.target, TestWriteConfig)
    .expectWriteOperation(write1, promiseWrite1.future)
    .expectWriteOperation(write2, promiseWrite2.future)
    .publishOnMessageBus(PlaybackProgress(PosOfs, TimeOfs))

    promiseWrite1.success(Done)
    helper.expectStateUpdates(PlaylistWriteStateUpdateServiceImpl.InitialState, stateTemp1)
    promiseWrite2.failure(new IllegalStateException("Test exception: Could not write file."))
    helper.expectStateUpdate(stateTemp2)

  it should "handle a close request" in :
    val helper = new SaveActorTestHelper

    helper.stub(NoActions, mock[PlaylistWriteStateUpdateService.PlaylistWriteState]): svc =>
      svc.handleCloseRequest(helper.saveService, TestWriteConfig)
    .closeHandle()
    .expectStateUpdates(PlaylistWriteStateUpdateServiceImpl.InitialState)

  it should "manage the shutdown future" in :
    val write = createWriteOperation(1)
    val promiseWrite = Promise[Done]()
    val stateTemp = mock[PlaylistWriteStateUpdateService.PlaylistWriteState]
    val actions1 = NoActions.copy(writes = List(write))
    val actions2 = NoActions.copy(closePossible = true)
    val helper = new SaveActorTestHelper

    helper.stub(actions1, stateTemp): svc =>
      svc.handleCloseRequest(helper.saveService, TestWriteConfig)
    .stub(actions2, mock[PlaylistWriteStateUpdateService.PlaylistWriteState]): svc =>
      svc.handleFileWritten(write.target, TestWriteConfig)
    .expectWriteOperation(write, promiseWrite.future)
    .closeHandle()
    .expectNoActorShutdown()

    promiseWrite.success(Done)
    helper.expectActorShutdown()

  it should "stop itself after a close request was handled" in :
    val actions = NoActions.copy(closePossible = true)
    val helper = new SaveActorTestHelper

    helper.stub(actions, mock[PlaylistWriteStateUpdateService.PlaylistWriteState]): svc =>
      svc.handleCloseRequest(helper.saveService, TestWriteConfig)
    .closeHandle()

    val deadLetterProbe = testKit.createDeadLetterProbe()
    helper.publishOnMessageBus(PlaybackProgress(20261024171938L, 63.seconds))
    deadLetterProbe.expectMessageType[DeadLetter]

  it should "remove the message bus registration when it stops" in :
    val actions = NoActions.copy(closePossible = true)
    val helper = new SaveActorTestHelper

    helper.stub(actions, mock[PlaylistWriteStateUpdateService.PlaylistWriteState]): svc =>
      svc.handleCloseRequest(helper.saveService, TestWriteConfig)
    .closeHandle()
    .verifyMessageBusUnregistration()

  /**
    * A test helper class to manage an actor to test and its dependencies.
    */
  private class SaveActorTestHelper
    extends StateTestHelper[PlaylistWriteStateUpdateService.PlaylistWriteState, PlaylistWriteStateUpdateService]:
    override val updateService: PlaylistWriteStateUpdateService = mock[PlaylistWriteStateUpdateService]

    /** Mock for the playlist service. */
    val playlistService: PlaylistService[Playlist, String] = mock

    /** Mock for the playlist save service. */
    val saveService: PlaylistSaveService = mock

    /** Mock for the message bus. */
    private val messageBus = createMessageBus()

    /**
      * Stores the receiver that was registered at the message bus. It is 
      * obtained on demand.
      */
    private var messageBusReceiverField: Option[Receive] = None

    /** The handle to the test actor instance. */
    private val handle = createTestActor()

    /**
      * Publishes the specified message on the simulated system message bus.
      *
      * @param msg the message to publish
      * @return this test helper
      */
    def publishOnMessageBus(msg: Any): SaveActorTestHelper =
      messageBusReceiver(msg)
      this

    /**
      * Prepares the mock for the save service to expect a write operation that 
      * yields a specific result.
      *
      * @param write  the expected write operation
      * @param result the result to return
      * @return this test helper
      */
    def expectWriteOperation(write: PlaylistSaveService.WriteOperation,
                             result: Future[Done]): SaveActorTestHelper =
      when(saveService.store(write)).thenReturn(result)
      this

    /**
      * Expects that the given state updates were done.
      *
      * @param states the states passed to the update service
      * @return this test helper
      */
    def expectStateUpdates(states: PlaylistWriteStateUpdateService.PlaylistWriteState*): SaveActorTestHelper =
      states foreach : s =>
        nextUpdatedState().get should be(s)
      this

    /**
      * Invokes the _close()_ method on the actor handle.
      *
      * @return this test helper
      */
    def closeHandle(): SaveActorTestHelper =
      handle.close()
      this

    /**
      * Expects that the shutdown future of the actor under tests completes
      * within a grace period.
      *
      * @return this test helper
      */
    def expectActorShutdown(): SaveActorTestHelper =
      shutdownLatch.await(3, TimeUnit.SECONDS) shouldBe true
      this

    /**
      * Expects that the shutdown future of the actor under test does not
      * complete within a grace period.
      *
      * @return this test helper
      */
    def expectNoActorShutdown(): SaveActorTestHelper =
      shutdownLatch.await(200, TimeUnit.MILLISECONDS) shouldBe false
      this

    /**
      * Verifies that the registration on the message bus has been removed
      * again.
      *
      * @return this test helper
      */
    def verifyMessageBusUnregistration(): SaveActorTestHelper =
      verify(messageBus, Mockito.timeout(3000)).removeListener(MessageBusListenerID)
      this

    /**
      * Returns a [[CountDownLatch]] that can be used to wait for the
      * completion of the shutdown [[Future]] managed by the test actor.
      *
      * @return a [[CountDownLatch]] to wait for the shutdown [[Future]]
      */
    private def shutdownLatch: CountDownLatch =
      val latch = new CountDownLatch(1)
      handle.shutdownFuture.foreach(_ => latch.countDown())(using system.executionContext)
      latch

    /**
      * Returns the receiver that was registered at the message bus.
      *
      * @return the message bus receiver
      */
    private def messageBusReceiver: Receive =
      messageBusReceiverField match
        case Some(receiver) => receiver
        case None =>
          val captor = ArgumentCaptor.forClass(classOf[Receive])
          verify(messageBus, Mockito.timeout(3000)).registerListener(captor.capture())
          messageBusReceiverField = Some(captor.getValue)
          captor.getValue

    /**
      * Creates a mock for the message bus that is prepared to accept a 
      * listener registration.
      *
      * @return the mock for the message bus
      */
    private def createMessageBus(): MessageBus =
      val bus = mock[MessageBus]
      when(bus.registerListener(any())).thenReturn(MessageBusListenerID)
      bus

    /**
      * Returns an actor factory implementation that can be used to create new
      * test instances of the actor under test. It checks passed in parameters
      * and delegates to the test kit to create new instances.
      *
      * @return the actor factory for testing
      */
    private def createActorFactory(): ActorFactory =
      new ActorFactory:
        override def actorSystem: classic.ActorSystem = testKit.system.toClassic

        override def createClassicActor(props: classic.Props,
                                        name: String,
                                        optStopCommand: Option[Any]): classic.ActorRef =
          throw new UnsupportedOperationException("Unexpected creation of classic actor.")

        override def createTypedActor[T](behavior: Behavior[T],
                                         name: String,
                                         props: Props,
                                         optStopCommand: Option[T]): ActorRef[T] =
          name should be(PlaylistSaveActor.ActorName)
          optStopCommand shouldBe empty
          testKit.spawn(behavior)

    /**
      * Creates a new actor instance to be tested.
      *
      * @return the new test instance
      */
    private def createTestActor(): PlaylistSaveActor.Handle =
      val params = PlaylistSaveActor.Params(
        playlistService = playlistService,
        playlistSaveService = saveService,
        updateService = updateService,
        writeConfig = TestWriteConfig,
        messageBus = messageBus
      )
      PlaylistSaveActor.newInstance(params)(using createActorFactory())