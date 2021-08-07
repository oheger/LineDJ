/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.factory.HttpRequestSenderFactoryImpl
import de.oliver_heger.linedj.archivehttp.{HttpArchiveStateConnected, HttpArchiveStateDisconnected, HttpArchiveStateFailedRequest, HttpArchiveStateResponse, HttpArchiveStateServerError}
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.io.MediaDownloader
import de.oliver_heger.linedj.archivehttp.io.oauth.OAuthStorageServiceImpl
import de.oliver_heger.linedj.archivehttpstart.app.HttpArchiveStates._
import de.oliver_heger.linedj.archivehttpstart.spi.HttpArchiveProtocolSpec.GenericHttpArchiveProtocolSpec
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app._
import de.oliver_heger.linedj.platform.bus.MessageBusRegistration
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.{MediaArchiveAvailabilityEvent, MediaFacadeActors}
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.di.impl.DefaultBeanStore
import net.sf.jguiraffe.di.impl.providers.ConstantBeanProvider
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.mockito.Matchers.{any, anyBoolean, anyInt, anyString, argThat, eq => argEq}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.OngoingStubbing
import org.mockito.{ArgumentCaptor, ArgumentMatcher}
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.security.Key
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

object HttpArchiveStartupApplicationSpec {
  /** Test user name. */
  private val UserName = "scott"

  /** Test password. */
  private val Password = "tiger"

  /** An index added to actor names. */
  private val ArcIndex = 11

  /** The index for the archive that is encrypted. */
  private val EncArchiveIndex = 4

  /** The index of the archive that uses a different protocol. */
  private val ProtocolArchiveIndex = 5

  /** The name of a special protocol used by an archive. */
  private val SpecialProtocol = "nonStandardHttpArchiveProtocol"

  /** The name of the actor for sending HTTP requests. */
  private val HttpActorName = "theHttpSenderActor"

  /**
    * Generates an actor name for a test archive.
    *
    * @param archiveIdx the index of the test archive
    * @param suffix     the suffix of the actor name
    * @return the resulting actor name
    */
  private def actorName(archiveIdx: Int, suffix: String): String =
    StartupConfigTestHelper.shortName(archiveIdx) + ArcIndex + '_' + suffix

  /**
    * Generates a credentials object for the realm with the given index.
    *
    * @param realmIdx the realm index
    * @return the credentials for this realm
    */
  private def credentials(realmIdx: Int): UserCredentials =
    UserCredentials(UserName + realmIdx, Secret(Password + realmIdx))

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
    * HTTP archives. The configuration defines 5 archives with the indices from
    * 1 to 5. The first and the 4th archive belong to the same realm; the 4th
    * one is encrypted; the 5th uses a different protocol.
    *
    * @return the configuration
    */
  private def createArchiveSourceConfig(): Configuration = {
    StartupConfigTestHelper.addArchiveToConfig(
      StartupConfigTestHelper.addArchiveToConfig(
        StartupConfigTestHelper.addArchiveToConfig(
          StartupConfigTestHelper.addArchiveToConfig(
            StartupConfigTestHelper.addArchiveToConfig(new HierarchicalConfiguration(), 5,
              protocol = Some(SpecialProtocol)),
            4, encrypted = true),
          3, Some(StartupConfigTestHelper.realmName(1))),
        2),
      1)
  }

  /**
    * Updates the names of the actors in the given resources to actor names
    * derived from the given index.
    *
    * @param resources the archive resources
    * @param index     the archive index
    * @return the resources with adapted actor keys
    */
  private def adaptActorNames(resources: HttpArchiveStarter.ArchiveResources, index: Int):
  HttpArchiveStarter.ArchiveResources = {
    val modifiedActors = resources.actors.map(e => (e._1.replace(ArcIndex + "_", index + "_"), e._2))
    resources.copy(actors = modifiedActors)
  }

  /**
    * Returns a custom matcher for ''UserCredentials''.
    *
    * @param credentials the expected credentials
    * @return the matcher for these credentials
    */
  private def credentialsEq(credentials: UserCredentials): UserCredentials =
    argThat(new CredentialsMatcher(credentials))
}

/**
  * Test class for ''HttpArchiveStartupApplication''.
  */
class HttpArchiveStartupApplicationSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with ApplicationTestSupport {

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
    val helper = new StartupTestHelper(mockStarter = false)

    val starter = helper.startupApplication()
      .queryBean[HttpArchiveStarter](helper.app, HttpArchiveStartupApplication.BeanArchiveStarter)
    starter.downloaderFactory match {
      case f: FileSystemMediaDownloaderFactory =>
        f.requestSenderFactory should be(HttpRequestSenderFactoryImpl)
      case f => fail("Unexpected downloader factory: " + f)
    }
    starter.authConfigFactory.oauthStorageService should be(OAuthStorageServiceImpl)
  }

  it should "register itself as message bus listener as early as possible" in {
    val context = mock[ClientApplicationContext]
    val bus = new MessageBusTestImpl
    when(context.messageBus).thenReturn(bus)
    val app = new HttpArchiveStartupApplication

    app initClientContext context
    bus.currentListeners should have size 1
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
    val helper = new StartupTestHelper(skipUI = true)

    helper.startupApplication()
      .deactivateApplication()
    helper.messageBus.busListeners should have size 0
  }

  it should "handle a union archive available notification" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
  }

  it should "send the updated archive state when requested to do so" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendOnMessageBus(HttpArchiveStateRequest)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
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
    * Creates an ''ArchiveResources'' object as simulated result for the
    * archive starter object.
    *
    * @param idx     the index of the archive
    * @param manager an optional ref to the archive manager actor
    * @return the ''ArchiveResources'' based on these parameters
    */
  private def createArchiveResources(idx: Int, manager: Option[ActorRef] = None): HttpArchiveStarter.ArchiveResources =
    HttpArchiveStarter.ArchiveResources(createActorsMap(idx, manager), mock[MediaDownloader], HttpActorName + idx)

  /**
    * Helper method which sends all data and messages required to create the
    * actors for the archives belonging to the test realm. It is expected
    * that the archive starter has already been prepared.
    *
    * @param helper the test helper
    * @return the test helper
    */
  private def triggerArchiveCreation(helper: StartupTestHelper): StartupTestHelper = {
    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(1)
  }

  /**
    * Calls methods on the given test helper to enter the archive available
    * state for the archives belonging to realm 1 in the standard way.
    *
    * @param helper the test helper
    */
  private def enterArchiveAvailableState(helper: StartupTestHelper): Unit = {
    triggerArchiveCreation(helper)
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
    * @return a list with the resources returned by the mock archive starter
    */
  private def prepareMultiRealmLogin(helper: StartupTestHelper,
                                     manager: Option[ActorRef] = None):
  List[HttpArchiveStarter.ArchiveResources] = {
    val resources1 = createArchiveResources(1, manager)
    val resources2 = createArchiveResources(3)
    helper.initArchiveStartupResult(resources1, 1, 1)
      .initArchiveStartupResult(resources2, 3, 1)
    List(resources1, resources2)
  }

  /**
    * Creates a mock for a protocol spec. The mock is configured to return the
    * name of the protocol.
    *
    * @param name the protocol name
    * @return the mock for the protocol spec
    */
  private def createProtocolSpecMock(name: String): GenericHttpArchiveProtocolSpec = {
    val protocol = mock[GenericHttpArchiveProtocolSpec]
    when(protocol.name).thenReturn(name)
    protocol
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
    helper.initArchiveStartupResult(createArchiveResources(2), 2, 2)
    helper.configuration.setProperty(HttpArchiveStartupApplication.PropArchiveInitTimeout,
      InitTimeout)

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest(timeout = Timeout(InitTimeout.seconds))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(2)
      .expectArchiveCreation()
  }

  it should "handle a failed future when requesting the facade actors" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest(actors = Promise.failed(new Exception))
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(2)
      .expectArchiveStateNotifications(HttpArchiveStateNoUnionArchive, 1, 2, 3, 4)
  }

  it should "create the archive if information comes in different order" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .initArchiveStartupResult(createArchiveResources(2), 2, 2)
      .prepareMediaFacadeActorsRequest()
      .sendLoginForRealm(2)
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .expectArchiveStateNotification(stateNotification(2, HttpArchiveStateInitializing))
      .expectArchiveCreation()
  }

  it should "do a logout first if a login message is received" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .initArchiveStartupResult(createArchiveResources(2), 2, 2)
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(2)
      .expectArchiveStateNotification(stateNotification(2, HttpArchiveStateInitializing))
      .sendLoginForRealm(2)
      .expectArchiveStateNotification(stateNotification(2, HttpArchiveStateNotLoggedIn))
      .expectArchiveStateNotification(stateNotification(2, HttpArchiveStateInitializing))

    val (indices, clears) = helper.fetchStarterParameters()
    indices.size should be > 1
    indices.toSet should have size indices.size
    clears.size should be(indices.size)
    clears.head shouldBe true
    clears.tail forall (_ == false) shouldBe true
  }

  it should "check preconditions again after facade actors have been fetched" in {
    val helper = new StartupTestHelper
    helper.messageBus.uiFutureProcessing = false
    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendLoginForRealm(2)
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotification(stateNotification(1, HttpArchiveStateNotLoggedIn))
      .expectArchiveStateNotification(stateNotification(3, HttpArchiveStateNotLoggedIn))
      .expectArchiveStateNotification(stateNotification(4, HttpArchiveStateNotLoggedIn))
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
    val uiFutureMsg = helper.messageBus.expectMessageType[Any]

    helper.sendAvailability(MediaFacade.MediaArchiveUnavailable)
      .expectArchiveStateNotifications(HttpArchiveStateNoUnionArchive, 1, 2, 3, 4, 5)
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
    watch(actor)
    expectTerminated(actor)
  }

  /**
    * Checks whether all resources created for archives are released when the
    * archives are no longer active.
    *
    * @param archiveResources the list with resources to check
    */
  private def checkResourcesCleaned(archiveResources: List[HttpArchiveStarter.ArchiveResources]): Unit = {
    archiveResources flatMap (_.actors.values) foreach expectTermination
    archiveResources foreach { resources =>
      verify(resources.downloader).shutdown()
    }
  }

  it should "stop the archive actors if the user logs out" in {
    val helper = new StartupTestHelper
    val archiveResources = prepareMultiRealmLogin(helper)
    enterArchiveAvailableState(helper)

    helper.sendOnMessageBus(LoginStateChanged(StartupConfigTestHelper.realmName(1), None))
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 3)
    checkResourcesCleaned(archiveResources)
  }

  it should "not send a message if the archive state does not change" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .sendAvailability(MediaFacade.MediaArchiveUnavailable)
    helper.messageBus.expectNoMessage()
  }

  it should "stop the archive actors on shutdown" in {
    val helper = new StartupTestHelper
    val archiveResources = prepareMultiRealmLogin(helper)
    enterArchiveAvailableState(helper)

    helper.deactivateApplication()
    checkResourcesCleaned(archiveResources)
  }

  it should "ignore a duplicate availability message" in {
    val helper = new StartupTestHelper
    prepareMultiRealmLogin(helper)
    enterArchiveAvailableState(helper)

    helper.sendAvailability(MediaFacade.MediaArchiveAvailable)
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
  .HttpArchiveState, expNotificationState: HttpArchiveState): Unit = {
    val helper = new StartupTestHelper
    prepareMultiRealmLogin(helper, Some(createStateActor(archiveState)))
    triggerArchiveCreation(helper)
    val msg = helper.messageBus.processNextMessage[HttpArchiveStateChanged]()
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
    triggerArchiveCreation(helper)
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
    val configManager = helper.queryBean[HttpArchiveConfigManager](helper.app,
      HttpArchiveStartupApplication.BeanConfigManager)

    configManager.archives should have size 5
  }

  it should "setup the main window" in {
    val helper = new StartupTestHelper

    helper.startupApplication(handleAvailabilityReg = false)
    helper.queryBean[HttpArchiveOverviewController](helper.app.getMainWindowBeanContext,
      "overviewController")
  }

  it should "handle an encrypted archive that is locked" in {
    val helper = new StartupTestHelper

    helper.startupApplication()
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(EncArchiveIndex)
      .expectArchiveStateNotification(stateNotification(EncArchiveIndex, HttpArchiveStateLocked))
  }

  it should "start up an encrypted archive if all information is available" in {
    val optKey = Some(mock[Key])
    val helper = new StartupTestHelper(skipUI = true)

    helper.startupApplication()
      .initArchiveStartupResult(createArchiveResources(EncArchiveIndex), EncArchiveIndex, EncArchiveIndex,
        optKey = optKey)
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(EncArchiveIndex)
      .expectArchiveStateNotification(stateNotification(4, HttpArchiveStateLocked))
      .sendLockStateChangeNotification(EncArchiveIndex, optKey)
      .expectArchiveStateNotifications(HttpArchiveStateInitializing, EncArchiveIndex)
      .expectArchiveCreation()
  }

  it should "start up an encrypted archive if information arrives in alternative order" in {
    val optKey = Some(mock[Key])
    val helper = new StartupTestHelper(skipUI = true)

    helper.startupApplication()
      .initArchiveStartupResult(createArchiveResources(EncArchiveIndex), EncArchiveIndex, EncArchiveIndex,
        optKey = optKey)
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLockStateChangeNotification(EncArchiveIndex, optKey)
      .sendLoginForRealm(EncArchiveIndex)
      .expectArchiveStateNotifications(HttpArchiveStateInitializing, EncArchiveIndex)
      .expectArchiveCreation()
  }

  it should "stop the actors of an archive when it gets locked" in {
    val resources = createArchiveResources(EncArchiveIndex)
    val optKey = Some(mock[Key])
    val helper = new StartupTestHelper(skipUI = true)

    helper.startupApplication()
      .initArchiveStartupResult(resources, EncArchiveIndex, EncArchiveIndex, optKey = optKey)
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(EncArchiveIndex)
      .expectArchiveStateNotification(stateNotification(4, HttpArchiveStateLocked))
      .sendLockStateChangeNotification(EncArchiveIndex, optKey)
      .expectArchiveStateNotifications(HttpArchiveStateInitializing, EncArchiveIndex)
      .expectArchiveCreation()
      .sendLockStateChangeNotification(EncArchiveIndex, None)
      .expectArchiveStateNotifications(HttpArchiveStateLocked, EncArchiveIndex)
    checkResourcesCleaned(List(resources))
  }

  it should "start an archive using another protocol when this protocol becomes available" in {
    val protocol = createProtocolSpecMock(SpecialProtocol)
    val helper = new StartupTestHelper

    helper.startupApplication()
      .initArchiveStartupResult(createArchiveResources(ProtocolArchiveIndex), ProtocolArchiveIndex,
        ProtocolArchiveIndex, optProtocol = Some(protocol))
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .addProtocol(protocol)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNotLoggedIn))
      .sendLoginForRealm(ProtocolArchiveIndex)
      .expectArchiveStateNotifications(HttpArchiveStateInitializing, ProtocolArchiveIndex)
      .expectArchiveCreation()
  }

  it should "stop the actors of an archive when its protocol goes away" in {
    val spec = createProtocolSpecMock(SpecialProtocol)
    val resources = createArchiveResources(ProtocolArchiveIndex)
    val helper = new StartupTestHelper

    helper.startupApplication()
      .initArchiveStartupResult(resources, ProtocolArchiveIndex, ProtocolArchiveIndex, optProtocol = Some(spec))
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(ProtocolArchiveIndex)
      .addProtocol(spec)
      .expectArchiveStateNotifications(HttpArchiveStateInitializing, ProtocolArchiveIndex)
      .expectArchiveCreation()
      .removeProtocol(spec)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
    checkResourcesCleaned(List(resources))
  }

  it should "handle a protocol added event before being fully started up" in {
    val protocol = createProtocolSpecMock(SpecialProtocol)
    val context = mock[ClientApplicationContext]
    val bus = new MessageBusTestImpl
    when(context.messageBus).thenReturn(bus)
    val app = new HttpArchiveStartupApplication
    app.initClientContext(context)

    app.addProtocolSpec(protocol)
    bus.processNextMessage[AnyRef]()
  }

  it should "handle a failed future from the archive starter" in {
    val Index = 2
    val exception = new IllegalStateException("Could not startup archive :-(")
    val errState = HttpArchiveStateServerError(exception)
    val expState = HttpArchiveErrorState(errState)
    val helper = new StartupTestHelper

    helper.startupApplication()
      .initFailedArchiveStartupResult(exception)
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(Index)
      .expectArchiveStateNotification(stateNotification(Index, expState))
  }

  it should "invoke the super password service to write the password file" in {
    val key = mock[Key]
    val optKey = Some(key)
    val superPasswordService = mock[SuperPasswordStorageService]
    val targetPath = Paths get "somePath"
    val SuperPassword = "theTopSecretVerySpecialSuperHyperPassword"
    val writeFuture = Future.successful(Paths get "writePath")
    when(superPasswordService.writeSuperPasswordFile(argEq(targetPath), argEq(SuperPassword),
      any(), any())(argEq(system))).thenReturn(writeFuture)
    val helper = new StartupTestHelper(skipUI = true)
    helper.startupApplication()
      .initArchiveStartupResult(createArchiveResources(EncArchiveIndex), EncArchiveIndex, EncArchiveIndex,
        optKey = optKey)
      .prepareMediaFacadeActorsRequest()
      .sendAvailability(MediaFacade.MediaArchiveAvailable)
      .expectArchiveStateNotifications(HttpArchiveStateNotLoggedIn, 1, 2, 3, 4)
      .expectArchiveStateNotification(stateNotification(ProtocolArchiveIndex, HttpArchiveStateNoProtocol))
      .sendLoginForRealm(EncArchiveIndex)
      .expectArchiveStateNotification(stateNotification(4, HttpArchiveStateLocked))
      .sendLockStateChangeNotification(EncArchiveIndex, optKey)
      .expectArchiveCreation()

    helper.app.saveArchiveCredentials(superPasswordService, targetPath, SuperPassword) should be(writeFuture)
    val captRealms = ArgumentCaptor.forClass(classOf[Map[String, UserCredentials]])
    val captLocks = ArgumentCaptor.forClass(classOf[Map[String, Key]])
    verify(superPasswordService).writeSuperPasswordFile(any(), anyString(), captRealms.capture(),
      captLocks.capture())(any())
    val realms = captRealms.getValue
    realms.keySet should contain only StartupConfigTestHelper.realmName(EncArchiveIndex)
    val realmCredentials = realms.values.head
    val expCredentials = credentials(EncArchiveIndex)
    realmCredentials.userName should be(expCredentials.userName)
    realmCredentials.password.secret should be(expCredentials.password.secret)
    val archives = captLocks.getValue
    archives should have size 1
    archives(StartupConfigTestHelper.archiveName(EncArchiveIndex)) should be(key)
  }

  it should "register a SuperPasswordController at the message bus" in {
    val helper = new StartupTestHelper
    helper.startupApplication()
    val beanContext = helper.app.getMainWindowBeanContext

    val superPasswordCtrl = queryBean[SuperPasswordController](beanContext, "superPasswordController")
    superPasswordCtrl.application should be(helper.app)
    superPasswordCtrl.superPasswordService should be(SuperPasswordStorageServiceImpl)

    val registrations = queryBean[MessageBusRegistration](beanContext, "LineDJ_messageBusRegistration")
    registrations.listeners should contain(superPasswordCtrl)
  }

  /**
    * A test implementation of the startup application. This class allows
    * overriding beans from the application context with mock beans.
    *
    * @param skipUI    flag whether UI creation is to be skipped
    * @param mockBeans a map with beans to mock in the context
    */
  private class HttpArchiveStartupApplicationTestImpl(skipUI: Boolean, mockBeans: Map[String, AnyRef])
    extends HttpArchiveStartupApplication with ApplicationSyncStartup with AppWithTestPlatform {

    override def initBeans(config: Configuration): BeanContext = {
      val beanContext = super.initBeans(config)
      val store = beanContext.getDefaultBeanStore
      val overrideStore = new DefaultBeanStore("override", store)
      mockBeans foreach { e =>
        overrideStore.addBeanProvider(e._1, ConstantBeanProvider.getInstance(e._2))
      }

      beanContext.setDefaultBeanStore(overrideStore)
      beanContext
    }

    override def initGUI(appCtx: ApplicationContext): Unit = {
      if (!skipUI) super.initGUI(appCtx)
    }
  }

  /**
    * A test helper class managing a test instance and allowing access to it.
    *
    * @param skipUI      flag whether UI creation is to be skipped
    * @param mockStarter flag whether the application starter is to be mocked
    */
  private class StartupTestHelper(skipUI: Boolean = false, mockStarter: Boolean = true)
    extends ApplicationTestSupport {
    /** A test message bus. */
    val messageBus = new MessageBusTestImpl(initUIFutureProcessing = true)

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

    /** The mock for the default WebDav protocol. */
    private val webDavProtocol = createProtocolSpecMock(HttpArchiveConfigManager.DefaultProtocolName)

    /** The application to be tested. */
    val app: HttpArchiveStartupApplicationTestImpl = createStartupApp()

    /**
      * Returns the configuration of the simulated application.
      *
      * @return the configuration
      */
    def configuration: Configuration = clientApplicationContext.managementConfiguration

    /**
      * Initializes and starts the test application. A notification about the
      * standard protocol is processed, too.
      *
      * @param handleAvailabilityReg flag whether a registration is expected
      * @return this test helper
      */
    def startupApplication(handleAvailabilityReg: Boolean = true): StartupTestHelper = {
      activateApp(app)
      if (handleAvailabilityReg) {
        availabilityRegistration = messageBus.expectMessageType[ArchiveAvailabilityRegistration]
      }
      addProtocol(webDavProtocol)
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
      val stateMsg = nextArchiveStateNotification()
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
    def expectArchiveStateNotifications(state: HttpArchiveState, archives: Int*): StartupTestHelper = {
      val messages = (1 to archives.length) map (_ => nextArchiveStateNotification())
      val expNotifications = archives map (i => stateNotification(i, state))
      messages should contain theSameElementsAs expNotifications
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
      * Sends a change notification for the lock state of the given archive.
      *
      * @param archiveIdx the index of the archive
      * @param optKey     the optional decryption key
      * @return this test helper
      */
    def sendLockStateChangeNotification(archiveIdx: Int, optKey: Option[Key]): StartupTestHelper =
      sendOnMessageBus(LockStateChanged(StartupConfigTestHelper.archiveName(archiveIdx), optKey))

    /**
      * Adds the given protocol spec to the test application and makes sure
      * that the corresponding message is processed on the message bus.
      *
      * @param spec the protocol spec
      * @return this test helper
      */
    def addProtocol(spec: GenericHttpArchiveProtocolSpec): StartupTestHelper = {
      app addProtocolSpec spec
      messageBus.processNextMessage[AnyRef]()
      this
    }

    /**
      * Sends a notification to the test application that the given protocol
      * spec is no longer available and makes sure that the corresponding
      * message is processed on the message bus.
      *
      * @param spec the protocol spec
      * @return this test helper
      */
    def removeProtocol(spec: GenericHttpArchiveProtocolSpec): StartupTestHelper = {
      app removeProtocolSpec spec
      messageBus.processNextMessage[AnyRef]()
      this
    }

    /**
      * Prepares the mock startup object for a startup operation and defines
      * the resources object to be returned. With this method successful
      * archive creations can be prepared.
      *
      * @param resources   the resources object to be returned by the mock
      * @param archiveIdx  the index of the test archive
      * @param realmIdx    the index of the test realm
      * @param optProtocol an option for the expected HTTP protocol
      * @param optKey      the optional decryption key
      * @return this test helper
      */
    def initArchiveStartupResult(resources: HttpArchiveStarter.ArchiveResources, archiveIdx: Int,
                                 realmIdx: Int, optProtocol: Option[GenericHttpArchiveProtocolSpec] = None,
                                 optKey: Option[Key] = None): StartupTestHelper = {
      expectStarterInvocation(archiveIdx, optProtocol getOrElse webDavProtocol, realmIdx, optKey)
        .thenAnswer((invocation: InvocationOnMock) => {
          archiveStartupCount.incrementAndGet()
          Future.successful(adaptActorNames(resources, invocation.getArguments()(7).asInstanceOf[Int]))
        })
      this
    }

    /**
      * Prepares the mock archive starter to return a failed ''Future'' when
      * invoked to start an archive.
      *
      * @param exception the exception to fail the future
      * @return this test helper
      */
    def initFailedArchiveStartupResult(exception: Throwable): StartupTestHelper = {
      when(archiveStarter.startup(any(), any(), any(), any(classOf[GenericHttpArchiveProtocolSpec]), any(), any(),
        any(), anyInt(), anyBoolean())(any(), any())).thenReturn(Future.failed(exception))
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
      * Returns the variable parameters that have been passed to the starter
      * object. These consist of the archive indices and the flags to clear
      * the temporary directory.
      *
      * @return tuple with numeric indices and clear flags
      */
    def fetchStarterParameters(): (Seq[Int], Seq[Boolean]) = {
      import scala.jdk.CollectionConverters._
      val captorIdx = ArgumentCaptor.forClass(classOf[Int])
      val captorClear = ArgumentCaptor.forClass(classOf[Boolean])
      verify(archiveStarter, times(2))
        .startup(argEq(MediaFacadeActors(probeUnionMediaManager.ref,
          probeUnionMetaManager.ref)), any(classOf[HttpArchiveData]), argEq(archiveConfig),
          any(classOf[GenericHttpArchiveProtocolSpec]),
          any(classOf[UserCredentials]), any(), argEq(actorFactory), captorIdx.capture(),
          captorClear.capture())(any(), any())
      (captorIdx.getAllValues.asScala.toSeq, captorClear.getAllValues.asScala.toSeq)
    }

    /**
      * @inheritdoc This implementation returns the test client application
      *             context that has already been created initially by this
      *             test helper.
      */
    override def createClientApplicationContext(config: Configuration): ClientApplicationContext =
      clientApplicationContext

    /**
      * Expects that the next message on the message bus is an archive state
      * notification. The message is returned.
      *
      * @return the state changed notification message
      */
    private def nextArchiveStateNotification(): HttpArchiveStateChanged =
      messageBus.expectMessageType[HttpArchiveStateChanged]

    /**
      * Creates the application to be tested.
      *
      * @return the test application
      */
    private def createStartupApp(): HttpArchiveStartupApplicationTestImpl = {
      val mockBeans: Map[String, AnyRef] = if (mockStarter)
        Map(HttpArchiveStartupApplication.BeanArchiveStarter -> archiveStarter)
      else Map.empty
      new HttpArchiveStartupApplicationTestImpl(skipUI, mockBeans)
    }

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
      * @param spec       the HTTP protocol spec for the archive
      * @param realmIdx   the index of the test realm
      * @param optKey     the optional decryption key
      * @return the stubbing object to define the behavior of the startup
      *         method
      */
    private def expectStarterInvocation(archiveIdx: Int, spec: GenericHttpArchiveProtocolSpec, realmIdx: Int,
                                        optKey: Option[Key]):
    OngoingStubbing[Future[HttpArchiveStarter.ArchiveResources]] = {
      val archiveData = archiveConfigManager.archives(
        StartupConfigTestHelper.archiveName(archiveIdx))
      val creds = credentials(realmIdx)
      when(archiveStarter.startup(argEq(MediaFacadeActors(probeUnionMediaManager.ref,
        probeUnionMetaManager.ref)), argEq(archiveData), argEq(archiveConfig), argEq(spec),
        credentialsEq(creds), argEq(optKey), argEq(actorFactory), anyInt(), anyBoolean())(any(), any()))
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
    case de.oliver_heger.linedj.archivehttp.HttpArchiveStateRequest =>
      sender() ! stateResponse
  }
}

/**
  * A custom matcher implementation for ''UserCredentials''. As ''Secret''
  * objects do not define an ''equals()'' method, a custom matcher is required
  * to deal with such objects.
  *
  * @param expCredentials the expected credentials
  */
class CredentialsMatcher(expCredentials: UserCredentials) extends ArgumentMatcher[UserCredentials] {
  override def matches(argument: Any): Boolean = {
    val actualCredentials = argument.asInstanceOf[UserCredentials]
    expCredentials.userName == actualCredentials.userName &&
      expCredentials.password.secret == actualCredentials.password.secret
  }
}
