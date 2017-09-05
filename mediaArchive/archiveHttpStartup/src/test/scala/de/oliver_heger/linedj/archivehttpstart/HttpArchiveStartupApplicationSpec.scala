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

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.{HttpArchiveState => _, _}
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates._
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.{ApplicationSyncStartup, ApplicationTestSupport, ClientApplicationContext, ClientApplicationContextImpl}
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.{MediaArchiveAvailabilityEvent, MediaFacadeActors}
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.window.Window
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.{Answer, OngoingStubbing}
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}

object HttpArchiveStartupApplicationSpec {
  /** Test user name. */
  private val UserName = "scott"

  /** Test password. */
  private val Password = "tiger"

  /**
    * Generates an actor name for a test archive.
    *
    * @param archiveIdx the index of the test archive
    * @param suffix     the suffix of the actor name
    * @return the resulting actor name
    */
  private def actorName(archiveIdx: Int, suffix: String): String =
    StartupConfigTestHelper.shortName(archiveIdx) + '_' + suffix

  /**
    * Generates a credentials object for the realm with the given index.
    *
    * @param realmIdx the realm index
    * @return the credentials for this realm
    */
  private def credentials(realmIdx: Int): UserCredentials =
    UserCredentials(UserName + realmIdx, Password + realmIdx)

  /**
    * Generates a state changed notification for a test archive.
    *
    * @param archiveIdx the index of the archive
    * @param state      the new state
    * @return the corresponding notification
    */
  private def stateNotification(archiveIdx: Int, state: HttpArchiveState):
  HttpArchiveStateChanged =
    HttpArchiveStateChanged(StartupConfigTestHelper.archiveName(archiveIdx), state)

  /**
    * Creates a configuration object with the properties defining the test
    * HTTP archives. The configuration defines 3 archives with the indices from
    * 1 to 3. The first and the last archive belong to the same realm.
    *
    * @return the configuration
    */
  private def createArchiveSourceConfig(): Configuration = {
    StartupConfigTestHelper.addArchiveToConfig(
      StartupConfigTestHelper.addArchiveToConfig(
        StartupConfigTestHelper.addArchiveToConfig(
          new HierarchicalConfiguration, 3, Some(StartupConfigTestHelper.realmName(1))),
        2),
      1)
  }
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
      .expectArchiveStateNotifications(HttpArchiveStateNoUnionArchive, 1, 2, 3)
  }

  it should "remove the message bus registration on deactivation" in {
    val helper = new StartupTestHelper

    helper.startupApplication().deactivateApplication()
    helper.messageBus.busListeners should have size 0
  }

  it should "handle a union archive available notification" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3)
  }

  it should "send the updated archive state when requested to do so" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3)
      .sendOnMessageBus(HttpArchiveStateRequest)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3)
  }

  /**
    * Creates a map with actors as simulated result for the archive starter
    * object.
    *
    * @param idx     the index of the archive
    * @param manager an optional ref to the archive manager actor
    * @return the map with test actors
    */
  private def createActorsMap(idx: Int, manager: Option[ActorRef] = None): Map[String, ActorRef] =
    Map(actorName(idx, HttpArchiveStarter.DownloadMonitoringActorName) -> TestProbe().ref,
      actorName(idx, HttpArchiveStarter.RemoveFileActorName) -> TestProbe().ref,
      actorName(idx, HttpArchiveStarter.ManagementActorName) -> manager.getOrElse(TestProbe().ref))

  /**
    * Helper method which sends all data and messages required to create the
    * actors for the archives belonging to a specific realm. It is expected
    * that the archive starter has already been prepared.
    *
    * @param helper   the test helper
    * @param realmIdx the index of the test realm
    * @return the test helper
    */
  private def triggerArchiveCreation(helper: StartupTestHelper, realmIdx: Int):
  StartupTestHelper = {
    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3)
      .sendLoginForRealm(realmIdx)
      .processUIFuture()
  }

  /**
    * Calls methods on the given test helper to enter the archive available
    * state for the archives belonging to realm 1 in the standard way.
    *
    * @param helper the test helper
    */
  private def enterArchiveAvailableState(helper: StartupTestHelper): Unit = {
    triggerArchiveCreation(helper, 1)
      .expectArchiveStateNotifications(HttpArchiveStateInitializing, 1, 3)
      .expectArchiveCreation(archiveCount = 2)
  }

  /**
    * Prepares a login operation into the realm which owns multiple HTTP
    * archives. It is possible to specify a special management actor
    * implementation for the first HTTP archive.
    *
    * @param helper  the test helper
    * @param manager a way to inject a special management actor
    * @return a map with all test actors
    */
  private def prepareMultiRealmLogin(helper: StartupTestHelper,
                                     manager: Option[ActorRef] = None):
  Map[String, ActorRef] = {
    val archiveActors1 = createActorsMap(1, manager)
    val archiveActors2 = createActorsMap(3)
    helper.initArchiveStartupResult(archiveActors1, 1, 1)
      .initArchiveStartupResult(archiveActors2, 3, 1)
    archiveActors1 ++ archiveActors2
  }

  it should "create the archive actors if all information is available" in {
    val helper = new StartupTestHelper
    prepareMultiRealmLogin(helper)

    enterArchiveAvailableState(helper)
  }

  it should "record a new archive state so that it can be queried later" in {
    val helper = new StartupTestHelper
    prepareMultiRealmLogin(helper)

    enterArchiveAvailableState(helper)
    helper.sendOnMessageBus(HttpArchiveStateRequest)
      .expectArchiveStateNotification(stateNotification(1, HttpArchiveStateInitializing))
      .expectArchiveStateNotification(stateNotification(2, HttpArchiveStateNotLoggedIn))
      .expectArchiveStateNotification(stateNotification(3, HttpArchiveStateInitializing))
  }

  it should "take an initialization timeout from the configuration into account" in {
    val InitTimeout = 28
    val helper = new StartupTestHelper
    helper.initArchiveStartupResult(createActorsMap(2), 2, 2)
    helper.configuration.setProperty(HttpArchiveStartupApplication.PropArchiveInitTimeout,
      InitTimeout)

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest(timeout = Timeout(InitTimeout.seconds))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3)
      .sendLoginForRealm(2)
      .processUIFuture()
      .expectArchiveCreation()
  }

  it should "handle a failed future when requesting the facade actors" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest(actors = Promise.failed(new Exception))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3)
      .sendLoginForRealm(2)
      .processUIFuture()
      .expectArchiveStateNotifications(HttpArchiveStateNoUnionArchive, 1, 2, 3)
  }

  it should "create the archive if information comes in different order" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .initArchiveStartupResult(createActorsMap(2), 2, 2)
      .prepareMediaFacadeActorsRequest()
      .sendLoginForRealm(2)
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(stateNotification(1, HttpArchiveStateNotLoggedIn))
      .expectArchiveStateNotification(stateNotification(3, HttpArchiveStateNotLoggedIn))
      .processUIFuture()
      .expectArchiveStateNotification(stateNotification(2, HttpArchiveStateInitializing))
      .expectArchiveCreation()
  }

  it should "check preconditions again after facade actors have been fetched" in {
    val helper = new StartupTestHelper
    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendLoginForRealm(2)
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(stateNotification(1, HttpArchiveStateNotLoggedIn))
      .expectArchiveStateNotification(stateNotification(3, HttpArchiveStateNotLoggedIn))
    val uiFutureMsg = helper.messageBus.expectMessageType[Any]

    helper.sendAvailability(MediaFacade.MediaArchiveUnavailable)
      .expectArchiveStateNotifications(HttpArchiveStateNoUnionArchive, 1, 2, 3)
    helper.messageBus.publishDirectly(uiFutureMsg)
    helper.messageBus.expectNoMessage()
  }

  it should "handle a logout message if the archive is not yet started" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendLoginForRealm(2)
      .sendOnMessageBus(LoginStateChanged(StartupConfigTestHelper.realmName(2), None))
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
    val archiveActors = prepareMultiRealmLogin(helper)
    enterArchiveAvailableState(helper)

    helper.sendOnMessageBus(LoginStateChanged(StartupConfigTestHelper.realmName(1), None))
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 3)
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
    val archiveActors = prepareMultiRealmLogin(helper)
    enterArchiveAvailableState(helper)

    helper.deactivateApplication()
    archiveActors.values foreach expectTermination
  }

  it should "ignore a duplicate availability message" in {
    val helper = new StartupTestHelper
    prepareMultiRealmLogin(helper)
    enterArchiveAvailableState(helper)

    helper.sendAvailability(MediaFacade.MediaArchiveAvailable)
    helper.messageBus.expectNoMessage()
  }

  it should "ignore a duplicate credentials message" in {
    val helper = new StartupTestHelper
    prepareMultiRealmLogin(helper)
    enterArchiveAvailableState(helper)

    helper.sendLoginForRealm(1)
    helper.messageBus.expectNoMessage()
  }

  /**
    * Creates a dummy management actor that returns the specified archive
    * state.
    *
    * @param state the archive state
    * @return the dummy management actor
    */
  private def createStateActor(state: de.oliver_heger.linedj.archivehttp.HttpArchiveState):
  ActorRef =
    system.actorOf(Props(classOf[ArchiveStateActor],
      HttpArchiveStateResponse(StartupConfigTestHelper.archiveName(1), state)))

  /**
    * Checks a notification sent via the message bus after the HTTP archive
    * has been started. This notification is published when the response of a
    * state request for the archive management actor comes in.
    *
    * @param archiveState         the state of the HTTP archive
    * @param expNotificationState the state expected in the notification
    */
  private def checkArchiveStartupNotification(archiveState: de.oliver_heger.linedj.archivehttp
                                               .HttpArchiveState,
                                               expNotificationState: HttpArchiveState): Unit = {
    val helper = new StartupTestHelper
    prepareMultiRealmLogin(helper, Some(createStateActor(archiveState)))
    triggerArchiveCreation(helper, 1)
    val msg = helper.messageBus.processNextMessage[HttpArchiveStateChanged]
    msg should be(stateNotification(1, HttpArchiveStateInitializing))
    helper.expectArchiveStateNotification(stateNotification(3, HttpArchiveStateInitializing))

    helper.expectArchiveStateNotification(stateNotification(1, expNotificationState))
  }

  it should "send a notification about a successfully started archive" in {
    checkArchiveStartupNotification(HttpArchiveStateConnected, HttpArchiveStateAvailable)
  }

  it should "send a notification about an archive started with a server error" in {
    val errState = HttpArchiveStateServerError(new Exception("Archive error!"))
    val expState = HttpArchiveErrorState(errState)

    checkArchiveStartupNotification(errState, expState)
  }

  it should "send a notification about an archive started with a failed response" in {
    val failedState = HttpArchiveStateFailedRequest(StatusCodes.Unauthorized)
    val expState = HttpArchiveErrorState(failedState)

    checkArchiveStartupNotification(failedState, expState)
  }

  it should "send a notification about an archive started in an unexpected state" in {
    val expState = HttpArchiveErrorState(HttpArchiveStateDisconnected)

    checkArchiveStartupNotification(HttpArchiveStateDisconnected, expState)
  }

  it should "handle a timeout when querying the state of an archive" in {
    val helper = new StartupTestHelper
    helper.configuration.setProperty(
      HttpArchiveStartupApplication.PropArchiveStateRequestTimeout, 1)
    prepareMultiRealmLogin(helper)
    triggerArchiveCreation(helper, 1)
    val initNotification = stateNotification(1, HttpArchiveStateInitializing)
    helper.expectArchiveStateNotifications(HttpArchiveStateInitializing, 1, 3)
      .sendOnMessageBus(initNotification)

    helper.expectArchiveStateNotification(initNotification)
  }

  it should "not crash when querying the archive state due to an unknown actor" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendOnMessageBus(stateNotification(1, HttpArchiveStateInitializing))
  }

  it should "store the config manager as bean in the bean context" in {
    val helper = new StartupTestHelper()

    helper.startupApplication()
    val configManager = helper.app.getApplicationContext.getBeanContext
      .getBean(HttpArchiveStartupApplication.BeanConfigManager)
      .asInstanceOf[HttpArchiveConfigManager]

    configManager.archives should have size 3
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

    /** The object storing configuration data for archives. */
    private val archiveConfigManager = HttpArchiveConfigManager(archiveConfig)

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
    def expectArchiveStateNotification(state: HttpArchiveStateChanged): StartupTestHelper = {
      val stateMsg = messageBus.expectMessageType[HttpArchiveStateChanged]
      stateMsg should be(state)
      this
    }

    /**
      * Expects state changed notifications for multiple archives.
      *
      * @param state    the expected target state
      * @param archives the indices of the archives affected
      * @return this test helper
      */
    def expectArchiveStateNotifications(state: HttpArchiveState, archives: Int*):
    StartupTestHelper = {
      archives foreach { i =>
        expectArchiveStateNotification(stateNotification(i, state))
      }
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
      * Sends a login message for the specified realm.
      *
      * @param realmIdx the realm index
      * @return this test helper
      */
    def sendLoginForRealm(realmIdx: Int): StartupTestHelper =
      sendOnMessageBus(LoginStateChanged(StartupConfigTestHelper.realmName(realmIdx),
        Some(credentials(realmIdx))))

    /**
      * Prepares the mock startup object for a startup operation and defines
      * the map of actors to be returned. With this method successful and
      * failed archive creations can be prepared.
      *
      * @param actors     the map of actors to be returned by the mock
      * @param archiveIdx the index of the test archive
      * @param realmIdx   the index of the test realm
      * @return this test helper
      */
    def initArchiveStartupResult(actors: Map[String, ActorRef], archiveIdx: Int,
                                 realmIdx: Int): StartupTestHelper = {
      expectStarterInvocation(archiveIdx, realmIdx)
        .thenAnswer(new Answer[Map[String, ActorRef]] {
          override def answer(invocation: InvocationOnMock): Map[String, ActorRef] = {
            archiveStartupCount.incrementAndGet()
            actors
          }
        })
      this
    }

    /**
      * Expects that the given number of HTTP archives has been created.
      *
      * @param archiveCount the number of archives that are to be created
      * @return this test helper
      */
    def expectArchiveCreation(archiveCount: Int = 1): StartupTestHelper = {
      awaitCond(archiveStartupCount.get() == archiveCount)
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
      * @return this test helper
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
      *
      * @param archiveIdx the index of the test archive
      * @param realmIdx   the index of the test realm
      * @return the stubbing object to define the behavior of the startup
      *         method
      */
    private def expectStarterInvocation(archiveIdx: Int, realmIdx: Int):
    OngoingStubbing[Map[String, ActorRef]] = {
      val archiveData = archiveConfigManager.archives(
        StartupConfigTestHelper.archiveName(archiveIdx))
      val creds = credentials(realmIdx)
      when(archiveStarter.startup(MediaFacadeActors(probeUnionMediaManager.ref,
        probeUnionMetaManager.ref), archiveData, archiveConfig, creds, actorFactory))
    }
  }

}

/**
  * An actor simulating the archive management actor for queries of the archive
  * state. The actor reacts on state requests and answers with a pre-defined
  * state response.
  *
  * @param stateResponse the response message for a state request
  */
class ArchiveStateActor(stateResponse: HttpArchiveStateResponse) extends Actor {
  override def receive: Receive = {
    case HttpArchiveStateRequest =>
      sender ! stateResponse
  }
}
