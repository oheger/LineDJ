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

import java.security.Key

import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.crypt.KeyGenerator
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.gui.builder.event.FormActionEvent
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent}
import net.sf.jguiraffe.gui.forms.ComponentHandler
import org.mockito.Mockito.{doReturn, verify, verifyZeroInteractions, when}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

object HttpArchiveDlgControllerSpec {
  /** Constant for a test user name. */
  private val UserName = "scott"

  /** Constant for a password. */
  private val Password = "tiger"

  /** Constant for the name of the realm. */
  private val Realm = "MyHttpOnlineArchive"

  /** Constant for the name of a test archive. */
  private val Archive = "MyTestArchive"
}

/**
  * Test class for the dialog controllers for HTTP archives.
  */
class HttpArchiveDlgControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import HttpArchiveDlgControllerSpec._

  "An HttpArchiveLoginDlgController" should "have dummy window listener implementations" in {
    val helper = new LoginControllerTestHelper

    helper.checkWindowEvents()
  }

  it should "initialize the field for the realm name" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .verifyPromptTextInitialized(Realm)
  }

  it should "handle a click on the login button" in {
    val helper = new LoginControllerTestHelper

    helper.openWindow()
      .prepareCredentials()
      .okClicked()
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

  "An HttpArchiveUnlockDlgController" should "initialize the field for the archive name" in {
    val helper = new UnlockControllerTestHelper

    helper.openWindow()
      .verifyPromptTextInitialized(Archive)
  }

  it should "handle a click on the cancel button" in {
    val helper = new UnlockControllerTestHelper

    helper.openWindow()
      .cancelClicked()
      .verifyWindowClosed()
    helper.messageBus.expectNoMessage(10.millis)
  }

  it should "handle a click on the unlock button" in {
    val helper = new UnlockControllerTestHelper

    helper.openWindow()
      .preparePassword()
      .okClicked()
      .verifyWindowClosed()
    helper.messageBus.expectMessageType[LockStateChanged] should be(LockStateChanged(Archive,
      Some(helper.key)))
  }

  /**
    * A test helper base class managing dependencies for the controller under
    * test. This class implements the major part of the functionality required
    * by tests for controllers. Derived classes only have to make sure that a
    * correct test controller instance is created.
    */
  private abstract class DlgControllerTestHelper {
    /** The message bus. */
    val messageBus = new MessageBusTestImpl

    /** Mock for the login button. */
    private val btnLogin = mock[ComponentHandler[Boolean]]

    /** Mock for the cancel button. */
    private val btnCancel = mock[ComponentHandler[Boolean]]

    /** Mock for the handler for the real text field. */
    private val realmHandler = mock[StaticTextHandler]

    /** Mock for the window representing the dialog. */
    private val window = mock[Window]

    /** The test controller. */
    private lazy val controller: HttpArchiveDlgController =
      createController(btnLogin, btnCancel, realmHandler)

    /**
      * Sends a window open event to the test controller.
      *
      * @return this test helper
      */
    def openWindow(): this.type = {
      controller windowOpened windowEvent()
      this
    }

    /**
      * Simulates a click on the OK button.
      *
      * @return this test helper
      */
    def okClicked(): this.type =
      simulateButtonClick(btnLogin)

    /**
      * Simulates a click on the logout button.
      *
      * @return this test helper
      */
    def cancelClicked(): this.type =
      simulateButtonClick(btnCancel)

    /**
      * Checks that the dialog window has been closed.
      *
      * @return this test helper
      */
    def verifyWindowClosed(): this.type = {
      verify(window).close(false)
      this
    }

    /**
      * Checks that the text field for the name has been initialized.
      * @param name the expected name
      * @return this test helper
      */
    def verifyPromptTextInitialized(name: String): this.type = {
      verify(realmHandler).setText(name)
      this
    }

    /**
      * Invokes all window listener methods and checks that they do not have
      * an effect.
      *
      * @return this test helper
      */
    def checkWindowEvents(): this.type = {
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
    def sendOnMessageBus(msg: Any): this.type = {
      messageBus publishDirectly msg
      this
    }

    /**
      * Creates the test controller instance using the given parameters.
      * Concrete sub classes must define this method to create a test instance
      * of the correct type.
      * @param btnOk the OK button handler
      *              @param btnCancel the cancel button handler
      * @param txtPrompt the prompt text handler
      * @return the test controller
      */
    protected def createController(btnOk: ComponentHandler[_], btnCancel: ComponentHandler[_],
                                   txtPrompt: StaticTextHandler): HttpArchiveDlgController

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
    private def simulateButtonClick(btn: ComponentHandler[Boolean]): this.type = {
      val event = new FormActionEvent(this, btn, "someButton", "someCmd")
      controller actionPerformed event
      this
    }
  }

  /**
    * A concrete test helper class for ''HttpArchiveLoginDlgController''.
    */
  private class LoginControllerTestHelper extends DlgControllerTestHelper {
    /** Mock for the user input field. */
    private val txtUser = mock[ComponentHandler[String]]

    /** Mock for the password input field. */
    private val txtPassword = mock[ComponentHandler[String]]

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

    override protected def createController(btnOk: ComponentHandler[_], btnCancel: ComponentHandler[_],
                                            txtPrompt: StaticTextHandler): HttpArchiveDlgController =
      new HttpArchiveLoginDlgController(messageBus, txtUser, txtPassword, btnOk, btnCancel,
        txtPrompt, Realm)
  }

  /**
    * A concrete test helper class for ''HttpArchiveUnlockDlgController''.
    */
  private class UnlockControllerTestHelper extends DlgControllerTestHelper {
    /** Mock for the password input field.*/
    private val txtPassword = mock[ComponentHandler[String]]

    /** Mock for the key generator.*/
    private val keyGen = mock[KeyGenerator]

    /**
      * Prepares the mock text field for the password to return the test
      * password and generates a mock key that is returned by the key
      * generator.
      * @return the mock key that corresponds to the test password
      */
    def preparePassword(): UnlockControllerTestHelper = {
      val key = mock[Key]
      when(txtPassword.getData).thenReturn(Password)
      when(keyGen.generateKey(Password)).thenReturn(key)
      this
    }

    /**
      * Returns the key for crypt operations that is used by the mock key
      * generator.
      * @return the crypt key
      */
    def key: Key = keyGen.generateKey(Password)

    override protected def createController(btnOk: ComponentHandler[_], btnCancel: ComponentHandler[_],
                                            txtPrompt: StaticTextHandler): HttpArchiveDlgController =
      new HttpArchiveUnlockDlgController(messageBus, txtPassword, btnOk, btnCancel, txtPrompt, Archive, keyGen)
  }
}