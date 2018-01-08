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

package de.oliver_heger.linedj.playlist.persistence

import java.nio.file.Paths

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ShutdownHandler}
import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.player.engine.{AudioSource, PlaybackProgressEvent}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.mockito.Mockito._
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.reflect.ClassTag

object PlaylistHandlerSpec extends PlaylistTestHelper {
  /** Path to the playlist file. */
  private val PathPlaylist = Paths get "persistedPlaylist.json"

  /** Path to the position file. */
  private val PathPosition = Paths get "position.json"

  /** The auto save interval from the configuration. */
  private val AutoSaveInterval = 90.seconds

  /** The maximum size of a playlist file. */
  private val MaxFileSize = 8 * 1024

  /** A test set playlist command. */
  private val TestSetPlaylist = generateSetPlaylist(16, 3, 1000, 60)
}

/**
  * Test class for ''PlaylistHandler''.
  */
class PlaylistHandlerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("PlaylistHandlerSpec"))

  import PlaylistHandlerSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A PlaylistHandler" should "register itself at the message bus on activation" in {
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

    helper.activate()
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

  it should "handle the completion of a playlist load operation" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .publishOnBus(LoadedPlaylist(TestSetPlaylist))
    helper.expectMessageOnBus[SetPlaylist] should be(TestSetPlaylist)
    helper.expectMessageOnBus[AudioPlayerStateChangeRegistration]
      .id should be(helper.handlerComponentID)
  }

  it should "remove the player state change registration on deactivate" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .deactivate()
      .expectMessageOnBus[AudioPlayerStateChangeUnregistration]
      .id should be(helper.handlerComponentID)
  }

  it should "forward a state update event to the state writer actor" in {
    val state = AudioPlayerState(TestSetPlaylist.playlist, 42, playbackActive = true,
      playlistClosed = false)
    val helper = new HandlerTestHelper
    helper.activate()
      .publishOnBus(LoadedPlaylist(TestSetPlaylist))
      .expectMessageOnBus[SetPlaylist]
    val reg = helper.expectMessageOnBus[AudioPlayerStateChangeRegistration]

    reg.callback(AudioPlayerStateChangedEvent(state))
    helper.expectStateWriterMsg(state)
  }

  it should "stop the state writer actor on deactivation" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .deactivate()
      .expectStateWriterActorStopped()
  }

  it should "forward a playback progress event to the state writer actor" in {
    val event = PlaybackProgressEvent(20171217, 214158, AudioSource("uri", 1000, 0, 0))
    val helper = new HandlerTestHelper

    helper.activate()
      .publishOnBus(event)
      .expectStateWriterMsg(event)
  }

  it should "send a close message to the state writer actor on shutdown" in {
    val helper = new HandlerTestHelper

    helper.activate()
      .sendShutdown()
      .expectStateWriterMsg(CloseRequest)
  }

  it should "take the configured shutdown timeout into account" in {
    val Timeout = 500.millis
    val helper = new HandlerTestHelper
    helper.config.setProperty(PlaylistHandlerConfig.PropShutdownTimeout, Timeout.toMillis)

    val confirm = helper.activate()
      .disableCloseAckForStateWriter()
      .sendShutdown()
      .expectMessageOnBus[ShutdownHandler.ShutdownDone]
    confirm.observerID should be(helper.handlerComponentID)
  }

  it should "wait for the close Ack of the state writer actor on deactivation" in {
    val Timeout = 500.millis
    val helper = new HandlerTestHelper
    helper.config.setProperty(PlaylistHandlerConfig.PropShutdownTimeout, Timeout.toMillis)

    helper.activate()
      .disableCloseAckForStateWriter()
      .sendShutdown()
      .expectNoMessageOnBus(Timeout.minus(100.millis))
  }

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class HandlerTestHelper {
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

    /** The client context passed to the test instance. */
    private val clientCtx = createClientContext()

    /** The handler to be tested. */
    private val handler = new PlaylistHandler

    /**
      * Activates the handler component under test.
      *
      * @return this test helper
      */
    def activate(): HandlerTestHelper = {
      handler initClientContext clientCtx
      handler.activate(mock[ComponentContext])
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
    def expectNoMessageOnBus(time: FiniteDuration): HandlerTestHelper = {
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
      * Sends a ''Shutdown'' message to the test handler.
      *
      * @return this test helper
      */
    def sendShutdown(): HandlerTestHelper = {
      publishOnBus(ShutdownHandler.Shutdown(clientCtx))
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
              props.args shouldBe 'empty
              probeLoader.ref
            case "persistentPlaylistStateWriterActor" =>
              classOf[PlaylistStateWriterActor].isAssignableFrom(props.actorClass()) shouldBe true
              classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
              props.args should be(List(PathPlaylist, PathPosition, AutoSaveInterval))
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
      sender ! CloseAck(probe)

    case msg =>
      probe ! msg
  }
}
