/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.radio

import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.{AppWithTestPlatform, ApplicationAsyncStartup, ApplicationSyncStartup, ApplicationTestSupport, ClientApplicationContext, ClientApplicationContextImpl}
import de.oliver_heger.linedj.platform.app.support.ActorManagementComponent
import de.oliver_heger.linedj.player.engine.PlaybackContextFactory
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource, RadioSourceChangedEvent}
import de.oliver_heger.linedj.utils.ActorFactory
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.apache.commons.configuration.Configuration
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.pattern.AskTimeoutException
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{eq as eqArg, *}
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentHashMap, TimeUnit}
import scala.collection.immutable.Seq
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.util.{Failure, Random, Success}

/**
  * Test class for ''RadioPlayerApplication''.
  */
class RadioPlayerApplicationSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar:
  def this() = this(ActorSystem("RadioPlayerApplicationSpec"))

  /** A counter for generating unique names for actors. */
  private val actorNameCounter = new AtomicInteger

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "A RadioPlayerApplication" should "define a correct default constructor" in:
    val app = new RadioPlayerApplication

    app.playerFactory should not be null
    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("radioplayer")

  it should "add playback context factories arrived after creation to the player" in:
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp()

    val factories = helper addPlaybackContextFactories 8
    helper.checkAddedPlaybackContextFactories(factories)

  it should "remove playback context factories from the player after startup" in:
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp()

    val factoriesAdded = helper addPlaybackContextFactories 8
    val part = Random.shuffle(factoriesAdded) splitAt 4
    part._1 foreach helper.app.removePlaylistContextFactory
    helper.checkAddedPlaybackContextFactories(part._2)

  it should "add playback context factories arrived before creation of the player" in:
    val helper = new RadioPlayerApplicationTestHelper
    val factories = helper addPlaybackContextFactories 8
    helper.activateRadioApp()

    helper.checkAddedPlaybackContextFactories(factories)

  it should "remove playback context factories before the creation of the player" in:
    val helper = new RadioPlayerApplicationTestHelper
    val factoriesAdded = helper addPlaybackContextFactories 8
    val part = Random.shuffle(factoriesAdded) splitAt 4
    part._1 foreach helper.app.removePlaylistContextFactory
    helper.activateRadioApp()

    helper.checkAddedPlaybackContextFactories(part._2)

  it should "correctly synchronize adding playlist context factories" in:
    val helper = new RadioPlayerApplicationTestHelper
    val queue = new ArrayBlockingQueue[Seq[PlaybackContextFactory]](1)
    val thread = new Thread:
      override def run(): Unit =
        Thread sleep 500
        queue.put(helper addPlaybackContextFactories 2000)
    thread.start()
    helper.activateRadioApp()
    thread.join(5000)

    val factories = queue.poll(2, TimeUnit.SECONDS)
    helper.checkAddedPlaybackContextFactories(factories)

  it should "correctly synchronize removing playlist context factories" in:
    val helper = new RadioPlayerApplicationTestHelper
    val factories = helper addPlaybackContextFactories 2500
    val thread = new Thread:
      override def run(): Unit =
        Thread sleep 500
        factories foreach helper.app.removePlaylistContextFactory
    thread.start()
    helper.activateRadioApp()
    thread.join(5000)

    helper.checkAddedPlaybackContextFactories(List.empty)

  it should "close the player on shutdown" in:
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp(clearMessageBus = false)

    val promise = Promise.successful(Seq.empty[CloseAck])
    helper.deactivateTest(promise)
    verify(helper.player, timeout(1000)).close()(any(classOf[ExecutionContextExecutor]), eqArg(Timeout(3.seconds)))

  it should "not crash on shutdown if there is no radio player" in:
    val helper = new RadioPlayerApplicationTestHelper
    val clCtx = new ClientApplicationContextImpl(messageBus = helper.messageBus)
    helper.app.initClientContext(clCtx)

    helper.app.closePlayer()

  it should "wait until the player terminates" in:
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp(clearMessageBus = false)
    val timestamp = new AtomicLong
    val promise = Promise[Seq[CloseAck]]()
    val thread = new Thread:
      override def run(): Unit =
        Thread sleep 400
        timestamp.set(System.nanoTime())
        promise.success(List.empty)
    thread.start()

    helper.deactivateTest(promise)
    val shutdownComplete = System.nanoTime()
    thread.join(2000)
    shutdownComplete should be > timestamp.get()

  it should "ignore exceptions when closing the player" in:
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp(clearMessageBus = false)

    val promise = Promise.failed[Seq[CloseAck]](new AskTimeoutException("Test timeout exception"))
    helper.deactivateTest(promise)

  it should "correctly initialize the controller in the Jelly script" in:
    val helper = new RadioPlayerApplicationTestHelper
    val app = helper.activateRadioApp()

    val ctrl = helper.queryBean[RadioController](app.getMainWindowBeanContext, "radioController")

    ctrl.userConfig should be(app.getUserConfiguration)
    val message = RadioController.RadioPlayerInitialized(Success(helper.player))
    helper.messageBus.findListenerForMessage(message) should not be empty

  it should "send a message with the initialized radio player on the message bus" in:
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp(clearMessageBus = false)

    val playerInitMessage = helper.messageBus.findMessageType[RadioController.RadioPlayerInitialized]

    playerInitMessage.triedRadioPlayer should be(Success(helper.player))

  it should "handle a failed creation of the radio player" in:
    val helper = new RadioPlayerApplicationTestHelper
    val exception = new IllegalStateException("Test exception: Player creation failed.")
    helper.prepareFailedPlayerCreation(exception)
    helper.activateRadioApp(clearMessageBus = false)

    val playerInitMessage = helper.messageBus.findMessageType[RadioController.RadioPlayerInitialized]

    playerInitMessage.triedRadioPlayer should be(Failure(exception))

  it should "not publish the player on the message bus before the UI was initialized" in:
    // A helper with an application that does not initialize the UI.
    val helper = new RadioPlayerApplicationTestHelper:
      override val app: RadioPlayerApplication with ApplicationSyncStartup =
        new RadioPlayerApplication(playerFactory) with ApplicationSyncStartup with AppWithTestPlatform:
          override def initGUI(appCtx: ApplicationContext): Unit = {}

    helper.activateRadioApp(clearMessageBus = false)

    helper.messageBus.expectNoMessage(500.millis)

  it should "register a listener actor at the radio player" in:
    val helper = new RadioPlayerApplicationTestHelper
    helper.activateRadioApp()

    val captor = ArgumentCaptor.forClass(classOf[ActorRef[RadioEvent]])
    verify(helper.player, timeout(3000)).addEventListener(captor.capture())

    val playerEvent = RadioSourceChangedEvent(RadioSource("testRadioSource"))
    val listenerActor = captor.getValue
    listenerActor ! playerEvent
    val publishedEvent = helper.messageBus.findMessageType[RadioEvent]
    publishedEvent should be(playerEvent)

  /**
    * A test helper class managing dependencies of a test instance and
    * providing some useful functionality.
    */
  private class RadioPlayerApplicationTestHelper extends ApplicationTestSupport:
    /** A mock for the radio player. */
    val player: RadioPlayer = createPlayerMock()

    /** A mock for the radio player factory. */
    val playerFactory: RadioPlayerFactory = createPlayerFactory(player)

    /** The application to be tested. */
    val app: RadioPlayerApplication with ApplicationSyncStartup = new RadioPlayerApplication(playerFactory)
      with ApplicationSyncStartup with AppWithTestPlatform

    /** The test message bus. */
    val messageBus = new MessageBusTestImpl

    /**
      * Stores the ''PlaybackContextFactory'' objects that were passed to the
      * player mock.
      */
    private val playbackContextFactories = new ConcurrentHashMap[PlaybackContextFactory, Boolean]

    /**
      * @inheritdoc Injects the test message bus in the application context.
      */
    override def createClientApplicationContext(config: Configuration): ClientApplicationContext =
      new ClientApplicationContextImpl(managementConfiguration = config,
        messageBus = messageBus,
        actorSystem = system,
        actorFactory = createActorFactory())

    /**
      * Starts up the test application.
      *
      * @param clearMessageBus flag whether the message bus should be reset
      * @return the test application
      */
    def activateRadioApp(clearMessageBus: Boolean = true): RadioPlayerApplication =
      val activatedApp = activateApp(app)
      if clearMessageBus then
        messageBus.clearMessages()
      activatedApp

    /**
      * Creates the given number of ''PlaybackContextFactory'' mocks and adds
      * them to the test application.
      *
      * @param count the number of factories to be added
      * @return a sequence with the mock factories that have been created
      */
    def addPlaybackContextFactories(count: Int): Seq[PlaybackContextFactory] =
      val factories = (1 to count).map(_ => mock[PlaybackContextFactory])
      factories foreach app.addPlaylistContextFactory
      factories

    /**
      * Returns a set with the playback context factories that have been
      * added to the radio player.
      *
      * @return the set with added ''PlaybackContextFactory'' objects
      */
    def addedPlaybackContextFactories: Set[PlaybackContextFactory] =
      import scala.jdk.CollectionConverters._
      playbackContextFactories.keySet().asScala.toSet

    /**
      * Checks whether the expected ''PlaybackContextFactory'' objects have
      * been added to the radio player. This is a bit tricky, since the player
      * is created asynchronously.
      *
      * @param expected the expected factories
      */
    def checkAddedPlaybackContextFactories(expected: Iterable[PlaybackContextFactory]): Unit =
      val expectedSet = expected.toSet
      awaitCond(addedPlaybackContextFactories == expectedSet)

    /**
      * Triggers a test for a deactivation of the application. Note: When
      * invoking this function, the message bus should not have been cleared
      * before.
      *
      * @param p the promise for the future to be returned by the player
      */
    def deactivateTest(p: Promise[Seq[CloseAck]]): Unit =
      // Wait until initialization of the player is complete.
      messageBus.findMessageType[RadioController.RadioPlayerInitialized]

      // Wait for the event listener registration; otherwise Mockito's stubbing gets messed up.
      verify(player, timeout(3000)).addEventListener(any())

      when(player.close()(system.dispatcher, Timeout(3.seconds))).thenReturn(p.future)
      app.deactivate(mock[ComponentContext])

    /**
      * Prepares the mock for the player factory to return a failed ''Future''
      * with the given exception.
      *
      * @param exception the exception to return
      */
    def prepareFailedPlayerCreation(exception: Throwable): Unit =
      initPlayerFactoryMock(playerFactory, Future.failed(exception))

    /**
      * Creates a mock for the radio player. The mock handles the methods for
      * adding or removing playback context factories by updating the
      * corresponding map.
      *
      * @return the mock radio player
      */
    private def createPlayerMock(): RadioPlayer =
      val player = mock[RadioPlayer]
      doAnswer((invocation: InvocationOnMock) => {
        val pcf = invocation.getArguments.head.asInstanceOf[PlaybackContextFactory]
        playbackContextFactories.put(pcf, java.lang.Boolean.TRUE)
        null
      }).when(player).addPlaybackContextFactory(any(classOf[PlaybackContextFactory]))
      doAnswer((invocation: InvocationOnMock) => {
        val pcf = invocation.getArguments.head.asInstanceOf[PlaybackContextFactory]
        playbackContextFactories remove pcf
        null
      }).when(player).removePlaybackContextFactory(any(classOf[PlaybackContextFactory]))
      player

    /**
      * Creates a mock for the radio player factory. The factory returns the
      * mock player. It also checks the provided parameter.
      *
      * @param playerMock the mock player to be returned by the factory
      * @return the mock player factory
      */
    private def createPlayerFactory(playerMock: RadioPlayer): RadioPlayerFactory =
      val factory = mock[RadioPlayerFactory]
      initPlayerFactoryMock(factory, Future.successful(playerMock))
      factory

    /**
      * Prepares the mock for the [[RadioPlayerFactory]] for an invocation and
      * to return a specific result.
      *
      * @param factory       the factory mock to be initialized
      * @param factoryResult the result to be returned by the factory
      */
    private def initPlayerFactoryMock(factory: RadioPlayerFactory, factoryResult: Future[RadioPlayer]): Unit =
      when(factory.createRadioPlayer(any(classOf[ActorManagementComponent]))(any(), any()))
        .thenAnswer((invocation: InvocationOnMock) => {
          invocation.getArguments.head should be(app)
          factoryResult
        })

    /**
      * Creates an [[ActorFactory]]. This implementation uses the default
      * mechanism for creating actors, but ensures that names for typed actors
      * are unique.
      *
      * @return the [[ActorFactory]]
      */
    private def createActorFactory(): ActorFactory =
      new ActorFactory(system):
        override def createActor[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] =
          super.createActor(behavior, name + actorNameCounter.incrementAndGet(), props)

