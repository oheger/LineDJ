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

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.gui.builder.event.FormActionEvent
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent}
import net.sf.jguiraffe.gui.forms.ComponentHandler
import net.sf.jguiraffe.resources.Message
import org.mockito.Mockito.{doReturn, verify, verifyNoInteractions, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*

object EnterCredentialControllerSpec:
  /** Constant for the name of a test credential. */
  private val CredentialName = "TestArchive.password"

  /** Constant for the value of a test credential. */
  private val CredentialValue = "scott.tiger"

  /** Text to be returned for the credential prompt. */
  private val CredentialPrompt = "prompt to enter the test credential"
end EnterCredentialControllerSpec

/**
  * Test class for [[EnterCredentialController]].
  */
class EnterCredentialControllerSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import EnterCredentialControllerSpec.*

  "An EnterCredentialController" should "have dummy window listener implementations" in :
    val helper = new EnterCredentialControllerTestHelper

    helper.checkWindowEvents()

  it should "initialize the prompt for the credential name" in :
    val helper = new EnterCredentialControllerTestHelper

    helper.openWindow()
      .verifyPromptTextInitialized(CredentialName)

  it should "handle a click on the OK button" in :
    val helper = new EnterCredentialControllerTestHelper

    helper.openWindow()
      .prepareCredential()
      .okClicked()
      .verifyWindowClosed()
    val msg = helper.messageBus.expectMessageType[LoginController.CredentialEntered]
    msg.name should be(CredentialName)
    msg.value.secret should be(CredentialValue)

  it should "handle a click on the cancel button" in :
    val helper = new EnterCredentialControllerTestHelper

    helper.openWindow()
      .cancelClicked()
      .verifyWindowClosed()
    helper.messageBus.expectNoMessage(10.millis)

  /**
    * A test helper class managing the controller to be tested and its
    * dependencies.
    */
  private class EnterCredentialControllerTestHelper:
    /** The message bus. */
    val messageBus = new MessageBusTestImpl

    /** The application context. */
    private val applicationContext = createApplicationContext()

    /** Mock for the text field for the credential value. */
    private val txtCredential = mock[ComponentHandler[String]]

    /** Mock for the OK button. */
    private val btnOk = mock[ComponentHandler[Boolean]]

    /** Mock for the cancel button. */
    private val btnCancel = mock[ComponentHandler[Boolean]]

    /** Mock for the handler for the prompt text. */
    private val txtPrompt = mock[StaticTextHandler]

    /** Mock for the window representing the dialog. */
    private val window = mock[Window]

    /** The test controller. */
    private lazy val controller = new EnterCredentialController(
      applicationContext,
      messageBus,
      txtCredential,
      btnOk,
      btnCancel,
      txtPrompt,
      CredentialName
    )

    /**
      * Sends a window open event to the test controller.
      *
      * @return this test helper
      */
    def openWindow(): this.type =
      controller windowOpened windowEvent()
      this

    /**
      * Prepares the mock text field to return a test credential value.
      *
      * @return this test helper
      */
    def prepareCredential(): this.type =
      when(txtCredential.getData).thenReturn(CredentialValue)
      this

    /**
      * Simulates a click on the OK button.
      *
      * @return this test helper
      */
    def okClicked(): this.type =
      simulateButtonClick(btnOk)

    /**
      * Simulates a click on the cancel button.
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
    def verifyWindowClosed(): this.type =
      verify(window).close(false)
      this

    /**
      * Checks that the text field for the credential name has been
      * initialized.
      *
      * @param name the expected name
      * @return this test helper
      */
    def verifyPromptTextInitialized(name: String): this.type =
      verify(txtPrompt).setText(CredentialPrompt)
      this

    /**
      * Invokes all window listener methods and checks that they do not have
      * an effect.
      *
      * @return this test helper
      */
    def checkWindowEvents(): this.type =
      val event = windowEvent()
      controller.windowActivated(event)
      controller.windowClosing(event)
      controller.windowClosed(event)
      controller.windowDeactivated(event)
      controller.windowDeiconified(event)
      controller.windowIconified(event)
      verifyNoInteractions(event)
      this

    /**
      * Creates a mock for the application context and prepares it to expect
      * requests for resources.
      *
      * @return the mock for the application context
      */
    private def createApplicationContext(): ApplicationContext =
      val context = mock[ApplicationContext]
      val expectedPromptMessage = new Message(null, "lab_credential_prompt", CredentialName)
      doReturn(CredentialPrompt).when(context).getResourceText(expectedPromptMessage)
      context

    /**
      * Creates a mock window event.
      *
      * @return the mock window event
      */
    private def windowEvent(): WindowEvent =
      val event = mock[WindowEvent]
      doReturn(window).when(event).getSourceWindow
      event

    /**
      * Invokes the action listener method of the test controller simulating a
      * button click.
      *
      * @param btn the button to be simulated
      * @return this test helper
      */
    private def simulateButtonClick(btn: ComponentHandler[Boolean]): this.type =
      val event = new FormActionEvent(this, btn, "someButton", "someCmd")
      controller.actionPerformed(event)
      this
