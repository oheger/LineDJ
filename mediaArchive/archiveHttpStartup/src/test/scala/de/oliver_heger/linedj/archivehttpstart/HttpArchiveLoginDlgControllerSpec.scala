/*
 * Copyright 2015-2019 The Developers Team.
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
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.gui.builder.event.FormActionEvent
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent}
import net.sf.jguiraffe.gui.forms.ComponentHandler
import org.mockito.Mockito.{verify, verifyZeroInteractions, when, doReturn}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._

object HttpArchiveLoginDlgControllerSpec {
  /** Constant for a test user name. */
  private val UserName = "scott"

  /** Constant for a password. */
  private val Password = "tiger"

  /** Constant for the name of the realm. */
  private val Realm = "MyHttpOnlineArchive"
}

/**
  * Test class for ''HttpArchiveLoginDlgController''.
  */
class HttpArchiveLoginDlgControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import HttpArchiveLoginDlgControllerSpec._

  "An HttpArchiveLoginDlgController" should "have dummy window listener implementations" in {
    val helper = new LoginControllerTestHelper

    helper.checkWindowEvents()
  }

  it should "initialize the field for the realm name" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .verifyRealmTextInitialized()
  }

  it should "handle a click on the login button" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .prepareCredentials()
      .loginClicked()
      .verifyWindowClosed()
    helper.messageBus.expectMessageType[LoginStateChanged] should be(LoginStateChanged(
      Realm, Some(UserCredentials(UserName, Password))
    ))
  }

  it should "handle a click on the cancel button" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .cancelClicked()
      .verifyWindowClosed()
    helper.messageBus.expectNoMessage(10.millis)
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

    /** Mock for the login button. */
    private val btnLogin = mock[ComponentHandler[Boolean]]

    /** Mock for the cancel button. */
    private val btnCancel = mock[ComponentHandler[Boolean]]

    /** Mock for the handler for the real text field. */
    private val realmHandler = mock[StaticTextHandler]

    /** Mock for the window representing the dialog. */
    private val window = mock[Window]

    /** The test controller. */
    val controller: HttpArchiveLoginDlgController = createController()

    /**
      * Sends a window open event to the test controller.
      *
      * @return this test helper
      */
    def openWindow(): LoginControllerTestHelper = {
      controller windowOpened windowEvent()
      this
    }

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
    def cancelClicked(): LoginControllerTestHelper =
      simulateButtonClick(btnCancel)

    /**
      * Checks that the dialog window has been closed.
      *
      * @return this test helper
      */
    def verifyWindowClosed(): LoginControllerTestHelper = {
      verify(window).close(false)
      this
    }

    /**
      * Checks that the text field for the realm has been initialized.
      *
      * @return this test helper
      */
    def verifyRealmTextInitialized(): LoginControllerTestHelper = {
      verify(realmHandler).setText(Realm)
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
      controller windowClosed event
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
      * Creates a mock window event.
      *
      * @return the mock window event
      */
    private def windowEvent(): WindowEvent = {
      val event = mock[WindowEvent]
      doReturn(window).when(event).getSourceWindow
      event
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
      * Creates the test controller instance.
      *
      * @return the test controller
      */
    private def createController(): HttpArchiveLoginDlgController =
      new HttpArchiveLoginDlgController(messageBus, txtUser, txtPassword, btnLogin, btnCancel,
        realmHandler, Realm)
  }

}
