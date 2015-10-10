/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.playlist.export

import java.nio.file.Path

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import de.oliver_heger.linedj.bus.MessageBusListener
import de.oliver_heger.linedj.remoting.{ActorFactory, RemoteMessageBus}
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.{ProgressBarHandler, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener}
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.gui.builder.window.{WindowUtils, Window, WindowEvent, WindowListener}
import net.sf.jguiraffe.resources.Message

object ExportController {
  /** The name of the export actor. */
  val ExportActorName = "playlistExportActor"

  /** The resource key for the dialog with an error message. */
  private val ResErrorTitle = "exp_failure_title"

  /** The resource key for a failed remove operation. */
  private val ResErrorRemove = "exp_failure_remove"

  /** The resource key for a failed copy operation. */
  private val ResErrorCopy = "exp_failure_copy"

  /**
   * Transforms the given path to a string to be displayed to the user.
   * @param p the path
   * @return the string
   */
  private def pathString(p: Path): String = p.toString
}

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
 * @param remoteBus the remote message bus
 * @param actorFactory the factory for actors
 * @param exportData describes the export operation
 * @param progressRemove handler for the progress bar for remove operations
 * @param progressCopy handler for the progress bar for copy operations
 * @param currentFile handler for the text for the current file
 */
class ExportController(applicationContext: ApplicationContext, remoteBus: RemoteMessageBus,
                       actorFactory: ActorFactory, exportData: ExportActor.ExportData,
                       progressRemove: ProgressBarHandler, progressCopy: ProgressBarHandler,
                       currentFile: StaticTextHandler) extends WindowListener with
MessageBusListener with FormActionListener {

  import ExportController._

  /** Stores a reference to the export actor. */
  private var exportActor: ActorRef = _

  /** Stores the managed window. */
  private var window: Window = _

  override def windowDeiconified(windowEvent: WindowEvent): Unit = {}

  override def windowClosing(windowEvent: WindowEvent): Unit = {}

  override def windowClosed(windowEvent: WindowEvent): Unit = {}

  override def windowActivated(windowEvent: WindowEvent): Unit = {}

  override def windowDeactivated(windowEvent: WindowEvent): Unit = {}

  override def windowIconified(windowEvent: WindowEvent): Unit = {}

  /**
   * The associated window was opened. Here the main initialization takes
   * place.
   * @param windowEvent the window event
   */
  override def windowOpened(windowEvent: WindowEvent): Unit = {
    remoteBus.bus registerListener receive

    exportActor = actorFactory.createActor(ExportActor(remoteBus), ExportActorName)
    exportActor ! exportData
    window = WindowUtils windowFromEvent windowEvent
  }

  /**
   * Reacts on a click of the cancel button. This is answered by sending a
   * cancel request to the export actor. When the export terminates a
   * corresponding message is sent on the event bus which in turn will cause a
   * shutdown.
   * @param formActionEvent the action event
   */
  override def actionPerformed(formActionEvent: FormActionEvent): Unit = {
    exportActor ! ExportActor.CancelExport
    formActionEvent.getHandler setEnabled false
  }

  /**
   * Reacts on messages on the bus. Here the messages from the export actor are
   * retrieved and processed.
   */
  override def receive: Receive = {
    case progress: ExportActor.ExportProgress =>
      currentFile setText pathString(progress.currentPath)
      progress.operationType match {
        case ExportActor.OperationType.Remove =>
          val percent = 100 * progress.currentOperation / (progress.totalOperations - exportData
            .songs.size)
          progressRemove setValue percent

        case ExportActor.OperationType.Copy =>
          val percent = 100 * progress.currentSize / progress.totalSize
          progressCopy setValue percent.toInt
          progressRemove setValue 100
      }

    case ExportActor.ExportResult(None) =>
      shutdown()

    case ExportActor.ExportResult(Some(error)) =>
      val errorResource = if (error.errorType == ExportActor.OperationType.Remove) ResErrorRemove
      else ResErrorCopy
      applicationContext.messageBox(new Message(null, errorResource, pathString
      (error.errorPath)), ResErrorTitle, MessageOutput.MESSAGE_ERROR, MessageOutput.BTN_OK)
      shutdown()
  }

  /**
   * Performs a shutdown after the export is done. Closes the associated window
   * and stops the export actor.
   */
  private def shutdown(): Unit = {
    actorFactory.actorSystem stop exportActor
    window close true
  }
}
