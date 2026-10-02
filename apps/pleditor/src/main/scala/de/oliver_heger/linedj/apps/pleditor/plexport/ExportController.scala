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

import de.oliver_heger.linedj.apps.pleditor.plexport
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.{ProgressBarHandler, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener}
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener, WindowUtils}
import net.sf.jguiraffe.resources.Message
import org.apache.pekko.actor.Actor.Receive
import org.apache.pekko.stream.KillSwitch

import scala.compiletime.uninitialized

object ExportController:
  /** The name of the export actor. */
  final val ExportActorName = "playlistExportActor"

  /** The resource key for the dialog with an error message. */
  private val ResErrorTitle = "exp_failure_title"

  /** The resource key for the message reporting a failed operation. */
  private val ResErrorMessage = "exp_failure_message"

  /**
    * The maximum length of the text displayed for the files in progress in
    * characters. Longer texts are abbreviated.
    */
  private val MaxFilesLength = 50

  /**
    * Generates a message about the current operations in progress and the
    * affected files.
    *
    * @param currentOperation the index of the current operation
    * @param totalOperations  the number of total operations
    * @param files            the files currently in progress
    * @return the resulting message
    */
  private def operationStatusMessage(currentOperation: Int,
                                     totalOperations: Int,
                                     files: Iterable[String]): String =
    val filesStr = files.mkString(",")
    val filesStrAbbreviated = if filesStr.length <= MaxFilesLength then filesStr
    else filesStr.take(MaxFilesLength - 3) + "..."
    s"($currentOperation/$totalOperations) $filesStrAbbreviated"

  /**
    * Creates the [[Message]] object for an error message for the specified
    * export error.
    *
    * @param exception the export error
    * @return the message object
    */
  private def createErrorMessage(exception: Throwable): Message =
    new Message(null, ResErrorMessage, exception)
end ExportController

/**
  * A controller class for an export operation.
  *
  * An instance of this class is created and associated with the dialog opened
  * during an export playlist operation. The controller is responsible for
  * initiating the export (which is actually executed by an actor) and
  * monitoring the progress. The associated UI has to be adapted for the
  * progress made by the export. When the export is done the dialog window is
  * closed.
  *
  * @param applicationContext the application context
  * @param messageBus         the system message bus
  * @param exporter           the object that performs the export
  * @param progressRemove     handler for the progress bar for remove operations
  * @param progressCopy       handler for the progress bar for copy operations
  * @param currentFile        handler for the text for the current file
  */
class ExportController(applicationContext: ApplicationContext,
                       messageBus: MessageBus,
                       exporter: PlaylistExporter,
                       progressRemove: ProgressBarHandler,
                       progressCopy: ProgressBarHandler,
                       currentFile: StaticTextHandler)
  extends WindowListener, MessageBusListener, FormActionListener:

  import ExportController.*

  /** Stores the managed window. */
  private var window: Window = uninitialized

  /** The kill switch to cancel the export operation. */
  private var killSwitch: KillSwitch = uninitialized

  /** The message bus listener ID. */
  private var listenerID: Int = uninitialized

  /** The object to monitor the progress of copy operations. */
  private var copyProgressState = CopyProgressState.Initial

  override def windowDeiconified(windowEvent: WindowEvent): Unit = ()

  override def windowClosing(windowEvent: WindowEvent): Unit = ()

  override def windowActivated(windowEvent: WindowEvent): Unit = ()

  override def windowDeactivated(windowEvent: WindowEvent): Unit = ()

  override def windowIconified(windowEvent: WindowEvent): Unit = ()

  /**
    * The window was closed. This implementation makes sure that an ongoing
    * export operation is canceled. Note: The export may have been canceled
    * already, but this is okay, since multiple `shutdown()` calls on a kill
    * switch are ignored.
    *
    * @param windowEvent the window event
    */
  override def windowClosed(windowEvent: WindowEvent): Unit =
    killSwitch.shutdown()
    messageBus.removeListener(listenerID)

  /**
    * The associated window was opened. This triggers the export operation.
    *
    * @param windowEvent the window event
    */
  override def windowOpened(windowEvent: WindowEvent): Unit =
    killSwitch = exporter.run()
    window = WindowUtils windowFromEvent windowEvent
    listenerID = messageBus.registerListener(receive)

  /**
    * Reacts on a click of the cancel button. This is answered by sending a
    * cancel request to the export actor. When the export terminates a
    * corresponding message is sent on the event bus which in turn will cause a
    * shutdown.
    *
    * @param formActionEvent the action event
    */
  override def actionPerformed(formActionEvent: FormActionEvent): Unit =
    killSwitch.shutdown()
    formActionEvent.getHandler.setEnabled(false)

  /**
    * Reacts on messages on the bus. Here the messages from the exporter are
    * processed.
    */
  override def receive: Receive =
    case progress: PlaylistExporter.ProgressNotification.Delete =>
      currentFile.setText(
        operationStatusMessage(progress.currentOperation, progress.totalOperations, List(progress.elementName))
      )
      val percent = math.round(100f * progress.currentOperation / progress.totalOperations)
      progressRemove.setValue(percent)

    case progress: PlaylistExporter.ProgressNotification.Copy =>
      val nextCopyState = copyProgressState.update(progress)
      currentFile.setText(
        operationStatusMessage(
          nextCopyState.lastOperation,
          nextCopyState.totalOperations,
          nextCopyState.activeOperations.map(_._2)
        )
      )
      progressCopy.setValue(nextCopyState.progress)
      progressRemove.setValue(100)
      copyProgressState = nextCopyState

    case PlaylistExporter.ExportResult.Success =>
      shutdown()

    case PlaylistExporter.ExportResult.Failure(error) =>
      applicationContext.messageBox(
        createErrorMessage(error),
        ResErrorTitle,
        MessageOutput.MESSAGE_ERROR,
        MessageOutput.BTN_OK
      )
      shutdown()

  /**
    * Performs a shutdown after the export is done. Closes the associated window
    * and stops the export actor.
    */
  private def shutdown(): Unit =
    window close true
