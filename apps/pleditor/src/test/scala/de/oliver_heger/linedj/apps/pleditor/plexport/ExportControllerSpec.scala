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

package de.oliver_heger.linedj.apps.pleditor.plexport

import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.{ProgressBarHandler, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.FormActionEvent
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent}
import net.sf.jguiraffe.gui.forms.ComponentHandler
import net.sf.jguiraffe.resources.Message
import org.apache.pekko.stream.KillSwitch
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ExportControllerSpec:
  /** A test path to be used as current file. */
  private val TestPath = "CurrentFile.tst"

  /** An exception to simulate a failed export. */
  private val ExportFailure = new IllegalArgumentException("Test exception: Export failed.")

  /** The ID for the message bus listener registration. */
  private val MessageBusListenerID = 20261004
end ExportControllerSpec

/**
  * Test class for [[ExportController]].
  */
class ExportControllerSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import ExportControllerSpec.*

  "An ExportController" should "have dummy implementations for window listener methods" in :
    val event = mock[WindowEvent]
    val helper = new ExportControllerTestHelper

    helper.controller.windowActivated(event)
    helper.controller.windowClosing(event)
    helper.controller.windowDeactivated(event)
    helper.controller.windowDeiconified(event)
    helper.controller.windowIconified(event)
    verifyNoInteractions(event)

  it should "update controls for a remove progress message" in :
    val progressMsg = PlaylistExporter.ProgressNotification.Delete(
      totalOperations = 100,
      currentOperation = 5,
      elementName = TestPath
    )
    val helper = new ExportControllerTestHelper

    helper.openWindow().sendMessage(progressMsg)

    helper.expectStatusUpdate(5, 100, TestPath)
    verify(helper.progressRemove).setValue(5)

  it should "update controls for a copy progress message" in :
    val progressMsg = PlaylistExporter.ProgressNotification.Copy(
      totalOperations = 100,
      currentOperation = 42,
      totalSize = 1000,
      currentSize = 100,
      progressedSize = 70,
      fileName = TestPath
    )
    val helper = new ExportControllerTestHelper

    helper.sendMessage(progressMsg)

    helper.expectStatusUpdate(42, 100, TestPath)
    verify(helper.progressRemove).setValue(100)
    verify(helper.progressCopy).setValue(7)

  it should "update controls for a sequence of copy progress messages" in :
    val File2 = "anotherFile.txt"
    val progressMsg1 = PlaylistExporter.ProgressNotification.Copy(
      totalOperations = 100,
      currentOperation = 42,
      totalSize = 1000,
      currentSize = 100,
      progressedSize = 70,
      fileName = TestPath
    )
    val progressMsg2 = PlaylistExporter.ProgressNotification.Copy(
      totalOperations = 100,
      currentOperation = 43,
      totalSize = 1000,
      currentSize = 200,
      progressedSize = 150,
      fileName = File2
    )
    val helper = new ExportControllerTestHelper

    helper.sendMessage(progressMsg1)
      .sendMessage(progressMsg2)

    helper.expectStatusUpdate(43, 100, s"$File2,$TestPath")
    verify(helper.progressCopy).setValue(22)

  it should "limit the length of files text" in :
    val file = "0123456789" * 5 + ".txt"
    val expectedFile = "0123456789" * 4 + "0123456..."
    val progressMsg = PlaylistExporter.ProgressNotification.Copy(
      totalOperations = 100,
      currentOperation = 42,
      totalSize = 1000,
      currentSize = 100,
      progressedSize = 70,
      fileName = file
    )
    val helper = new ExportControllerTestHelper

    helper.sendMessage(progressMsg)

    helper.expectStatusUpdate(42, 100, expectedFile)

  it should "handle the successful end of the export" in :
    val helper = new ExportControllerTestHelper

    helper.openWindow()
      .sendMessage(PlaylistExporter.ExportResult.Success)
      .expectWindowClosed()

  it should "handle the end of an export that failed" in :
    val error = PlaylistExporter.ExportResult.Failure(ExportFailure)
    val helper = new ExportControllerTestHelper

    helper.openWindow()
      .sendMessage(error)
      .expectErrorMessage()
      .expectWindowClosed()

  it should "cancel the export when the window is closed" in :
    val helper = new ExportControllerTestHelper

    helper.openWindow()
      .closeWindow()
      .expectExportCanceled()

  it should "react on the cancel button" in :
    val helper = new ExportControllerTestHelper

    helper.openWindow()
      .simulateCancelButton()
      .expectExportCanceled()

  it should "remove the message bus listener registration when the window is closed" in :
    val helper = new ExportControllerTestHelper

    helper.openWindow()
      .closeWindow()
      .expectMessageBusUnregistration()

  /**
    * A test helper class managing the dependencies of the test controller.
    */
  private class ExportControllerTestHelper:
    /** Mock for the remove progress bar handler. */
    val progressRemove: ProgressBarHandler = mock[ProgressBarHandler]

    /** Mock for the copy progress bar handler. */
    val progressCopy: ProgressBarHandler = mock[ProgressBarHandler]

    /** Mock for the current file text handler. */
    private val textFile: StaticTextHandler = mock[StaticTextHandler]

    /** Mock for the window. */
    private val window: Window = mock[Window]

    /** Mock for the application context. */
    private val applicationContext: ApplicationContext = mock[ApplicationContext]

    /** Mock for the kill switch to cancel the export process. */
    private val killSwitch: KillSwitch = mock

    /** Mock for the system message bus. */
    private val messageBus: MessageBus = createMessageBus()

    /** Mock for the playlist exporter. */
    private val exporter: PlaylistExporter = createExporter()

    /** The test controller. */
    val controller = new ExportController(
      applicationContext,
      messageBus,
      exporter,
      progressRemove,
      progressCopy,
      textFile
    )

    /**
      * Simulates the opening of the window. At this time the controller does
      * most of its initialization.
      *
      * @return this helper object
      */
    def openWindow(): ExportControllerTestHelper =
      val event = new WindowEvent(this, window, WindowEvent.Type.WINDOW_OPENED)
      controller.windowOpened(event)
      this

    /**
      * Sends a message to the test controller via the message bus.
      *
      * @param msg the message
      * @return this test helper
      */
    def sendMessage(msg: Any): ExportControllerTestHelper =
      controller.receive(msg)
      this

    /**
      * Sends an action event to the test controller that simulates a click on
      * the cancel button.
      *
      * @return this test helper
      */
    def simulateCancelButton(): ExportControllerTestHelper =
      val handler = mock[ComponentHandler[_]]
      val actionEvent = new FormActionEvent(this, handler, "someControl", "someCommand")
      controller.actionPerformed(actionEvent)
      verify(handler).setEnabled(false)
      this

    /**
      * Simulates the closing of the window associated with the controller.
      *
      * @return this test helper
      */
    def closeWindow(): ExportControllerTestHelper =
      controller.windowClosed(null)
      this

    /**
      * Expects that the window is closed after an export operation is done.
      *
      * @return this test helper
      */
    def expectWindowClosed(): ExportControllerTestHelper =
      verify(window).close(true)
      this

    /**
      * Expects an update of the control for the currently processed file(s).
      *
      * @param currentOperation the current operation index
      * @param totalOperations  the number of total operations
      * @param files            the text for the files in progress
      * @return this test helper
      */
    def expectStatusUpdate(currentOperation: Int, totalOperations: Int, files: String): ExportControllerTestHelper =
      val expectedString = s"($currentOperation/$totalOperations) $files"
      verify(textFile).setText(expectedString)
      this

    /**
      * Expects an error message to be displayed for a failed export.
      *
      * @return this test helper
      */
    def expectErrorMessage(): ExportControllerTestHelper =
      verify(applicationContext).messageBox(
        new Message(null, "exp_failure_message", ExportFailure),
        "exp_failure_title",
        MessageOutput.MESSAGE_ERROR,
        MessageOutput.BTN_OK
      )
      this

    /**
      * Expects that the export operation was canceled using the kill switch
      * provided by the exporter.
      *
      * @return this test helper
      */
    def expectExportCanceled(): ExportControllerTestHelper =
      verify(killSwitch, atMostOnce()).shutdown()
      this

    /**
      * Verifies that the controller has removed its message bus listener
      * registration.
      *
      * @return this test helper
      */
    def expectMessageBusUnregistration(): ExportControllerTestHelper =
      verify(messageBus).removeListener(MessageBusListenerID)
      this

    /**
      * Creates a mock for the exporter that is prepared to be triggered for an
      * exporter run.
      *
      * @return the mock exporter
      */
    private def createExporter(): PlaylistExporter =
      val exp = mock[PlaylistExporter]
      when(exp.run()).thenReturn(killSwitch)
      exp

    /**
      * Creates a mock for the system message bus that is prepared to expect
      * a listener registration from the controller.
      *
      * @return the mock for the message bus
      */
    private def createMessageBus(): MessageBus =
      val mockBus = mock[MessageBus]
      when(mockBus.registerListener(any())).thenReturn(MessageBusListenerID)
      mockBus
