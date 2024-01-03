/*
 * Copyright 2015-2024 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archiveadmin

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.{AvailableMediaRegistration, AvailableMediaUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.ext.StateListenerExtension.{StateListenerRegistration, StateListenerUnregistration}
import de.oliver_heger.linedj.shared.archive.media.AvailableMedia
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.shared.archive.union.GetArchiveMetaDataFileInfo
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import net.sf.jguiraffe.gui.builder.utils.MessageOutput
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener, WindowUtils}
import net.sf.jguiraffe.resources.Message
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.ActorRef

import scala.beans.BeanProperty
import scala.collection.immutable.Seq
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

object MetaDataFilesController:
  /**
    * The name of the refresh action. This action requests current file
    * information from the archive.
    */
  val ActionRefresh = "refreshAction"

  /**
    * The name of the action for removing the selected meta data files.
    */
  val ActionRemoveFiles = "removeFilesAction"

  /**
    * The name of the property in the builder script that contains the
    * currently selected archive ID.
    */
  val PropSelectedArchiveID = "selectedArchive"

  /** Status line message indicating scan in progress. */
  private val MsgScanning = new Message("files_state_scanning")

  /** Status line message indicating that the archive is disconnected. */
  private val MsgDisconnected = new Message("files_state_disconnected")

  /** Status line message indicating that a file info request has been sent. */
  private val MsgLoading = new Message("files_state_loading")

  /** Status line message indicating a failed actor invocation. */
  private val MsgErrorActorAccess = new Message("files_state_error_actor_access")

  /** Status line message indicating an active remove operation. */
  private val MsgRemoving = new Message("files_state_removing")

  /** The resource ID for the state ready resource. */
  private val ResIDStateReady = "files_state_ready"

  /** The resource ID for the warning text for a failed remove operation. */
  private val ResIDRemoveFailedText = "files_err_remove_msg"

  /** The resource ID for the warning title for a failed remove operation. */
  private val ResIDRemoveFailedTitle = "files_err_remove_tit"

  /** The state for a disconnected media archive. */
  private val StateDisconnected = ControllerState(MsgDisconnected, resetFileData = true)

  /** The state for scan in progress. */
  private val StateScanning = ControllerState(MsgScanning, resetFileData = true)

  /** The state for loading data about meta data files. */
  private val StateLoading = ControllerState(MsgLoading, sendFileRequest = true)

  /** The state for an active remove operation. */
  private val StateRemoving = ControllerState(MsgRemoving)

  /** The state for a failed invocation of the meta data manager actor. */
  private val StateErrorActorAccess = ControllerState(MsgErrorActorAccess,
    showProgress = false)

  /**
    * A data class storing the properties of a meta data file. The table
    * managed by this controller is populated with instances of this class.
    *
    * @param mediumName the name of the medium
    * @param checksum   the checksum of the medium
    */
  case class MetaDataFileData(@BeanProperty mediumName: String, @BeanProperty checksum: String)

  /**
    * An internally used class representing the current state of the
    * controller. The state determines things like the text to be displayed in
    * the status line and whether actions are enabled or not. It switches
    * depending on received events.
    *
    * @param statusMessage   the message to be displayed in the status line
    * @param sendFileRequest a flag whether this state requires a request to
    *                        load file data
    * @param refreshEnabled  a flag whether the refresh action is enabled
    * @param showProgress    a flag whether the progress indicator is visible
    * @param resetFileData   a flag whether data about meta data files should be
    *                        cleaned
    */
  private case class ControllerState(statusMessage: Message, sendFileRequest: Boolean = false,
                                     refreshEnabled: Boolean = false,
                                     showProgress: Boolean = true,
                                     resetFileData: Boolean = false)


/**
  * A controller class for dealing with persistent meta data files.
  *
  * This class is the controller of a dialog box that shows a list with the
  * currently available files for persistent meta data. The checksum values
  * of the files and the media they belong to (if any) are displayed in a
  * table. An action is available for removing selected files.
  *
  * In order to keep the displayed information up-to-date, the class needs to
  * keep track on the archive state. When the archive becomes unavailable, no
  * actions are allowed. When it becomes available again (or initially) the
  * current meta data files information has to be retrieved. During a meta data
  * scan, the remove action has to be disabled.
  *
  * @param application        the associated application
  * @param applicationContext the ''ApplicationContext''
  * @param actionStore        the action store
  * @param filesTableHandler  the handler for the table control
  * @param statusLine         a text handler for the status line
  * @param progressIndicator  a widget indicating processing
  * @param archiveID          the ID of the current archive component
  */
class MetaDataFilesController(application: ArchiveAdminApp,
                              applicationContext: ApplicationContext,
                              actionStore: ActionStore, filesTableHandler: TableHandler,
                              statusLine: StaticTextHandler, progressIndicator: WidgetHandler,
                              archiveID: String)
  extends WindowListener with FormChangeListener:

  import MetaDataFilesController._

  /** Component ID for the archive available registration. */
  private val ArchiveRegistrationID = ComponentID()

  /** Component ID for the state listener registration. */
  private val StateListenerRegistrationID = ComponentID()

  /** Component ID for the available media registration. */
  private val MediaRegistrationID = ComponentID()

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** The message bus. */
  private val messageBus = application.clientApplicationContext.messageBus

  /** The current state the controller is in. */
  private var currentState = StateRemoving

  /** Stores data about the currently available media. */
  private var availableMedia: Option[AvailableMedia] = None

  /** Stores data about meta data files. */
  private var fileInfo: Option[MetaDataFileInfo] = None

  /** The window this controller is associated with. */
  private var window: Window = _

  override def windowDeiconified(event: WindowEvent): Unit = {}

  /**
    * @inheritdoc This implementation performs some cleanup when the window is
    *             closing.
    */
  override def windowClosing(event: WindowEvent): Unit =
    messageBus publish ArchiveAvailabilityUnregistration(ArchiveRegistrationID)
    messageBus publish StateListenerUnregistration(StateListenerRegistrationID)
    messageBus publish AvailableMediaUnregistration(MediaRegistrationID)

  override def windowClosed(event: WindowEvent): Unit = {}

  override def windowActivated(event: WindowEvent): Unit = {}

  /**
    * @inheritdoc This implementation performs initializations, e.g. the
    *             required registrations are added. It also requests data
    *             about meta data files from the archive.
    */
  override def windowOpened(event: WindowEvent): Unit =
    window = WindowUtils windowFromEvent event
    messageBus publish ArchiveAvailabilityRegistration(ArchiveRegistrationID,
      consumeArchiveAvailability)
    messageBus publish StateListenerRegistration(StateListenerRegistrationID,
      consumeStateEvents)
    messageBus publish AvailableMediaRegistration(MediaRegistrationID, consumeAvailableMedia)
    refresh()

  override def windowDeactivated(event: WindowEvent): Unit = {}

  override def windowIconified(event: WindowEvent): Unit = {}

  /**
    * Notifies this object that the selection of the table has changed. In
    * this case, the state of actions may have to be updated.
    *
    * @param e the change event (ignored)
    */
  override def elementChanged(e: FormChangeEvent): Unit =
    if currentState.refreshEnabled then
      enableAction(ActionRemoveFiles, enabled = filesTableHandler.getSelectedIndices.nonEmpty &&
        optUpdateActor.isDefined)

  /**
    * Notifies this controller that a remove operation should be triggered.
    * This method is invoked in reaction on the remove files action.
    */
  def removeFiles(): Unit =
    optUpdateActor foreach { actor =>
      switchToState(StateRemoving)
      val model = filesTableHandler.getModel
      val fileIDs = filesTableHandler.getSelectedIndices map (model.get(_)
        .asInstanceOf[MetaDataFileData].checksum)

      application.invokeActor(actor, RemovePersistentMetaData(fileIDs.toSet))
        .executeUIThread[RemovePersistentMetaDataResult, Unit]:
          case Success(result) =>
            handleRemoveResult(result)
          case Failure(exception) =>
            log.error("Error when removing meta data files!", exception)
            switchToState(StateErrorActorAccess)
    }

  /**
    * Tells this controller to refresh its information about meta data
    * files. This method is invoked in reaction on the refresh action.
    */
  def refresh(): Unit =
    switchToState(StateLoading)

  /**
    * Tells this controller to close the window. This method is invoked in
    * reaction on the close action.
    */
  def close(): Unit =
    window.close(false)

  /**
    * Returns an ''Option'' with the actor for updating meta data files. Not
    * all archives may support such updates.
    *
    * @return an ''Option'' with the update actor
    */
  private def optUpdateActor: Option[ActorRef] = fileInfo flatMap (_.optUpdateActor)

  /**
    * Handles a result of a remove meta data files operation. The UI is
    * updated accordingly.
    *
    * @param result the result
    */
  private def handleRemoveResult(result: RemovePersistentMetaDataResult): Unit =
    fileInfo foreach { info =>
      val newFiles = info.metaDataFiles filterNot (t => result.successfulRemoved.contains(t._2))
      val newUnused = info.unusedFiles diff result.successfulRemoved
      fileInfo = Some(MetaDataFileInfo(newFiles, newUnused, info.optUpdateActor))
      dataReceived()

      if result.successfulRemoved.size < result.request.checksumSet.size then
        applicationContext.messageBox(ResIDRemoveFailedText, ResIDRemoveFailedTitle,
          MessageOutput.MESSAGE_WARNING, MessageOutput.BTN_OK)
    }

  /**
    * Notifies this object that data relevant for this controller has been
    * received. If sufficient data is available, the table is now
    * populated.
    */
  private def dataReceived(): Unit =
    for
      media <- availableMedia
      info <- fileInfo
    do
      val assignedData = fetchAssignedFiles(info, media)
      val unassignedData = fetchUnassignedFiles(info)
      populateTable(assignedData ++ unassignedData)

      switchToState(ControllerState(new Message(null, ResIDStateReady, info.metaDataFiles.size,
        info.unusedFiles.size), refreshEnabled = true, showProgress = false))

  /**
    * Returns a list with ''MetaDataFileData'' objects for all files that
    * belong to an existing medium. To obtain this list, the available media
    * are joined with the information about meta data files.
    *
    * @param info  the object with information about meta data files
    * @param media the object with information about available media
    * @return a list with data about assigned files
    */
  private def fetchAssignedFiles(info: MetaDataFileInfo, media: AvailableMedia):
  List[MetaDataFileData] =
    media.mediaList.filter(info.metaDataFiles contains _._1)
      .sortWith(_._2.name < _._2.name) map { t =>
      MetaDataFileData(mediumName = t._2.name, checksum = t._2.checksum)
    }

  /**
    * Returns a list with ''MetaDataFileData'' objects for all files not
    * assigned to a medium.
    *
    * @param info the ''MetaDataFileInfo''
    * @return a list with data about unassigned files
    */
  private def fetchUnassignedFiles(info: MetaDataFileInfo): List[MetaDataFileData] =
    info.unusedFiles.toList.sortWith(_ < _) map (MetaDataFileData(null, _))

  /**
    * Populates the table with meta data files with the given content.
    *
    * @param content a sequence with the content objects to be added
    */
  private def populateTable(content: Seq[MetaDataFileData]): Unit =
    val model = filesTableHandler.getModel
    model.clear()
    model addAll content.asJava
    filesTableHandler.tableDataChanged()

  /**
    * The consumer function for available media messages.
    *
    * @param media the current ''AvailableMedia'' object
    */
  private def consumeAvailableMedia(media: AvailableMedia): Unit =
    availableMedia = Some(media)
    dataReceived()

  /**
    * The consumer function for updates of the archive availability.
    *
    * @param event the availability event
    */
  private def consumeArchiveAvailability(event: MediaFacade.MediaArchiveAvailabilityEvent): Unit =
    event match
      case MediaFacade.MediaArchiveUnavailable =>
        switchToState(StateDisconnected)

      case _ =>

  /**
    * The consumer function for meta data state change events.
    *
    * @param event the state event
    */
  private def consumeStateEvents(event: MetaDataStateEvent): Unit =
    event match
      case MetaDataScanStarted =>
        switchToState(StateScanning)
      case MetaDataScanCompleted =>
        switchToState(StateLoading)
      case MetaDataStateUpdated(MetaDataState(_, _, _, _, true, _, _)) =>
        switchToState(StateScanning)
      case MetaDataStateUpdated(MetaDataState(_, _, _, _, false, _, _)) =>
        switchToState(StateLoading)
      case _ => // ignore other events

  /**
    * Sets the enabled state of the specified action.
    *
    * @param name    the action name
    * @param enabled the new state for this action
    */
  private def enableAction(name: String, enabled: Boolean): Unit =
    actionStore.getAction(name) setEnabled enabled

  /**
    * Switches to the specified state. Controls are updated accordingly.
    *
    * @param state the new target state
    */
  private def switchToState(state: ControllerState): Unit =
    if state != currentState then
      enableAction(ActionRefresh, enabled = state.refreshEnabled)
      enableAction(ActionRemoveFiles, enabled = false)
      progressIndicator setVisible state.showProgress
      statusLine.setText(applicationContext.getResourceText(state.statusMessage))

      if state.sendFileRequest then
        filesTableHandler.getModel.clear()
        application.invokeActor(application.mediaFacadeActors.mediaManager,
          GetArchiveMetaDataFileInfo(archiveID)).executeUIThread[MetaDataFileInfo, Unit]:
          case Success(response) =>
            fileInfo = Some(response)
            dataReceived()
          case Failure(ex) =>
            log.error("Error when requesting meta data file info!", ex)
            switchToState(StateErrorActorAccess)
    if state.resetFileData then
      fileInfo = None
    currentState = state
