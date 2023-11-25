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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttpstart.app.HttpArchiveStates.{HttpArchiveState, HttpArchiveStateAvailable, HttpArchiveStateInitializing, HttpArchiveStateNotLoggedIn}
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import net.sf.jguiraffe.gui.builder.window.WindowEvent
import org.apache.commons.configuration.HierarchicalConfiguration
import org.mockito.ArgumentMatchers.{any, anyBoolean}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.security.Key
import java.util
import java.util.concurrent.atomic.AtomicReference

object HttpArchiveOverviewControllerSpec:
  /** The number of archives defined in the configuration. */
  private val ArchiveCount = 4

  /** The configuration manager with test archives. */
  private val ConfigManager = createConfigManager()

  /** Icon for the active state. */
  private val IconActive = new Object

  /** Icon for the inactive state. */
  private val IconInactive = new Object

  /** Icon for the pending state. */
  private val IconPending = new Object

  /** Icon for the locked state. */
  private val IconLocked = new Object

  /** Icon for the unlocked state. */
  private val IconUnlocked = new Object

  /**
    * Creates a config manager object that is initialized with a number of test
    * archives and associated realms. The last archive is also encrypted.
    *
    * @return the test configuration manager
    */
  private def createConfigManager(): HttpArchiveConfigManager =
    val config = StartupConfigTestHelper.addArchiveToConfig(
      StartupConfigTestHelper.addArchiveToConfig(
        StartupConfigTestHelper.addConfigs(new HierarchicalConfiguration, 2, ArchiveCount - 1),
        1, Some(StartupConfigTestHelper.realmName(ArchiveCount - 1))), ArchiveCount, encrypted = true)
    HttpArchiveConfigManager(config)

  /**
    * Generates a change notification for an archive state.
    *
    * @param idx   the index of the test archive
    * @param state the new state
    * @return the change notification
    */
  private def stateChanged(idx: Int, state: HttpArchiveState): HttpArchiveStateChanged =
    HttpArchiveStateChanged(StartupConfigTestHelper.archiveName(idx), state)

  /**
    * Generates a notification about a login state change for a realm.
    *
    * @param idx      the index of the test realm
    * @param loggedIn a flag whether login credentials are now available
    * @return the change notification
    */
  private def realmLoginState(idx: Int, loggedIn: Boolean): LoginStateChanged =
    val credentials = if loggedIn then Some(UserCredentials("foo", Secret("bar")))
    else None
    LoginStateChanged(StartupConfigTestHelper.realmName(idx), credentials)

/**
  * Test class for ''HttpArchiveOverviewController''.
  */
class HttpArchiveOverviewControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import HttpArchiveOverviewControllerSpec._

  /**
    * Generates a lock state changed message for the encrypted test archive.
    *
    * @param locked flag whether the archive is locked
    * @return the message
    */
  private def lockStateChanged(locked: Boolean): LockStateChanged =
    LockStateChanged(StartupConfigTestHelper.archiveName(ArchiveCount),
      if locked then None else Some(mock[Key]))

  "An HttpArchiveOverController" should "implement window listener methods" in:
    val helper = new ControllerTestHelper

    helper.testWindowEvents()

  it should "populate the table with archives" in:
    val expArchives = (1 to ArchiveCount) map { i =>
      TableElement(StartupConfigTestHelper.archiveName(i), IconInactive,
        if i == ArchiveCount then IconLocked else null)
    }
    val helper = new ControllerTestHelper

    helper.openWindow().checkArchivesPopulated(expArchives)

  it should "populate the table realms" in:
    val expRealms = (2 to ArchiveCount) map { i =>
      TableElement(StartupConfigTestHelper.realmName(i), IconInactive, null)
    }
    val helper = new ControllerTestHelper

    helper.openWindow().checkRealmsPopulated(expRealms)

  it should "initialize the enabled states of actions" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .checkActionEnabled("actionLogin", enabled = false)
      .checkActionEnabled("actionUnlock", enabled = false)
      .checkActionEnabled("actionLogout", enabled = false)
      .checkActionEnabled("actionLogoutAll", enabled = false)

  it should "request the current archive states when the window is opened" in:
    val helper = new ControllerTestHelper

    helper.openWindow(resetMsgBus = false)
      .expectMessageOnBus(HttpArchiveStateRequest)

  it should "handle an archive inactive notification" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initCurrentArchive(0)
      .sendMessage(stateChanged(2, HttpArchiveStateNotLoggedIn))
      .checkArchiveUpdated(2, IconInactive)
      .expectNoStatusLineUpdate()

  it should "handle an archive active notification" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initCurrentArchive(0)
      .sendMessage(stateChanged(2, HttpArchiveStateAvailable))
      .checkArchiveUpdated(2, IconActive)

  it should "handle an archive notification for the pending state" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initCurrentArchive(0)
      .sendMessage(stateChanged(2, HttpArchiveStateInitializing))
      .checkArchiveUpdated(2, IconPending)

  it should "handle an archive notification for an unknown archive" in:
    val helper = new ControllerTestHelper

    helper.sendMessage(stateChanged(1, HttpArchiveStateAvailable))
      .expectNoStatusLineUpdate()

  it should "update the status line if the state of the selected archive changes" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initCurrentArchive(0)
      .sendMessage(stateChanged(1, HttpArchiveStateAvailable))
      .expectStatusLineUpdate(HttpArchiveStateAvailable)

  it should "handle a logged in notification for a realm" in:
    val realmIdx = 3
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initCurrentRealm(-1)
      .sendMessage(realmLoginState(realmIdx, loggedIn = true))
      .checkRealmUpdated(realmIdx, IconActive)
      .checkActionEnabled("actionLogoutAll", enabled = true)
      .checkActionEnabled("actionLogout", enabled = false)

  it should "handle a logged out notification for a realm" in:
    val realmIdx = 2
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initCurrentRealm(-1)
      .sendMessage(realmLoginState(realmIdx, loggedIn = false))
      .checkRealmUpdated(realmIdx, IconInactive)

  it should "handle a realm login state changed notification for an unknown realm" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(LoginStateChanged("unknownRealm",
        Some(UserCredentials("foo", Secret("bar")))))
      .checkActionEnabled("actionLogoutAll", enabled = false)

  it should "update the state of the logout all action" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initCurrentRealm(-1)
      .sendMessage(realmLoginState(2, loggedIn = true))
      .sendMessage(realmLoginState(2, loggedIn = false))
      .checkActionEnabled("actionLogoutAll", enabled = false)

  it should "update the state of the logout action for the current realm" in:
    val realmIdx = 2
    val helper = new ControllerTestHelper

    helper.openWindow()
      .initCurrentRealm(0)
      .sendMessage(realmLoginState(realmIdx, loggedIn = true))
      .checkActionEnabled("actionLogout", enabled = true)
      .sendMessage(realmLoginState(realmIdx, loggedIn = false))
      .checkActionEnabled("actionLogout", enabled = false)

  it should "update the status line when another archive is selected" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(stateChanged(2, HttpArchiveStateNotLoggedIn))
      .sendArchiveTableSelectionChange(1)
      .expectStatusLineUpdate(HttpArchiveStateNotLoggedIn)

  it should "clear the status line if no archive is selected" in:
    val helper = new ControllerTestHelper

    helper.sendArchiveTableSelectionChange(-1)
      .expectStatusLineCleared()

  it should "update action states if another realm is selected" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(realmLoginState(2, loggedIn = true))
      .sendRealmTableSelectionChange(0)
      .checkActionEnabled("actionLogout", enabled = true)
      .checkActionEnabled("actionLogin", enabled = true)

  it should "disable the logout action if the selected realm has no credentials" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendRealmTableSelectionChange(0)
      .checkActionEnabled("actionLogout", enabled = false)
      .checkActionEnabled("actionLogin", enabled = true)

  it should "update action states if no realm is selected" in:
    val helper = new ControllerTestHelper

    helper.sendRealmTableSelectionChange(-1)
      .checkActionEnabled("actionLogout", enabled = false)
      .checkActionEnabled("actionLogin", enabled = false)

  it should "update the current realm name if the selection changes" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendRealmTableSelectionChange(1)
      .checkCurrentRealm(3)

  it should "update the current realm name if the selection is cleared" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendRealmTableSelectionChange(1)
      .sendRealmTableSelectionChange(-1)
      .checkCurrentRealm(-1)

  it should "update the current archive name if the selection changes" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendArchiveTableSelectionChange(1)
      .checkCurrentArchiveName(2)

  it should "update the current archive name if the selection is cleared" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendArchiveTableSelectionChange(2)
      .sendArchiveTableSelectionChange(-1)
      .checkCurrentArchiveName(-1)

  it should "send a logout message to logout the current realm" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendRealmTableSelectionChange(0)
      .invokeLogout()
      .expectMessageOnBus(realmLoginState(2, loggedIn = false))

  it should "not send a logout message if there is no current realm" in:
    val helper = new ControllerTestHelper

    helper.invokeLogout().expectNoMoreMessagesOnBus()

  it should "implement the logout all action" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(realmLoginState(2, loggedIn = true))
      .sendMessage(realmLoginState(ArchiveCount - 1, loggedIn = true))
      .invokeLogoutAll()
      .expectMessageOnBus(realmLoginState(2, loggedIn = false))
      .expectMessageOnBus(realmLoginState(ArchiveCount - 1, loggedIn = false))
      .expectMessageOnBus(lockStateChanged(locked = true))
      .expectNoMoreMessagesOnBus()

  it should "indicate that an encrypted archive has been unlocked" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(lockStateChanged(locked = false))
      .checkArchiveUpdated(ArchiveCount, IconInactive, IconUnlocked)

  it should "indicate that an encrypted archive has been locked again" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(lockStateChanged(locked = false))
      .sendMessage(lockStateChanged(locked = true))
      .checkArchiveUpdated(ArchiveCount, IconInactive, IconLocked)

  it should "ignore lock state change messages for non-encrypted archives" in:
    val ArcIdx = 1
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(LockStateChanged(StartupConfigTestHelper.archiveName(ArcIdx), Some(mock[Key])))
      .checkArchiveData(ArcIdx, IconInactive, null)

  it should "lock archives when a logout for a realm is performed" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(realmLoginState(ArchiveCount, loggedIn = true))
      .sendMessage(lockStateChanged(locked = false))
      .sendRealmTableSelectionChange(ArchiveCount - 2)
      .invokeLogout()
      .expectMessageOnBus(realmLoginState(ArchiveCount, loggedIn = false))
      .expectMessageOnBus(lockStateChanged(locked = true))

  it should "enable the logout all action if there is an unlocked archive" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(lockStateChanged(locked = false))
      .checkActionEnabled("actionLogoutAll", enabled = true)

  it should "disable the logout all action if the last unlocked archive is locked" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(lockStateChanged(locked = false))
      .sendMessage(lockStateChanged(locked = true))
      .checkActionEnabled("actionLogoutAll", enabled = false)

  it should "update the enabled state of logout all action based on logged in realms and unlocked archives" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(realmLoginState(2, loggedIn = true))
      .sendMessage(lockStateChanged(locked = false))
      .sendMessage(realmLoginState(2, loggedIn = false))
      .checkActionEnabled("actionLogoutAll", enabled = true)

  it should "enable the unlock action if a locked, encrypted archive is selected" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendArchiveTableSelectionChange(ArchiveCount - 1)
      .checkActionEnabled("actionUnlock", enabled = true)

  it should "disable the unlock action if no archive is selected" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendArchiveTableSelectionChange(ArchiveCount - 1)
      .sendArchiveTableSelectionChange(-1)
      .checkActionEnabled("actionUnlock", enabled = false)

  it should "disable the unlock action if a non-encrypted archive is selected" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendArchiveTableSelectionChange(1)
      .checkActionEnabled("actionUnlock", enabled = false)

  it should "disable the unlock action if an already unlocked archive is selected" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendMessage(lockStateChanged(locked = false))
      .sendArchiveTableSelectionChange(ArchiveCount - 1)
      .checkActionEnabled("actionUnlock", enabled = false)

  it should "update the state of the unlock action when the currently selected archive is changed" in:
    val helper = new ControllerTestHelper

    helper.openWindow()
      .sendArchiveTableSelectionChange(ArchiveCount - 1)
      .sendMessage(lockStateChanged(locked = false))
      .checkActionEnabled("actionUnlock", enabled = false)
      .sendMessage(lockStateChanged(locked = true))
      .checkActionEnabled("actionUnlock", enabled = true)

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class ControllerTestHelper:
    /**
      * Holds states of actions set explicitly through the mock action manager.
      */
    private var actionStates = Map.empty[String, Boolean]

    /** The mock for the message bus. */
    private val messageBus = mock[MessageBus]

    /** The model collection for the archives table. */
    private val modelArchives = new util.ArrayList[TableElement]

    /** The model collection for the realms table. */
    private val modelRealms = new util.ArrayList[TableElement]

    /** The mock for the handler for the archives table. */
    private val handlerArchives = createTableHandler(modelArchives)

    /** The mock for the handler for the realms table. */
    private val handlerRealms = createTableHandler(modelRealms)

    /** The mock for the status helper. */
    private val statusHelper = createStatusHelper()

    /** The reference for the current realm. */
    private val refRealm = new AtomicReference[ArchiveRealm]

    /** The reference for the currently selected archive. */
    private val refArchive = new AtomicReference[String]

    /** The controller to be tested. */
    private val controller = createController()

    /**
      * Tests the dummy implementations for the window listener interface.
      */
    def testWindowEvents(): Unit =
      val event = mock[WindowEvent]
      controller.windowActivated(event)
      controller.windowClosed(event)
      controller.windowClosing(event)
      controller.windowDeactivated(event)
      controller.windowDeiconified(event)
      controller.windowIconified(event)
      verifyNoInteractions(event)

    /**
      * Simulates opening of the window.
      *
      * @param resetMsgBus flag whether the mock for the message bus should be
      *                    reset to ignore messages published during
      *                    initialization
      * @return this test helper
      */
    def openWindow(resetMsgBus: Boolean = true): ControllerTestHelper =
      controller.windowOpened(mock[WindowEvent])
      if resetMsgBus then
        reset(messageBus)
      this

    /**
      * Checks that the table with archives has been populated correctly.
      *
      * @param expArchives the expected archives
      * @return this test helper
      */
    def checkArchivesPopulated(expArchives: Iterable[TableElement]): ControllerTestHelper =
      checkHandlerPopulated(handlerArchives, expArchives)

    /**
      * Checks that the table with realms has been populated correctly.
      *
      * @param expRealms the expected realms
      * @return this test helper
      */
    def checkRealmsPopulated(expRealms: Iterable[TableElement]): ControllerTestHelper =
      checkHandlerPopulated(handlerRealms, expRealms)

    /**
      * Tests whether the specified action has the given enabled flag.
      *
      * @param name    the name of the action
      * @param enabled the expected enabled flag
      * @return this test helper
      */
    def checkActionEnabled(name: String, enabled: Boolean): ControllerTestHelper =
      actionStates(name) shouldBe enabled
      this

    /**
      * Sends the given message to the test controller on the message bus.
      *
      * @param msg the message
      * @return this test helper
      */
    def sendMessage(msg: Any): ControllerTestHelper =
      controller receive msg
      this

    /**
      * Initializes the table handler for the archives to return the given
      * current index.
      *
      * @param idx the index (0-based)
      * @return this test helper
      */
    def initCurrentArchive(idx: Int): ControllerTestHelper =
      when(handlerArchives.getSelectedIndex).thenReturn(idx)
      this

    /**
      * Checks that the table model data for an archive has the expected
      * content.
      *
      * @param idx       the index of the archive (1-based)
      * @param stateIcon the expected state icon
      * @param cryptIcon the expected crypt state icon
      * @return this test helper
      */
    def checkArchiveData(idx: Int, stateIcon: AnyRef, cryptIcon: AnyRef = null): ControllerTestHelper =
      val elem = TableElement(StartupConfigTestHelper.archiveName(idx), stateIcon, cryptIcon)
      modelArchives.get(idx - 1) should be(elem)
      this

    /**
      * Checks that the table model data for an archive has been updated.
      *
      * @param idx       the index of the archive (1-based)
      * @param stateIcon the expected state icon
      * @param cryptIcon the expected crypt state icon
      * @return this test helper
      */
    def checkArchiveUpdated(idx: Int, stateIcon: AnyRef, cryptIcon: AnyRef = null): ControllerTestHelper =
      checkArchiveData(idx, stateIcon, cryptIcon)
      verify(handlerArchives, atLeastOnce()).rowsUpdated(idx - 1, idx - 1)
      this

    /**
      * Initializes the table handler for the realms to return the given
      * current index.
      *
      * @param idx the index (0-based)
      * @return this test helper
      */
    def initCurrentRealm(idx: Int): ControllerTestHelper =
      when(handlerRealms.getSelectedIndex).thenReturn(idx)
      this

    /**
      * Checks that the table model data for a realm has been updated.
      *
      * @param idx  the index of the realm (1-based)
      * @param icon the expected icon
      * @return this test helper
      */
    def checkRealmUpdated(idx: Int, icon: AnyRef): ControllerTestHelper =
      val tabIdx = idx - 2
      val elem = TableElement(StartupConfigTestHelper.realmName(idx), icon, null)
      modelRealms.get(tabIdx) should be(elem)
      verify(handlerRealms).rowsUpdated(tabIdx, tabIdx)
      this

    /**
      * Checks that the status line has not been changed.
      *
      * @return this test helper
      */
    def expectNoStatusLineUpdate(): ControllerTestHelper =
      verify(statusHelper, never()).updateStatusLine(any(classOf[HttpArchiveState]))
      verify(statusHelper, never()).clearStatusLine()
      this

    /**
      * Expects an update of the status line for the given archive state.
      *
      * @param state the archive state
      * @return this test helper
      */
    def expectStatusLineUpdate(state: HttpArchiveState): ControllerTestHelper =
      verify(statusHelper).updateStatusLine(state)
      this

    /**
      * Expects that the status line was cleared.
      *
      * @return this test helper
      */
    def expectStatusLineCleared(): ControllerTestHelper =
      verify(statusHelper).clearStatusLine()
      this

    /**
      * Notifies the controller about a change in the selection of the archives
      * table.
      *
      * @param idx the new selected index
      * @return this test helper
      */
    def sendArchiveTableSelectionChange(idx: Int): ControllerTestHelper =
      initCurrentArchive(idx)
      controller.elementChanged(new FormChangeEvent(this, handlerArchives, "foo"))
      this

    /**
      * Notifies the controller about a change in the selection of the realms
      * table.
      *
      * @param idx the new selected index
      * @return this test helper
      */
    def sendRealmTableSelectionChange(idx: Int): ControllerTestHelper =
      initCurrentRealm(idx)
      controller.elementChanged(new FormChangeEvent(this, handlerRealms, "bar"))
      this

    /**
      * Checks that the reference to the current realm has been updated
      * correctly.
      *
      * @param realmIdx the expected current realm index (1-based) or -1 for
      *                 no selection
      * @return this test helper
      */
    def checkCurrentRealm(realmIdx: Int): ControllerTestHelper =
      val expRealm = if realmIdx >= 0 then findRealmByIndex(realmIdx)
      else null
      refRealm.get() should be(expRealm)
      this

    /**
      * Checks that the name of the currently selected archive has been updated
      * correctly.
      *
      * @param arcIdx the expected current archive index (1-based) or -1 for no
      *               selection
      * @return this test helper
      */
    def checkCurrentArchiveName(arcIdx: Int): ControllerTestHelper =
      val expName = if arcIdx >= 0 then StartupConfigTestHelper.archiveName(arcIdx)
      else null
      refArchive.get() should be(expName)
      this

    /**
      * Checks that no further messages have been published on the message bus.
      *
      * @return this test helper
      */
    def expectNoMoreMessagesOnBus(): ControllerTestHelper =
      verifyNoMoreInteractions(messageBus)
      this

    /**
      * Checks that the specified message was published on the message bus.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectMessageOnBus(msg: Any): ControllerTestHelper =
      verify(messageBus).publish(msg)
      this

    /**
      * Invokes the method to logout the current realm on the test controller.
      *
      * @return this test helper
      */
    def invokeLogout(): ControllerTestHelper =
      controller.logoutCurrentRealm()
      this

    /**
      * Invokes the method to logout all currently logged in realms.
      *
      * @return this test helper
      */
    def invokeLogoutAll(): ControllerTestHelper =
      controller.logoutAllRealms()
      this

    /**
      * Determines the realm object to the given realm index.
      *
      * @param realmIdx the realm index
      * @return the realm with this index
      */
    private def findRealmByIndex(realmIdx: Int): ArchiveRealm =
      val realmName = StartupConfigTestHelper.realmName(realmIdx)
      ConfigManager.archives.values
        .find(_.realm.name == realmName)
        .map(_.realm)
        .get

    /**
      * Checks whether the model of a table handler has been initialized
      * correctly.
      *
      * @param handler  the handler in question
      * @param expModel the expected model collection
      * @return this test helper
      */
    private def checkHandlerPopulated(handler: TableHandler, expModel: Iterable[TableElement]):
    ControllerTestHelper =
      verify(handler).tableDataChanged()
      checkTableModel(handler.getModel, expModel)

    /**
      * Checks that a table model has been populated correctly.
      *
      * @param model    the actual model
      * @param expModel the expected model
      * @return this test helper
      */
    private def checkTableModel(model: util.List[_],
                                expModel: Iterable[TableElement]): ControllerTestHelper =
      import scala.jdk.CollectionConverters._
      model.asScala should contain theSameElementsInOrderAs expModel
      this

    /**
      * Creates a mock table handler that returns the specified model.
      *
      * @param model the table model
      * @return the handler
      */
    private def createTableHandler(model: util.ArrayList[TableElement]): TableHandler =
      val handler = mock[TableHandler]
      doReturn(model).when(handler).getModel
      handler

    /**
      * Creates a mock action that can track its enabled state. The passed in
      * action store mock is configured to return this action.
      *
      * @param name  the name of the action
      * @param store the action store
      * @return the mock action
      */
    private def createAction(name: String, store: ActionStore): FormAction =
      val action = mock[FormAction]
      when(action.setEnabled(anyBoolean())).thenAnswer((invocation: InvocationOnMock) => {
        val state = invocation.getArguments.head.asInstanceOf[Boolean]
        actionStates += name -> state
        null
      })
      when(store.getAction(name)).thenReturn(action)
      action

    /**
      * Creates a mock for the status helper.
      *
      * @return the mock status helper
      */
    private def createStatusHelper(): ArchiveStatusHelper =
      val helper = mock[ArchiveStatusHelper]
      doReturn(IconActive).when(helper).iconActive
      doReturn(IconInactive).when(helper).iconInactive
      doReturn(IconPending).when(helper).iconPending
      doReturn(IconLocked).when(helper).iconLocked
      doReturn(IconUnlocked).when(helper).iconUnlocked
      helper

    /**
      * Creates the controller to be tested.
      *
      * @return the test controller
      */
    private def createController(): HttpArchiveOverviewController =
      val actionStore = mock[ActionStore]
      createAction("actionLogout", actionStore)
      createAction("actionLogoutAll", actionStore)
      createAction("actionLogin", actionStore)
      createAction("actionUnlock", actionStore)
      new HttpArchiveOverviewController(messageBus, ConfigManager, actionStore,
        handlerArchives, handlerRealms, statusHelper, refRealm, refArchive)

