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

import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates.{HttpArchiveState, HttpArchiveStateNotLoggedIn}
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import net.sf.jguiraffe.gui.builder.event.FormActionEvent
import net.sf.jguiraffe.gui.builder.window.WindowEvent
import net.sf.jguiraffe.gui.forms.ComponentHandler
import org.mockito.Matchers.anyBoolean
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object HttpArchiveLoginControllerSpec {
  /** Constant for a test user name. */
  private val UserName = "scott"

  /** Constant for a password. */
  private val Password = "tiger"

  /**
    * An answer class for keeping track of the enabled state of a button mock.
    */
  class ButtonEnabledAnswer extends Answer[Object] {
    /** The current enabled state. */
    var enabledState = true

    override def answer(invocation: InvocationOnMock): Object = {
      enabledState = invocation.getArguments.head.asInstanceOf[Boolean]
      null
    }
  }

}

/**
  * Test class for ''HttpArchiveLoginController''.
  */
class HttpArchiveLoginControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import HttpArchiveLoginControllerSpec._

  /**
    * Creates a mock window event.
    *
    * @return the mock window event
    */
  private def windowEvent(): WindowEvent = mock[WindowEvent]

  "An HttpArchiveLoginController" should "register a bus listener initially" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow().verifyBusListenerRegistration()
  }

  it should "remove the bus listener registration when the window is closed" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .closeWindow().verifyRemoveBusListenerRegistration()
  }

  it should "have dummy implementations for window listener methods" in {
    val helper = new LoginControllerTestHelper

    helper.checkWindowEvents()
  }

  it should "set the correct initial enabled state for buttons" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .verifyLoginButtonEnabled(state = true)
      .verifyLogoutButtonEnabled(state = false)
  }

  it should "handle a click on the login button" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .prepareCredentials()
      .loginClicked()
      .verifyLoginButtonEnabled(state = false)
      .verifyLogoutButtonEnabled(state = true)
      .verifyPasswordReset()
    helper.messageBus.expectMessageType[LoginStateChanged] should be(LoginStateChanged(
      Some(UserCredentials(UserName, Password))
    ))
  }

  it should "handle a click on the logout button" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .logoutClicked()
      .verifyLoginButtonEnabled(state = true)
      .verifyLogoutButtonEnabled(state = false)
    helper.messageBus.expectMessageType[LoginStateChanged] should be(LoginStateChanged(None))
  }

  it should "pass an HTTP archive state to the status line handler" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .sendOnMessageBus(HttpArchiveStateNotLoggedIn)
      .verifyStatusLineHandler(HttpArchiveStateNotLoggedIn)
  }

  /**
    * A test helper class managing dependencies for the controller under test.
    */
  private class LoginControllerTestHelper {
    /** The message bus. */
    val messageBus = new MessageBusTestImpl

    /** Mock for the user input field. */
    private val txtUser = mock[ComponentHandler[String]]

    /** Mock for the password input field. */
    private val txtPassword = mock[ComponentHandler[String]]

    /** The enabled state answer for the login button. */
    private val answerLogin = new ButtonEnabledAnswer

    /** Mock for the login button. */
    private val btnLogin = createButtonMock(answerLogin)

    /** The enabled state answer for the logout button. */
    private val answerLogout = new ButtonEnabledAnswer

    /** Mock for the logout button. */
    private val btnLogout = createButtonMock(answerLogout)

    /** Mock for the status line handler. */
    private val statusLineHandler = mock[StatusLineHandler]

    /** The test controller. */
    val controller: HttpArchiveLoginController = createController()

    /**
      * Sends a window open event to the test controller.
      *
      * @return this test helper
      */
    def openWindow(): LoginControllerTestHelper = {
      controller windowOpened windowEvent()
      messageBus.expectMessageType[Any] should be(HttpArchiveStateRequest)
      this
    }

    /**
      * Sends a window closed event to the test controller.
      *
      * @return this test helper
      */
    def closeWindow(): LoginControllerTestHelper = {
      controller windowClosed windowEvent()
      this
    }

    /**
      * Verifies that a message bus listener has been added.
      *
      * @return this test helper
      */
    def verifyBusListenerRegistration(): LoginControllerTestHelper = {
      messageBus.busListeners should have size 1
      this
    }

    /**
      * Verifies that the message bus listener has been removed again.
      *
      * @return this test helper
      */
    def verifyRemoveBusListenerRegistration(): LoginControllerTestHelper = {
      messageBus.busListeners should have size 0
      this
    }

    /**
      * Verifies that the login button has the expected enabled state.
      *
      * @param state the expected state
      * @return this test helper
      */
    def verifyLoginButtonEnabled(state: Boolean): LoginControllerTestHelper =
      verifyButtonEnabled(answerLogin, state)

    /**
      * Verifies that the logout button has the expected enabled state.
      *
      * @param state the expected state
      * @return this test helper
      */
    def verifyLogoutButtonEnabled(state: Boolean): LoginControllerTestHelper =
      verifyButtonEnabled(answerLogout, state)

    /**
      * Prepares the mocks for text fields to return test user credentials.
      *
      * @return this test helper
      */
    def prepareCredentials(): LoginControllerTestHelper = {
      when(txtUser.getData).thenReturn(UserName)
      when(txtPassword.getData).thenReturn(Password)
      this
    }

    /**
      * Simulates a click on the login button.
      *
      * @return this test helper
      */
    def loginClicked(): LoginControllerTestHelper =
      simulateButtonClick(btnLogin)

    /**
      * Simulates a click on the logout button.
      *
      * @return this test helper
      */
    def logoutClicked(): LoginControllerTestHelper =
      simulateButtonClick(btnLogout)

    /**
      * Verifies that the password field has been reset after the login button
      * has been clicked.
      *
      * @return this test helper
      */
    def verifyPasswordReset(): LoginControllerTestHelper = {
      verify(txtPassword).setData("")
      this
    }

    /**
      * Invokes all window listener methods and checks that they do not have
      * an effect.
      *
      * @return this test helper
      */
    def checkWindowEvents(): LoginControllerTestHelper = {
      val event = windowEvent()
      controller windowActivated event
      controller windowClosing event
      controller windowDeactivated event
      controller windowDeiconified event
      controller windowIconified event
      verifyZeroInteractions(event)
      this
    }

    /**
      * Sends the provided message directly on the test message bus.
      *
      * @param msg the message
      * @return this test helper
      */
    def sendOnMessageBus(msg: Any): LoginControllerTestHelper = {
      messageBus publishDirectly msg
      this
    }

    /**
      * Verifies that the specified state has been passed to the status line
      * handler.
      *
      * @param state the expected state
      * @return this test helper
      */
    def verifyStatusLineHandler(state: HttpArchiveState): LoginControllerTestHelper = {
      verify(statusLineHandler).archiveStateChanged(state)
      this
    }

    /**
      * Checks whether a button has the expected enabled state.
      *
      * @param state   the answer monitoring the button state
      * @param enabled the expected enabled state
      * @return this test helper
      */
    private def verifyButtonEnabled(state: ButtonEnabledAnswer, enabled: Boolean):
    LoginControllerTestHelper = {
      state.enabledState should be(enabled)
      this
    }

    /**
      * Invokes the action listener method of the test controller simulating a
      * button click.
      *
      * @param btn the button to be simulated
      * @return this test helper
      */
    private def simulateButtonClick(btn: ComponentHandler[Boolean]): LoginControllerTestHelper = {
      val event = new FormActionEvent(this, btn, "someButton", "someCmd")
      controller actionPerformed event
      this
    }

    /**
      * Creates a mock for a button.
      *
      * @param answer the answer for changing the enabled state
      * @return the mock buton component handler
      */
    private def createButtonMock(answer: Answer[Object]): ComponentHandler[Boolean] = {
      val handler = mock[ComponentHandler[Boolean]]
      doAnswer(answer).when(handler).setEnabled(anyBoolean())
      handler
    }

    /**
      * Creates the test controller instance.
      *
      * @return the test controller
      */
    private def createController(): HttpArchiveLoginController =
      new HttpArchiveLoginController(messageBus, txtUser, txtPassword, btnLogin, btnLogout,
        statusLineHandler)
  }

}
