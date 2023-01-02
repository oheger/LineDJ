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

package de.oliver_heger.linedj.playlist.persistence

import java.nio.file.Paths

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.StateTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ShutdownHandler}
import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerFunction
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.AvailableMediaUnregistration
import de.oliver_heger.linedj.player.engine.{AudioSource, PlaybackProgressEvent}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag

object PlaylistHandlerSpec {
  /** Path to the playlist file. */
  private val PathPlaylist = Paths get "persistedPlaylist.json"

  /** Path to the position file. */
  private val PathPosition = Paths get "position.json"

  /** The auto save interval from the configuration. */
  private val AutoSaveInterval = 90.seconds

  /** The expected write configuration. */
  private val WriteConfig = PlaylistWriteConfig(PathPlaylist, PathPosition, AutoSaveInterval)

  /** The maximum size of a playlist file. */
  private val MaxFileSize = 8 * 1024

  /**
    * Convenience function to to generate an ''Iterable''. This is needed when
    * stubbing operations on the update service.
    *
    * @param m a sequence with messages
    * @return the ''Iterable'' with these messages
    */
  def messages(m: Any*): Iterable[Any] = List(m: _*)
}

/**
  * Test class for ''PlaylistHandler''.
  */
class PlaylistHandlerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("PlaylistHandlerSpec"))

  import PlaylistHandlerSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A PlaylistHandler" should "create a default update service" in {
    val handler = new PlaylistHandler

    handler.updateService should be(PersistentPlaylistStateUpdateServiceImpl)
  }

  it should "register itself at the message bus on activation" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .expectBusListenerRegistrationCount(1)
  }

  it should "remove the message bus listener registration on deactivation" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .deactivate()
      .expectBusListenerRegistrationCount(0)
  }

  it should "trigger a playlist load operation on activation" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .expectLoadPlaylistOperation()
  }

  it should "handle invalid configuration data" in {
    val helper = new HandlerTestHelper
    helper.config.clearProperty(PlaylistHandlerConfig.PropPositionPath)

    helper.activate(stubActivation = false, expInitMessages = false)
      .expectBusListenerRegistrationCount(0)
      .expectNoLoadPlaylistOperation()
      .deactivate()
    helper.numberOfCreatedActors should be(0)
  }

  it should "create the state writer actor during activation" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .numberOfCreatedActors should be(2)
  }

  it should "invoke the update service correctly" in {
    val MsgActivate1 = "activate1"
    val MsgActivate2 = "activate2"
    val MsgPlaylist = "playlist arrived"
    val MsgMedia = "media arrived"
    val Playlist = mock[SetPlaylist]
    val AvMedia = mock[AvailableMedia]
    val helper = new HandlerTestHelper
    val stateActive = PersistentPlaylistStateUpdateServiceImpl.InitialState
      .copy(componentID = Some(helper.handlerComponentID))
    val statePlaylist = stateActive.copy(loadedPlaylist = Some(mock[SetPlaylist]))
    val stateMedia = statePlaylist.copy(availableMediaIDs = Set(MediumID("test", None)))

    helper.stub(messages(MsgActivate1, MsgActivate2), stateActive) { s =>
      s.handleActivation(argEq(helper.handlerComponentID), any())
    }.stub(messages(MsgPlaylist), statePlaylist) { s =>
      s.handlePlaylistLoaded(argEq(Playlist), any())
    }.stub(messages(MsgMedia), stateMedia) { s =>
      s.handleNewAvailableMedia(AvMedia)
    }.activate(stubActivation = false)

    val mediaCallback = helper.capture { s =>
      val captor = ArgumentCaptor.forClass(classOf[ConsumerFunction[AvailableMedia]])
      verify(s).handleActivation(argEq(helper.handlerComponentID), captor.capture())
      captor
    }
    helper.publishOnBus(LoadedPlaylist(Playlist))
    mediaCallback(AvMedia)
    List(MsgActivate1, MsgActivate2, MsgPlaylist, MsgMedia) foreach { m =>
      helper.expectMessageOnBus[Any] should be(m)
    }
    helper.expectUpdatedState(PersistentPlaylistStateUpdateServiceImpl.InitialState,
      stateActive, statePlaylist)
  }

  it should "remove all registrations on deactivate" in {
    val helper = new HandlerTestHelper
    helper.activate().deactivate()

    helper.expectMessageOnBus[AudioPlayerStateChangeUnregistration].id should be(helper.handlerComponentID)
    helper.expectMessageOnBus[AvailableMediaUnregistration].id should be(helper.handlerComponentID)
    helper.expectMessageOnBus[ShutdownHandler.RemoveShutdownObserver].observerID should be(helper.handlerComponentID)
  }

  it should "forward a state update event to the state writer actor" in {
    val Msg = "test_message"
    val TestList = mock[SetPlaylist]
    val state = AudioPlayerState(mock[Playlist], 42, playbackActive = true,
      playlistClosed = false, playlistActivated = true)
    val helper = new HandlerTestHelper

    helper.stub(messages(Msg), PersistentPlaylistStateUpdateServiceImpl.InitialState) { s =>
      s.handlePlaylistLoaded(argEq(TestList), any())
    }.activate()
      .publishOnBus(LoadedPlaylist(TestList))
      .expectStateWriterMsg(TestList)
    helper.expectMessageOnBus[Any] should be(Msg)
    val eventCallback = helper.capture { s =>
      val captor =
        ArgumentCaptor.forClass(classOf[ConsumerFunction[AudioPlayerStateChangedEvent]])
      verify(s).handlePlaylistLoaded(argEq(TestList), captor.capture())
      captor
    }
    eventCallback(AudioPlayerStateChangedEvent(state))
    helper.expectStateWriterMsg(state)
  }

  it should "stop the state writer actor on deactivation" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .deactivate()
      .expectStateWriterActorStopped()
  }

  it should "forward a playback progress event to the state writer actor" in {
    val event = PlaybackProgressEvent(20171217, 214158.seconds, AudioSource("uri", 1000, 0, 0))
    val helper = new HandlerTestHelper

    helper.activate()
      .publishOnBus(event)
      .expectStateWriterMsg(event)
  }

  it should "send a close message to the state writer actor on shutdown" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .triggerShutdown()
      .expectStateWriterMsg(CloseRequest)
  }

  it should "take the configured shutdown timeout into account" in {
    val Timeout = 500.millis
    val helper = new HandlerTestHelper
    helper.config.setProperty(PlaylistHandlerConfig.PropShutdownTimeout, Timeout.toMillis)

    helper.activate()
      .disableCloseAckForStateWriter()
      .triggerShutdown()
      .expectShutdownCompletionNotification()
  }

  it should "wait for the close Ack of the state writer actor on deactivation" in {
    val Timeout = 500.millis
    val helper = new HandlerTestHelper
    helper.config.setProperty(PlaylistHandlerConfig.PropShutdownTimeout, Timeout.toMillis)

    helper.activate()
      .disableCloseAckForStateWriter()
      .triggerShutdown()
      .expectShutdownCompletionNotification(0)
  }

  it should "stop the state writer actor when handling the shutdown notification" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .triggerShutdown()
      .expectStateWriterActorStopped()
  }

  it should "not stop the state writer actor in deactivate() after shutdown handling" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .triggerShutdown()
      .deactivate()
    helper.numberOfStateWriterStops should be(1)
  }

  it should "not do any shutdown handling in deactivate() if the activation failed" in {
    val helper = new HandlerTestHelper
    helper.config.clearProperty(PlaylistHandlerConfig.PropPositionPath)

    helper.activate(stubActivation = false, expInitMessages = false)
      .deactivate()
      .expectNoMessageOnBus(time = 100.millis)
  }

  it should "shutdown the state writer actor in deactivate() if there was no shutdown message" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .deactivate()
      .expectStateWriterMsg(CloseRequest)
      .expectStateWriterActorStopped()
    helper.numberOfStateWriterStops should be(1)
  }

  it should "take the configured shutdown timeout into account when doing shutdown in deactivate()" in {
    val Timeout = 1.second
    val helper = new HandlerTestHelper
    helper.config.setProperty(PlaylistHandlerConfig.PropShutdownTimeout, Timeout.toMillis)

    helper.activate()
      .disableCloseAckForStateWriter()
    import system.dispatcher
    val futDeactivate = Future(helper.deactivate())
    Await.ready(futDeactivate, 2.seconds)
  }

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class HandlerTestHelper
    extends StateTestHelper[PersistentPlaylistState, PersistentPlaylistStateUpdateService] {
    /** Mock for the playlist state update service. */
    override val updateService: PersistentPlaylistStateUpdateService = mock[PersistentPlaylistStateUpdateService]

    /** The test message bus. */
    private val messageBus = new MessageBusTestImpl

    /** Test probe for the playlist loader actor. */
    private val probeLoader = TestProbe()

    /** Test probe for the playlist state writer actor. */
    private val probeWriter = TestProbe()

    /** An actor simulating the state writer actor. */
    private val stateWriterActor =
      system.actorOf(Props(classOf[StateWriterTestActor], probeWriter.ref))

    /** Stores the names of actors that have been created. */
    private var createdActors = Set.empty[String]

    /** Mock for the completion notifier. */
    private val completionNotifier = mock[ShutdownHandler.ShutdownCompletionNotifier]

    /** Records the number of times the state writer actor was stopped. */
    private var stateWriterStops = 0

    /** The client context passed to the test instance. */
    private val clientCtx = createClientContext()

    /** The handler to be tested. */
    private val handler = createHandler()

    /**
      * Activates the handler component under test. This method already expects
      * the consumer registrations that need to be done on activation and can
      * pass an object with available media.
      *
      * @param stubActivation  flag whether the activation on the update service
      *                        should be mocked
      * @param expInitMessages flag whether messages for the initialization of
      *                        the component are expected on the message bus
      * @return this test helper
      */
    def activate(stubActivation: Boolean = true, expInitMessages: Boolean = true): HandlerTestHelper = {
      if (stubActivation) {
        stub(List.empty[Any]: Iterable[Any],
          PersistentPlaylistStateUpdateServiceImpl.InitialState) { s =>
          s.handleActivation(argEq(handlerComponentID), any())
        }
      }
      handler initClientContext clientCtx
      handler.activate(mock[ComponentContext])
      if (expInitMessages) {
        val regMsg = messageBus.expectMessageType[ShutdownHandler.RegisterShutdownObserver]
        regMsg.observerID should be(handler.componentID)
        regMsg.observer should be(handler)
        messageBus.processNextMessage[PlaylistHandlerConfig]()
      } else messageBus.expectNoMessage()
      this
    }

    /**
      * Deactivates the handler component under test.
      *
      * @return this test helper
      */
    def deactivate(): HandlerTestHelper = {
      handler.deactivate(mock[ComponentContext])
      this
    }

    /**
      * Tests that the given number of listeners is registered at the message
      * bus.
      *
      * @param count the expected number of listeners
      * @return this test helper
      */
    def expectBusListenerRegistrationCount(count: Int): HandlerTestHelper = {
      messageBus.currentListeners should have size count
      this
    }

    /**
      * Expects that the load actor was triggered to load the persistent
      * playlist.
      *
      * @return this test helper
      */
    def expectLoadPlaylistOperation(): HandlerTestHelper = {
      val expMsg = LoadPlaylistActor.LoadPlaylistData(PathPlaylist, PathPosition, MaxFileSize,
        messageBus)
      probeLoader.expectMsg(expMsg)
      this
    }

    /**
      * Checks that no playlist load operation took place.
      *
      * @return this test helper
      */
    def expectNoLoadPlaylistOperation(): HandlerTestHelper = {
      val pingMsg = new Object
      probeLoader.ref ! pingMsg
      probeLoader.expectMsg(pingMsg)
      this
    }

    /**
      * Expects that the specified message was passed to the state writer
      * actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectStateWriterMsg(msg: Any): HandlerTestHelper = {
      probeWriter.expectMsg(msg)
      this
    }

    /**
      * Sens the specified message on the message bus. It should be retrieved
      * by the test instance.
      *
      * @param msg the message
      * @return this test helper
      */
    def publishOnBus(msg: Any): HandlerTestHelper = {
      messageBus publishDirectly msg
      this
    }

    /**
      * Expects that a message of the given type was published on the message
      * bus.
      *
      * @return the message
      */
    def expectMessageOnBus[A](implicit t: ClassTag[A]): A =
      messageBus.expectMessageType[A]

    /**
      * Tests that no message is published on the message bus for the
      * specified time.
      *
      * @param time the time
      * @return this test helper
      */
    def expectNoMessageOnBus(time: FiniteDuration = 10.millis): HandlerTestHelper = {
      messageBus.expectNoMessage(time)
      this
    }

    /**
      * Checks that the state writer actor has been stopped.
      *
      * @return this test helper
      */
    def expectStateWriterActorStopped(): HandlerTestHelper = {
      val probeWatcher = TestProbe()
      probeWatcher watch stateWriterActor
      probeWatcher.expectMsgType[Terminated]
      this
    }

    /**
      * Configures the test state writer actor not to send a close Ack.
      *
      * @return this test helper
      */
    def disableCloseAckForStateWriter(): HandlerTestHelper = {
      stateWriterActor ! IgnoreCloseRequest
      this
    }

    /**
      * Invokes the test handler to trigger a shutdown operation.
      *
      * @return this test helper
      */
    def triggerShutdown(): HandlerTestHelper = {
      handler.triggerShutdown(completionNotifier)
      this
    }

    /**
      * Verifies that the mock for the completion notifier has been invoked
      * the given number of times.
      *
      * @param expTimes the expected number of invocations
      * @return this test helper
      */
    def expectShutdownCompletionNotification(expTimes: Int = 1): HandlerTestHelper = {
      verify(completionNotifier, timeout(1000).times(expTimes)).shutdownComplete()
      this
    }

    /**
      * Allows access to the configuration passed to the test handler. This can
      * be used to tweak certain settings in tests.
      *
      * @return the configuration
      */
    def config: Configuration = clientCtx.managementConfiguration

    /**
      * Returns the number of actors created by the test handler.
      *
      * @return the number of actors created
      */
    def numberOfCreatedActors: Int = createdActors.size

    /**
      * Returns the component ID of the test handler instance.
      *
      * @return the component ID of the test handler
      */
    def handlerComponentID: ComponentID = handler.componentID

    /**
      * Expects that the provided states have been involved in update
      * operations (in this order).
      *
      * @param states the sequence of expected states
      * @return this test helper
      */
    def expectUpdatedState(states: PersistentPlaylistState*): HandlerTestHelper = {
      states foreach { s =>
        nextUpdatedState().get should be(s)
      }
      this
    }

    /**
      * Returns a counter for the stop invocations of the state writer actor.
      *
      * @return the counter for stops of the state writer actor
      */
    def numberOfStateWriterStops: Int = stateWriterStops

    /**
      * Creates the client application context.
      *
      * @return the context
      */
    private def createClientContext(): ClientApplicationContext = {
      val context = mock[ClientApplicationContext]
      when(context.messageBus).thenReturn(messageBus)
      when(context.actorSystem).thenReturn(system)
      when(context.managementConfiguration).thenReturn(createConfig())
      when(context.actorFactory).thenReturn(new ActorFactory(system) {
        override def createActor(props: Props, name: String): ActorRef = {
          createdActors should not contain name
          createdActors += name
          name match {
            case "persistentPlaylistLoaderActor" =>
              props.actorClass() should be(classOf[LoadPlaylistActor])
              props.args shouldBe empty
              probeLoader.ref
            case "persistentPlaylistStateWriterActor" =>
              classOf[PlaylistStateWriterActor].isAssignableFrom(props.actorClass()) shouldBe true
              classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
              props.args should be(List(WriteConfig))
              stateWriterActor
          }
        }
      })
      context
    }

    /**
      * Creates the test configuration for the playlist handler component.
      *
      * @return the initialized configuration
      */
    private def createConfig(): Configuration = {
      val config = new PropertiesConfiguration
      config.addProperty(PlaylistHandlerConfig.PropPlaylistPath, PathPlaylist.toString)
      config.addProperty(PlaylistHandlerConfig.PropPositionPath, PathPosition.toString)
      config.addProperty(PlaylistHandlerConfig.PropAutoSaveInterval, AutoSaveInterval.toSeconds)
      config.addProperty(PlaylistHandlerConfig.PropMaxFileSize, MaxFileSize)
      config
    }

    /**
      * Creates the handler to be tested. This instance support some more
      * mocking facilities.
      *
      * @return the test handler instance
      */
    private def createHandler(): PlaylistHandler =
      new PlaylistHandler(updateService) {
        /**
          * @inheritdoc Records this invocation.
          */
        override private[persistence] def stopStateWriterActor(act: ActorRef): Unit = {
          stateWriterStops += 1
          super.stopStateWriterActor(act)
        }
      }
  }

}

/**
  * A message which tells the state writer test actor to ignore a close
  * request.
  */
case object IgnoreCloseRequest

/**
  * A test actor which simulates a state writer actor.
  *
  * This class mainly handles closing logic by answering a close request with
  * an Ack message. (This is difficult to achieve with a test probe.) All other
  * messages are propagated to a test probe, so that the normal expectation
  * methods work.
  *
  * @param probe the probe to forward messages to
  */
private class StateWriterTestActor(probe: ActorRef) extends Actor {
  private var acceptClose = true

  override def receive: Actor.Receive = {
    case IgnoreCloseRequest =>
      acceptClose = false

    case CloseRequest if acceptClose =>
      probe ! CloseRequest
      sender() ! CloseAck(probe)

    case msg =>
      probe ! msg
  }
}
