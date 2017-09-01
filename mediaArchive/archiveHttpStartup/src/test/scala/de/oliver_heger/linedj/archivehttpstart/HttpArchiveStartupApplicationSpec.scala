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

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorSystem, Terminated}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates.HttpArchiveState
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.{ApplicationSyncStartup, ApplicationTestSupport, ClientApplicationContext, ClientApplicationContextImpl}
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.{MediaArchiveAvailabilityEvent, MediaFacadeActors}
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.window.Window
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.{Answer, OngoingStubbing}
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success, Try}

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
    * Generates a ''Failure'' object for a failed archive startup.
    *
    * @return the ''Failure''
    */
  private def failedArchiveCreation(): Try[Map[String, ActorRef]] =
    Try(throw new Exception("Failure!"))
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

  it should "create a default archive starter" in {
    val app = new HttpArchiveStartupApplication

    app.archiveStarter shouldBe a[HttpArchiveStarter]
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
    * Creates a map with actors as simulated result for the archive starter
    * object.
    *
    * @return the map with test actors
    */
  private def createActorsMap(): Map[String, ActorRef] =
    Map("actor1" -> TestProbe().ref, "actor2" -> TestProbe().ref)

  /**
    * Helper method which sends all data and messages required to create the
    * archive management actors.
    *
    * @param helper        the test helper
    * @param archiveActors a map with the actors to be returned by the starter
    * @return the test helper
    */
  private def triggerArchiveCreation(helper: StartupTestHelper,
                                     archiveActors: Try[Map[String, ActorRef]] =
                                     Success(createActorsMap())): StartupTestHelper = {
    helper.startupApplication()
      .initArchiveStartupResult(archiveActors)
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
      .sendOnMessageBus(LoginStateChanged(null, Some(ArchiveCredentials)))
      .processUIFuture()
  }

  /**
    * Calls methods on the given test helper to enter the archive available
    * state in the standard way.
    *
    * @param helper the test helper
    * @param archiveActors a map with the actors to be returned by the starter
    */
  private def enterArchiveAvailableState(helper: StartupTestHelper,
                                         archiveActors: Map[String, ActorRef]
                                         = createActorsMap()): Unit = {
    triggerArchiveCreation(helper, Success(archiveActors))
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateAvailable)
      .expectArchiveCreation()
  }

  it should "create the archive actor if all information is available" in {
    val helper = new StartupTestHelper

    enterArchiveAvailableState(helper)
  }

  it should "handle an invalid configuration when creating the archive actor" in {
    val helper = new StartupTestHelper

    triggerArchiveCreation(helper, failedArchiveCreation())
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
    helper.initArchiveStartupResult(Success(createActorsMap()))
    helper.configuration.setProperty(HttpArchiveStartupApplication.PropArchiveInitTimeout,
      InitTimeout)

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest(timeout = Timeout(InitTimeout.seconds))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
      .sendOnMessageBus(LoginStateChanged(null, Some(ArchiveCredentials)))
      .processUIFuture()
      .expectArchiveCreation()
  }

  it should "handle a failed future when requesting the facade actors" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest(actors = Promise.failed(new Exception))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
      .sendOnMessageBus(LoginStateChanged(null, Some(ArchiveCredentials)))
      .processUIFuture()
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNoUnionArchive)
  }

  it should "create the archive if information comes in different order" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .initArchiveStartupResult(Success(createActorsMap()))
      .prepareMediaFacadeActorsRequest()
      .sendOnMessageBus(LoginStateChanged(null, Some(ArchiveCredentials)))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .processUIFuture()
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateAvailable)
      .expectArchiveCreation()
  }

  it should "check preconditions again after facade actors have been fetched" in {
    val helper = new StartupTestHelper
    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendOnMessageBus(LoginStateChanged(null, Some(ArchiveCredentials)))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
    val uiFutureMsg = helper.messageBus.expectMessageType[Any]

    helper.sendAvailability(MediaFacade.MediaArchiveUnavailable)
    helper.messageBus.publishDirectly(uiFutureMsg)
    helper.messageBus.expectNoMessage()
  }

  it should "handle a logout message if the archive is not yet started" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendOnMessageBus(LoginStateChanged(null, Some(ArchiveCredentials)))
      .sendOnMessageBus(LoginStateChanged(null, None))
    helper.messageBus.expectNoMessage()
  }

  /**
    * Expects that an actor gets terminated.
    *
    * @param actor the actor to be checked
    */
  private def expectTermination(actor: ActorRef): Unit = {
    val probeWatch = TestProbe()
    probeWatch watch actor
    probeWatch.expectMsgType[Terminated]
  }

  it should "stop the archive actors if the user logs out" in {
    val helper = new StartupTestHelper
    val archiveActors = createActorsMap()
    enterArchiveAvailableState(helper, archiveActors)

    helper.sendOnMessageBus(LoginStateChanged(null, None))
      .expectArchiveStateNotification(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
    archiveActors.values foreach expectTermination
  }

  it should "not send a message if the archive state does not change" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveUnavailable)
    helper.messageBus.expectNoMessage()
  }

  it should "stop the archive actors on shutdown" in {
    val helper = new StartupTestHelper
    val archiveActors = createActorsMap()
    enterArchiveAvailableState(helper, archiveActors)

    helper.deactivateApplication()
    archiveActors.values foreach expectTermination
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

    helper.sendOnMessageBus(LoginStateChanged(null, Some(ArchiveCredentials)))
    helper.messageBus.expectNoMessage()
  }

  it should "setup the main window" in {
    val helper = new StartupTestHelper(skipUI = false)

    helper.startupApplication(handleAvailabilityReg = false)
    helper.queryBean[HttpArchiveLoginController](helper.app.getMainWindowBeanContext,
      "loginController")
  }

  /**
    * A test implementation of the startup application.
    *
    * @param starter the object for starting the archive
    * @param skipUI  flag whether UI creation is to be skipped
    */
  private class HttpArchiveStartupApplicationTestImpl(starter: HttpArchiveStarter,
                                                      skipUI: Boolean)
    extends HttpArchiveStartupApplication(starter) with ApplicationSyncStartup {
    override def initGUI(appCtx: ApplicationContext): Unit = {
      if (!skipUI) super.initGUI(appCtx)
    }

    /**
      * @inheritdoc Never shows the main window.
      */
    override def showMainWindow(window: Window): Unit = {}
  }

  /**
    * A test helper class managing a test instance and allowing access to it.
    *
    * @param skipUI flag whether UI creation is to be skipped
    */
  private class StartupTestHelper(skipUI: Boolean = true) extends ApplicationTestSupport {
    /** A test message bus. */
    val messageBus = new MessageBusTestImpl

    /** A mock for the actor factory. */
    val actorFactory: ActorFactory = mock[ActorFactory]

    /** Stores the registration of the app for the availability extension. */
    var availabilityRegistration: ArchiveAvailabilityRegistration = _

    /** A counter to keep track on archive startup operations. */
    private val archiveStartupCount = new AtomicInteger

    /** Test probe for the union media manager actor. */
    private val probeUnionMediaManager = TestProbe()

    /** Test probe for the union meta data manager actor. */
    private val probeUnionMetaManager = TestProbe()

    /** The configuration for the archive. */
    private val archiveConfig = createArchiveSourceConfig()

    /** Mock for the media facade. */
    private val mockFacade = mock[MediaFacade]

    /** Mock for the archive starter. */
    private val archiveStarter = mock[HttpArchiveStarter]

    /** The client application context. */
    private val clientApplicationContext = createTestClientApplicationContext()

    /** The application to be tested. */
    val app = new HttpArchiveStartupApplicationTestImpl(archiveStarter, skipUI)

    /**
      * Returns the configuration of the simulated application.
      *
      * @return the configuration
      */
    def configuration: Configuration = clientApplicationContext.managementConfiguration

    /**
      * Initializes and starts the test application.
      *
      * @param handleAvailabilityReg flag whether a registration is expected
      * @return this test helper
      */
    def startupApplication(handleAvailabilityReg: Boolean = true): StartupTestHelper = {
      activateApp(app)
      if (handleAvailabilityReg) {
        availabilityRegistration = messageBus.expectMessageType[ArchiveAvailabilityRegistration]
      }
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
      * Prepares the mock startup object for a startup operation and defines
      * the map of actors to be returned. With this method successful and
      * failed archive creations can be prepared.
      *
      * @param actors a ''Try'' with the map of actors to be returned by the
      *               mock
      * @return this test helper
      */
    def initArchiveStartupResult(actors: Try[Map[String, ActorRef]]): StartupTestHelper = {
      actors match {
        case Success(_) =>
          expectStarterInvocation().thenAnswer(new Answer[Try[Map[String, ActorRef]]] {
            override def answer(invocation: InvocationOnMock): Try[Map[String, ActorRef]] = {
              archiveStartupCount.incrementAndGet()
              actors
            }
          })
        case f@Failure(_) =>
          expectStarterInvocation().thenReturn(f)
      }
      this
    }

    /**
      * Expects that the HTTP archive has been created.
      *
      * @return this test helper
      */
    def expectArchiveCreation(): StartupTestHelper = {
      awaitCond(archiveStartupCount.get() == 1)
      this
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
        override val actorFactory: ActorFactory = StartupTestHelper.this.actorFactory
        override val managementConfiguration: Configuration = archiveConfig
        override val mediaFacade: MediaFacade = mockFacade
      }
    }

    /**
      * Helper method to set expectations for the starter mock. This can be
      * used to prepare the mock to answer a startup request.
      * @return the stubbing object to define the behavior of the startup
      *         method
      */
    private def expectStarterInvocation(): OngoingStubbing[Try[Map[String, ActorRef]]] =
      when(archiveStarter.startup(MediaFacadeActors(probeUnionMediaManager.ref,
        probeUnionMetaManager.ref), archiveConfig, "media.http", ArchiveCredentials,
        actorFactory))
  }

}
