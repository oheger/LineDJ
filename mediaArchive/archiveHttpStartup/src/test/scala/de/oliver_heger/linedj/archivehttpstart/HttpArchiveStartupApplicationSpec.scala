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

package de.oliver_heger.linedj.archivehttpstart

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, Terminated}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.HttpArchiveManagementActor
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates.HttpArchiveState
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.{ApplicationSyncStartup, ApplicationTestSupport,
ClientApplicationContext, ClientApplicationContextImpl}
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.{MediaArchiveAvailabilityEvent,
MediaFacadeActors}
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension
.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.mockito.Mockito._
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._

object HttpArchiveStartupApplicationSpec {
  /** The URL to the test HTTP archive. */
  private val ArchiveURL = "https://my.test.music.archive.la/content.json"

  /** Test user name. */
  private val UserName = "scott"

  /** Test password. */
  private val Password = "tiger"

  /** Test credentials for the archive. */
  private val ArchiveCredentials = UserCredentials(UserName, Password)

  /**
    * Creates a configuration object with the properties defining the test
    * HTTP archive.
    *
    * @return the configuration
    */
  private def createArchiveSourceConfig(): Configuration = {
    val props = new PropertiesConfiguration
    props.addProperty(HttpArchiveConfig.PropArchiveUri, ArchiveURL)
    props
  }

  /**
    * Creates a config for the test HTTP archive.
    *
    * @return the archive config
    */
  private def createArchiveConfig(): HttpArchiveConfig =
    HttpArchiveConfig(createArchiveSourceConfig(), ArchiveCredentials).get
}

/**
  * Test class for ''HttpArchiveStartupApplication''.
  */
class HttpArchiveStartupApplicationSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import HttpArchiveStartupApplicationSpec._

  def this() = this(ActorSystem("HttpArchiveStartupApplicationSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An ArchiveAdminApp" should "construct an instance correctly" in {
    val app = new HttpArchiveStartupApplication

    app shouldBe a[HttpArchiveStartupApplication]
    app.appName should be("httpArchiveStartup")
  }

  it should "register itself for the archive available extension" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
    helper.availabilityRegistration.id should be(helper.app.componentID)
  }

  it should "remove the availability registration on deactivation" in {
    val helper = new StartupTestHelper

    helper.startupApplication().deactivateApplication()
    val unRegistration = helper.messageBus.expectMessageType[ArchiveAvailabilityUnregistration]
    unRegistration.id should be(helper.app.componentID)
  }

  it should "send the current archive state when requested to do so" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendOnMessageBus(HttpArchiveStateRequest)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNoUnionArchive)
  }

  it should "remove the message bus registration on deactivation" in {
    val helper = new StartupTestHelper

    helper.startupApplication().deactivateApplication()
    helper.messageBus.busListeners should have size 0
  }

  it should "handle an archive available notification" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
  }

  it should "send the updated archive state when requested to do so" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
      .sendOnMessageBus(HttpArchiveStateRequest)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
  }

  /**
    * Helper method which sends all data and messages required to create the
    * archive management actor.
    *
    * @param helper the test helper
    * @return the test helper
    */
  private def triggerActorCreation(helper: StartupTestHelper): StartupTestHelper = {
    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
      .sendOnMessageBus(LoginStateChanged(Some(ArchiveCredentials)))
      .processUIFuture()
  }

  /**
    * Calls methods on the given test helper to enter the archive available
    * state in the standard way.
    *
    * @param helper the test helper
    * @return the test probe for the management actor
    */
  private def enterArchiveAvailableState(helper: StartupTestHelper): TestProbe = {
    triggerActorCreation(helper)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateAvailable)
      .expectActorCreation()
  }

  it should "create the archive actor if all information is available" in {
    val helper = new StartupTestHelper

    enterArchiveAvailableState(helper)
  }

  it should "handle an invalid configuration when creating the archive actor" in {
    val helper = new StartupTestHelper
    helper.configuration.clearProperty(HttpArchiveConfig.PropArchiveUri)

    triggerActorCreation(helper)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateInvalidConfig)
  }

  it should "record a new archive state so that it can be queried later" in {
    val helper = new StartupTestHelper

    enterArchiveAvailableState(helper)
    helper.sendOnMessageBus(HttpArchiveStateRequest)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateAvailable)
  }

  it should "take a timeout from the configuration into account" in {
    val InitTimeout = 28
    val helper = new StartupTestHelper
    helper.configuration.setProperty(HttpArchiveStartupApplication.PropArchiveInitTimeout,
      InitTimeout)

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest(timeout = Timeout(InitTimeout.seconds))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
      .sendOnMessageBus(LoginStateChanged(Some(ArchiveCredentials)))
      .processUIFuture()
      .expectActorCreation()
  }

  it should "handle a failed future when requesting the facade actors" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest(actors = Promise.failed(new Exception))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
      .sendOnMessageBus(LoginStateChanged(Some(ArchiveCredentials)))
      .processUIFuture()
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNoUnionArchive)
  }

  it should "create the archive actor if information comes in different order" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendOnMessageBus(LoginStateChanged(Some(ArchiveCredentials)))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateAvailable)
      .expectActorCreation()
  }

  it should "trigger a scan after starting the management actor" in {
    val helper = new StartupTestHelper

    triggerActorCreation(helper)
      .expectActorCreation().expectMsg(ScanAllMedia)
  }

  it should "check preconditions again after facade actors have been fetched" in {
    val helper = new StartupTestHelper
    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendOnMessageBus(LoginStateChanged(Some(ArchiveCredentials)))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
    val uiFutureMsg = helper.messageBus.expectMessageType[Any]

    helper.sendAvailability(MediaFacade.MediaArchiveUnavailable)
    helper.messageBus.publishDirectly(uiFutureMsg)
    helper.messageBus.expectNoMessage()
  }

  it should "handle a logout message if the archive is not yet started" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendOnMessageBus(LoginStateChanged(Some(ArchiveCredentials)))
      .sendOnMessageBus(LoginStateChanged(None))
    helper.messageBus.expectNoMessage()
  }

  /**
    * Expects that an actor (represented by a test probe) gets terminated.
    *
    * @param probe the test probe
    */
  private def expectTermination(probe: TestProbe): Unit = {
    val probeWatch = TestProbe()
    probeWatch watch probe.ref
    probeWatch.expectMsgType[Terminated]
  }

  it should "stop the archive actor if the user logs out" in {
    val helper = new StartupTestHelper
    val probeManagementActor = enterArchiveAvailableState(helper)

    helper.sendOnMessageBus(LoginStateChanged(None))
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
    expectTermination(probeManagementActor)
  }

  it should "not send a message if the archive state does not change" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveUnavailable)
    helper.messageBus.expectNoMessage()
  }

  it should "stop the archive actor on shutdown" in {
    val helper = new StartupTestHelper
    val probeManagementActor = enterArchiveAvailableState(helper)

    helper.deactivateApplication()
    expectTermination(probeManagementActor)
  }

  it should "ignore a duplicate availability message" in {
    val helper = new StartupTestHelper
    enterArchiveAvailableState(helper)

    helper.sendAvailability(MediaFacade.MediaArchiveAvailable)
    helper.messageBus.expectNoMessage()
  }

  it should "ignore a duplicate credentials message" in {
    val helper = new StartupTestHelper
    enterArchiveAvailableState(helper)

    helper.sendOnMessageBus(LoginStateChanged(Some(ArchiveCredentials)))
    helper.messageBus.expectNoMessage()
  }

  /**
    * A test implementation of the startup application.
    */
  private class HttpArchiveStartupApplicationTestImpl
    extends HttpArchiveStartupApplication with ApplicationSyncStartup {
    override def initGUI(appCtx: ApplicationContext): Unit = {}
  }

  /**
    * A test helper class managing a test instance and allowing access to it.
    */
  private class StartupTestHelper extends ApplicationTestSupport {
    /** A test message bus. */
    val messageBus = new MessageBusTestImpl

    /** The application to be tested. */
    val app = new HttpArchiveStartupApplicationTestImpl

    /** Stores the registration of the app for the availability extension. */
    var availabilityRegistration: ArchiveAvailabilityRegistration = _

    /** A queue which stores actors created by the actor factory. */
    private val actorsQueue = new LinkedBlockingQueue[TestProbe]

    /** Test probe for the union media manager actor. */
    private val probeUnionMediaManager = TestProbe()

    /** Test probe for the union meta data manager actor. */
    private val probeUnionMetaManager = TestProbe()

    /** Mock for the media facade. */
    private val mockFacade = mock[MediaFacade]

    /** The client application context. */
    private val clientApplicationContext = createTestClientApplicationContext()

    /**
      * Returns the configuration of the simulated application.
      *
      * @return the configuration
      */
    def configuration: Configuration = clientApplicationContext.managementConfiguration

    /**
      * Initializes and starts the test application.
      *
      * @return this test helper
      */
    def startupApplication(): StartupTestHelper = {
      activateApp(app)
      availabilityRegistration = messageBus.expectMessageType[ArchiveAvailabilityRegistration]
      this
    }

    /**
      * Simulates a deactivation of the test application.
      *
      * @return this test helper
      */
    def deactivateApplication(): StartupTestHelper = {
      app deactivate mock[ComponentContext]
      this
    }

    /**
      * Directly publishes the specified message on the bus.
      *
      * @param msg the message
      * @return this test helper
      */
    def sendOnMessageBus(msg: Any): StartupTestHelper = {
      messageBus publishDirectly msg
      this
    }

    /**
      * Expects that an archive state changed notification was sent on the
      * message bus.
      *
      * @param state the expected state
      * @return this test helper
      */
    def expectArchiveStateNotification(state: HttpArchiveState): StartupTestHelper = {
      val stateMsg = messageBus.expectMessageType[HttpArchiveState]
      stateMsg should be(state)
      this
    }

    /**
      * Sends the specified availability event to the test application.
      *
      * @param av the availability event
      * @return this test helper
      */
    def sendAvailability(av: MediaArchiveAvailabilityEvent): StartupTestHelper = {
      availabilityRegistration.callback(av)
      this
    }

    /**
      * Expects that the actor for archive management has been created.
      *
      * @return the test probe for the management actor
      */
    def expectActorCreation(): TestProbe = {
      val probe = actorsQueue.poll(3, TimeUnit.SECONDS)
      probe should not be null
      probe
    }

    /**
      * Prepares the mock for the media facade to expect a request for the
      * actors of the union archive.
      *
      * @param actors  a promise for the actors to be returned
      * @param timeout a timeout for the initialization
      * @return this test helper
      */
    def prepareMediaFacadeActorsRequest(actors: Promise[MediaFacadeActors] =
                                        Promise.successful(MediaFacadeActors(
                                          probeUnionMediaManager.ref,
                                          probeUnionMetaManager.ref)), timeout: Timeout = 10
      .seconds): StartupTestHelper = {
      when(mockFacade.requestFacadeActors()(argEq(timeout), any(classOf[ExecutionContext])))
        .thenReturn(actors.future)
      this
    }

    /**
      * Expects a message for processing a future in the UI thread on the
      * message bus and delivers it.
      *
      * @return this test heper
      */
    def processUIFuture(): StartupTestHelper = {
      messageBus.processNextMessage[Any]
      this
    }

    /**
      * @inheritdoc This implementation returns the test client application
      *             context that has already been created initially by this
      *             test helper.
      */
    override def createClientApplicationContext(): ClientApplicationContext =
      clientApplicationContext

    /**
      * Creates a client application context for the test application.
      *
      * @return the context
      */
    private def createTestClientApplicationContext(): ClientApplicationContext = {
      val testMsgBus = messageBus
      new ClientApplicationContextImpl {
        override val actorSystem: ActorSystem = system
        override val messageBus: MessageBus = testMsgBus
        override val actorFactory: ActorFactory = createActorFactory()
        override val managementConfiguration: Configuration = createArchiveSourceConfig()
        override val mediaFacade: MediaFacade = mockFacade
      }
    }

    /**
      * Creates a stub actor factory for creating the HTTP archive manager
      * actor.
      *
      * @return the actor factory
      */
    private def createActorFactory(): ActorFactory = {
      val refProps = HttpArchiveManagementActor(createArchiveConfig(),
        probeUnionMediaManager.ref, probeUnionMetaManager.ref)
      val factory = mock[ActorFactory]
      when(factory.createActor(refProps, "httpArchiveManagementActor"))
        .thenAnswer(new Answer[ActorRef] {
          override def answer(invocation: InvocationOnMock): ActorRef = {
            val probe = TestProbe()
            actorsQueue offer probe
            probe.ref
          }
        })
      factory
    }
  }

}
