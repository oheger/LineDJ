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

package de.oliver_heger.linedj.apps.archive.login

import de.oliver_heger.linedj.archive.server.cloud.model.CloudArchiveModel
import de.oliver_heger.linedj.platform.archiveclient.{ArchiveService, ArchiveStateMonitor, LoginService}
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import net.sf.jguiraffe.gui.builder.window.WindowEvent
import org.mockito.ArgumentMatchers.{any, anyBoolean}
import org.mockito.Mockito.{doReturn, verify, verifyNoInteractions, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util
import java.util.concurrent.atomic.AtomicReference
import scala.compiletime.uninitialized

object LoginControllerSpec:
  /** The icon for the waiting state. */
  private val IconWaiting = new Object

  /** The icon for the loaded state. */
  private val IconLoaded = new Object

  /** The icon for the failure state. */
  private val IconError = new Object

  /** A test object with a login state. */
  private val TestLoginState = LoginService.ArchiveLoginState(
    waitingArchives = Set("country music"),
    loadedArchives = Set("Rock Music", "Classic Music"),
    failedArchives = Map("Pop Music" -> CloudArchiveModel.FailedArchive("Pop Music", "Error", 1)),
    fileCredentials = Set("cloudPasswords"),
    archiveCredentials = Set("TestArchive.username", "TestArchive.password", "archive.crypt")
  )
end LoginControllerSpec

/**
  * Test class for [[LoginController]].
  */
class LoginControllerSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import LoginControllerSpec.*

  "A LoginController" should "implement window listener methods" in :
    val helper = new ControllerTestHelper

    helper.testWindowEvents()

  it should "register a change listener at the login service" in :
    val helper = new ControllerTestHelper

    helper.openWindow()
      .testArchiveChangeListener(TestLoginState, LoginController.LoginStateChanged(TestLoginState))

  it should "remove the change listener when the window is closed" in :
    val helper = new ControllerTestHelper

    helper.testArchiveChangeListenerUnregistration()

  it should "disable all actions initially" in :
    val helper = new ControllerTestHelper

    helper.openWindow()
      .checkActionEnabled("actionEnterCredential", enabled = false)

  it should "populate the table with the archive state" in :
    val expectedTableModel = List(
      LoginController.TableElement("Classic Music", IconLoaded),
      LoginController.TableElement("country music", IconWaiting),
      LoginController.TableElement("Pop Music", IconError),
      LoginController.TableElement("Rock Music", IconLoaded)
    )
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .checkArchivesPopulated(expectedTableModel)

  it should "populate the table with credentials" in :
    val expectedTableModel = List(
      LoginController.TableElement("archive.crypt", IconWaiting),
      LoginController.TableElement("cloudPasswords", IconWaiting),
      LoginController.TableElement("TestArchive.password", IconWaiting),
      LoginController.TableElement("TestArchive.username", IconWaiting)
    )
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .checkCredentialsPopulated(expectedTableModel)

  it should "update the status line when the selection of the archives table changes" in :
    val expectedState = ArchiveStatusHelper.ArchiveState.Failed("Error", 1)
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .sendArchiveTableSelectionChange(2)
      .expectStatusLineUpdate(expectedState)

  it should "clear the status line if no archive is selected" in :
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .sendArchiveTableSelectionChange(-1)
      .expectStatusLineCleared()

  it should "enable the enter credential action if a credential is selected" in :
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .sendCredentialsTableSelectionChange(1)
      .checkActionEnabled("actionEnterCredential", enabled = true)

  it should "disable the enter credential action if no credential is selected" in :
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .sendCredentialsTableSelectionChange(-1)
      .checkActionEnabled("actionEnterCredential", enabled = false)

  it should "update the current credential if a credential is selected" in :
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .sendCredentialsTableSelectionChange(1)
      .expectCurrentCredential("cloudPasswords")

  it should "reset the current credential if no credential is selected" in :
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .sendCredentialsTableSelectionChange(-1)
      .expectCurrentCredential(null)

  it should "trigger the archive monitor on receiving a login state change notification" in :
    val helper = new ControllerTestHelper

    helper.messageBusReceive(LoginController.LoginStateChanged(TestLoginState))
      .verifyArchiveStateMonitorReset()

  /**
    * A test helper class managing the controller to be tested and its
    * dependencies.
    */
  private class ControllerTestHelper:
    /**
      * Holds states of actions set explicitly through the mock action manager.
      */
    private var actionStates = Map.empty[String, Boolean]

    /** The mock for the message bus. */
    private val messageBus = mock[MessageBus]

    /** The mock for the login service. */
    private val loginService = mock[LoginService]

    /** The mock for the archive service. */
    private val archiveService = mock[ArchiveService]

    /** The model collection for the archives table. */
    private val modelArchives = new util.ArrayList[LoginController.TableElement]

    /** The model collection for the credentials table. */
    private val modelCredentials = new util.ArrayList[LoginController.TableElement]

    /** The mock for the handler for the archives table. */
    private val handlerArchives = createTableHandler(modelArchives)

    /** The mock for the handler for the credentials table. */
    private val handlerCredentials = createTableHandler(modelCredentials)

    /** The mock for the status helper. */
    private val statusHelper = createStatusHelper()

    /** The reference for the current credential. */
    private val refCredential = new AtomicReference[String]

    /** The controller to be tested. */
    private val controller = createController()

    /** Stores the change listener at the login service. */
    private var loginStateListener: ArchiveStateMonitor.ArchiveChangeListener[LoginService.ArchiveLoginState] =
      uninitialized

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
      * Send a window opened event to the test controller which should trigger
      * some initialization tasks.
      *
      * @return this test helper
      */
    def openWindow(): ControllerTestHelper =
      val event = mock[WindowEvent]
      controller.windowOpened(event)
      verifyNoInteractions(event)
      this

    /**
      * Tests that an event listener has been registered at the login service.
      * Simulates a state change notification and checks whether an expected
      * message is published on the message bus.
      *
      * @param state           the state passed to the notification
      * @param expectedMessage the message on the message bus
      * @return this test helper
      */
    def testArchiveChangeListener(state: LoginService.ArchiveLoginState, expectedMessage: Any): ControllerTestHelper =
      verify(loginService).addChangeListener(controller)
      controller.archiveStateChanged(state)
      verify(messageBus).publish(expectedMessage)
      this

    /**
      * Tests whether the change listener at the login service is removed again
      * when the window is closed.
      *
      * @return this test helper
      */
    def testArchiveChangeListenerUnregistration(): ControllerTestHelper =
      controller.windowClosed(mock)
      verify(loginService).removeChangeListener(controller)
      this

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
      * Simulates an incoming message on the message bus.
      *
      * @param message the message on the bus
      * @return this test helper
      */
    def messageBusReceive(message: Any): ControllerTestHelper =
      controller.receive(message)
      this

    /**
      * Checks that the table with cloud archives has been populated correctly.
      *
      * @param expArchives the expected archives
      * @return this test helper
      */
    def checkArchivesPopulated(expArchives: Iterable[LoginController.TableElement]): ControllerTestHelper =
      checkHandlerPopulated(handlerArchives, expArchives)

    /**
      * Checks that the table with credentials has been populated correctly.
      *
      * @param expCredentials the expected credentials
      * @return this test helper
      */
    def checkCredentialsPopulated(expCredentials: Iterable[LoginController.TableElement]): ControllerTestHelper =
      checkHandlerPopulated(handlerCredentials, expCredentials)

    /**
      * Expects an update of the status line for the given archive state.
      *
      * @param state the archive state
      * @return this test helper
      */
    def expectStatusLineUpdate(state: ArchiveStatusHelper.ArchiveState): ControllerTestHelper =
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
      * Tests whether the given credential has been set as the current one.
      *
      * @param credential the expected credential
      * @return this test helper
      */
    def expectCurrentCredential(credential: String): ControllerTestHelper =
      refCredential.get() should be(credential)
      this

    /**
      * Notifies the controller about a change in the selection of the archives
      * table.
      *
      * @param idx the new selected index
      * @return this test helper
      */
    def sendArchiveTableSelectionChange(idx: Int): ControllerTestHelper =
      when(handlerArchives.getSelectedIndex).thenReturn(idx)
      controller.elementChanged(new FormChangeEvent(this, handlerArchives, "foo"))
      this

    /**
      * Notifies the controller about a change in the selection of the
      * credentials table.
      *
      * @param idx the new selected index
      * @return this test helper
      */
    def sendCredentialsTableSelectionChange(idx: Int): ControllerTestHelper =
      when(handlerCredentials.getSelectedIndex).thenReturn(idx)
      controller.elementChanged(new FormChangeEvent(this, handlerCredentials, "bar"))
      this

    /**
      * Verifies that the archive state monitor is reset on receiving an update
      * of the login state.
      *
      * @return this test helper
      */
    def verifyArchiveStateMonitorReset(): ControllerTestHelper =
      verify(archiveService).expectChanges()
      this

    /**
      * Checks whether the model of a table handler has been initialized
      * correctly.
      *
      * @param handler  the handler in question
      * @param expModel the expected model collection
      * @return this test helper
      */
    private def checkHandlerPopulated(handler: TableHandler,
                                      expModel: Iterable[LoginController.TableElement]): ControllerTestHelper =
      verify(handler).tableDataChanged()
      verify(handler).setSelectedIndex(-1)
      checkTableModel(handler.getModel, expModel)

    /**
      * Checks that a table model has been populated correctly.
      *
      * @param model    the actual model
      * @param expModel the expected model
      * @return this test helper
      */
    private def checkTableModel(model: util.List[_],
                                expModel: Iterable[LoginController.TableElement]): ControllerTestHelper =
      import scala.jdk.CollectionConverters.*
      model.asScala should contain theSameElementsInOrderAs expModel
      this

    /**
      * Creates a mock table handler that returns the specified model.
      *
      * @param model the table model
      * @return the handler
      */
    private def createTableHandler(model: util.ArrayList[LoginController.TableElement]): TableHandler =
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
      when(helper.iconForState(any())).thenAnswer(
        (invocation: InvocationOnMock) => invocation.getArgument[ArchiveStatusHelper.ArchiveState](0) match
          case ArchiveStatusHelper.ArchiveState.Waiting => IconWaiting
          case ArchiveStatusHelper.ArchiveState.Loaded => IconLoaded
          case ArchiveStatusHelper.ArchiveState.Failed(_, _) => IconError
      )
      doReturn(IconWaiting).when(helper).iconWaiting
      helper

    /**
      * Creates the controller to be tested.
      *
      * @return the test controller
      */
    private def createController(): LoginController =
      val actionStore = mock[ActionStore]
      createAction("actionEnterCredential", actionStore)
      new LoginController(
        messageBus,
        actionStore,
        loginService,
        archiveService,
        handlerArchives,
        handlerCredentials,
        statusHelper,
        refCredential
      )
