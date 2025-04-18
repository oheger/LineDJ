/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archiveadmin

import java.util.concurrent.atomic.AtomicReference

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerRegistration
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider
import de.oliver_heger.linedj.platform.mediaifc.ext.{ArchiveAvailabilityExtension, StateListenerExtension}
import de.oliver_heger.linedj.platform.ui.DurationTransformer
import de.oliver_heger.linedj.shared.archive.metadata._
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, StaticTextData}
import net.sf.jguiraffe.gui.builder.components.tags.StaticTextDataImpl
import net.sf.jguiraffe.transform.Transformer
import org.apache.commons.logging.LogFactory

import scala.annotation.tailrec
import scala.beans.BeanProperty
import scala.util.{Failure, Success}

object ArchiveAdminController:
  /** Resource ID for the name of the ''days'' unit. */
  final val ResUnitDays = "unit_days"

  /** Resource ID for the name of the ''hours'' unit. */
  final val ResUnitHours = "unit_hours"

  /** Resource ID for the name of the ''minutes'' unit. */
  final val ResUnitMinutes = "unit_minutes"

  /** Resource ID for the name of the ''seconds'' unit. */
  final val ResUnitSeconds = "unit_seconds"

  /** Factor for converting bytes to mega bytes. */
  private val MegaBytes = 1024.0 * 1024

  /** Bean name for the action store. */
  private val BeanActionStore = "ACTION_STORE"

  /** The action for starting a media scan. */
  private val ActionStartScan = "startScanAction"

  /** The action for canceling a media scan. */
  private val ActionCancelScan = "cancelScanAction"

  /** The action for opening the window with metadata files. */
  private val ActionMetaDataFiles = "metaDataFilesAction"

  /** A set containing all managed actions. */
  private val ManagedActions = Set(ActionStartScan, ActionCancelScan, ActionMetaDataFiles)

  /**
    * An initial state of the union archive that is set before the first state
    * update event is received.
    */
  private val InitialUnionArchiveState = MetadataState(mediaCount = 0, songCount = 0, size = 0,
    duration = 0, scanInProgress = false, updateInProgress = false, archiveCompIDs = Set.empty)

  /**
    * A string to be displayed in the UI to indicate that data is currently
    * loaded.
    */
  private[archiveadmin] val LoadingIndicator = "?"

  /**
    * A string to be displayed in the UI to indicate that an error occurred
    * while loading archive statistics.
    */
  private[archiveadmin] val ErrorIndicator = "!"

  /**
    * Creates a ''StaticTextData'' object with the specified properties.
    *
    * @param text the text
    * @param icon the icon
    * @return the data object
    */
  private def staticTextData(text: String, icon: AnyRef): StaticTextData =
    val data = new StaticTextDataImpl
    data setText text
    data setIcon icon
    data

  /**
    * Returns a set with the names of the actions that are enabled for the
    * given archive state.
    *
    * @param archiveAvailable flag whether the archive is available
    * @param updateInProgress flag whether an update is in progress
    * @return the set with actions that need to be enabled
    */
  private def enabledActionsForState(archiveAvailable: Boolean, updateInProgress: Boolean):
  Set[String] =
    if !archiveAvailable then Set.empty
    else if updateInProgress then Set(ActionCancelScan)
    else Set(ActionStartScan)

/**
  * The controller class for the archive admin UI.
  *
  * This class is mainly responsible for updating UI controls when events from
  * the archive are received. To receive these events, some consumers are
  * registered. Note that this controller does not directly interact with the
  * UI, but it populates the properties of a form bean and then delegates to
  * the current form to initialize its fields.
  *
  * @param application          the main application class
  * @param componentBuilderData the current builder data object
  * @param stringTransformer    a transformer for producing strings
  * @param comboArchives        the handler for the combo with the archives
  * @param unionArchiveName     the name to display for the union archive
  * @param refSelectedArchive   here the currently selected archive is stored
  */
class ArchiveAdminController(application: ArchiveAdminApp,
                             componentBuilderData: ComponentBuilderData,
                             stringTransformer: Transformer,
                             comboArchives: ListComponentHandler,
                             unionArchiveName: String,
                             refSelectedArchive: AtomicReference[String])
  extends ConsumerRegistrationProvider:

  import ArchiveAdminController._

  /** The logger. */
  private val log = LogFactory.getLog(getClass)

  /** The form bean. */
  private val formBean = createFormBean()

  /** The action store. */
  private lazy val actionStore =
    componentBuilderData.getBeanContext.getBean(BeanActionStore).asInstanceOf[ActionStore]

  /** Text to be displayed if the archive is not available. */
  @BeanProperty var stateUnavailableText: String = _

  /** Text to be displayed if a media scan is in progress. */
  @BeanProperty var stateScanInProgressText: String = _

  /** Text to be displayed if no media scan is in progress. */
  @BeanProperty var stateNoScanInProgressText: String = _

  /** Icon to be displayed if the archive is not available. */
  @BeanProperty var stateUnavailableIcon: AnyRef = _

  /** Icon to be displayed if a media scan is in progress. */
  @BeanProperty var stateScanInProgressIcon: AnyRef = _

  /** Icon to be displayed if no media scan is in progress. */
  @BeanProperty var stateNoScanInProgressIcon: AnyRef = _

  /** Stores the latest state update event. */
  private var unionArchiveState = InitialUnionArchiveState

  /** A cache for statistics retrieved for the archive components. */
  private var archiveStatistics = Map.empty[String, ArchiveComponentStatistics]

  /**
    * Stores the name of the archive component representing the union ID. This
    * is the passed in union archive name per default; if there is only a
    * single archive component, the name of this component is used.
    */
  private var unionArchiveComponentID = unionArchiveName

  /**
    * The consumer registrations for this controller.
    */
  override val registrations: Iterable[ConsumerRegistration[_]] = List(
    StateListenerExtension.StateListenerRegistration(ComponentID(), consumeStateEvent),
    ArchiveAvailabilityExtension.ArchiveAvailabilityRegistration(ComponentID(),
      consumeAvailabilityEvent)
  )

  /**
    * Notification method that reports a change in the selection of the
    * archives combo box. In reaction of this change the metadata for the
    * newly selected archive has to be displayed. If it has not yet been
    * retrieved, it has to be fetched from the metadata union actor.
    */
  def archiveSelectionChanged(): Unit =
    val archiveID = comboArchives.getData.asInstanceOf[String]
    refSelectedArchive set archiveID
    if unionArchiveComponentID == archiveID then
      formatStats(unionArchiveState.mediaCount, unionArchiveState.songCount,
        unionArchiveState.size, unionArchiveState.duration)
      updateForm()
    else
      archiveStatistics.get(archiveID).fold(loadStatistics(archiveID))(updateStatistics)

    enableAction(ActionMetaDataFiles, archiveID != unionArchiveName)

  /**
    * The consumer function for processing metadata state events.
    *
    * @param event the state event
    */
  private def consumeStateEvent(event: MetadataStateEvent): Unit =
    event match
      case MetadataStateUpdated(state) =>
        formatStats(state.mediaCount, state.songCount, state.size, state.duration)
        val data = archiveStatusForUpdateEvent(state)
        updateArchiveStatus(data)
        updateForm()
        updateActions(enabledActionsForState(archiveAvailable = true,
          updateInProgress = state.updateInProgress))
        unionArchiveState = state
        if !state.updateInProgress then
          initializeArchivesCombo(state.archiveCompIDs)

      case MetadataUpdateInProgress =>
        updateArchiveStatus(archiveStatusForScanFlag(scanInProgress = true))
        updateForm()
        updateActions(enabledActionsForState(archiveAvailable = true, updateInProgress = true))

      case MetadataUpdateCompleted =>
        updateArchiveStatus(archiveStatusForScanFlag(scanInProgress = false))
        updateForm()
        updateActions(enabledActionsForState(archiveAvailable = true,
          updateInProgress = false))
        initializeArchivesCombo(unionArchiveState.archiveCompIDs)
        comboArchives.setEnabled(true)

      case MetadataScanStarted =>
        comboArchives.setEnabled(false)
        if comboArchives.getListModel.size() > 0 then
          selectUnionArchive()

      case _ =>

  /**
    * Populates the fields of the form bean related to statistics information
    * with the given values.
    *
    * @param mediaCount the number of media
    * @param songCount  the number of songs
    * @param size       the file size
    * @param duration   the playback duration
    */
  private def formatStats(mediaCount: Int, songCount: Int, size: Long, duration: Long): Unit =
    def formatText(value: Any, data: StaticTextData): Unit =
      data setText stringTransformer.transform(value,
        componentBuilderData.getTransformerContext).asInstanceOf[String]

    val appCtx = application.getApplicationContext
    formatText(mediaCount, formBean.mediaCount)
    formatText(songCount, formBean.songCount)
    formatText(size / MegaBytes, formBean.fileSize)
    formatText(DurationTransformer.formatLongDuration(duration, appCtx.getResourceText(ResUnitDays),
      appCtx.getResourceText(ResUnitHours), appCtx.getResourceText(ResUnitMinutes),
      appCtx.getResourceText(ResUnitSeconds)), formBean.playbackDuration)

  /**
    * The consumer function for processing archive availability events.
    *
    * @param event the availability event
    */
  private def consumeAvailabilityEvent(event: MediaFacade.MediaArchiveAvailabilityEvent): Unit =
    event match
      case MediaFacade.MediaArchiveUnavailable =>
        updateArchiveStatus(staticTextData(stateUnavailableText, stateUnavailableIcon))
        updateForm()
        updateActions(enabledActionsForState(archiveAvailable = false,
          updateInProgress = false))

      case _ => // ignore because this is handled by state update events

  /**
    * Generates the archive status data based on the given scan in progress
    * flag.
    *
    * @param scanInProgress the flag whether a scan in progress
    * @return the status to be displayed for the archive
    */
  private def archiveStatusForScanFlag(scanInProgress: Boolean): StaticTextData =
    if scanInProgress then
      staticTextData(stateScanInProgressText, stateScanInProgressIcon)
    else staticTextData(stateNoScanInProgressText, stateNoScanInProgressIcon)

  /**
    * Generates the archive status data based on the given state from an
    * update event.
    *
    * @param state the state
    * @return the status to be displayed for the archive
    */
  private def archiveStatusForUpdateEvent(state: MetadataState): StaticTextData =
    archiveStatusForScanFlag(state.updateInProgress)

  /**
    * Updates the status property of the form bean.
    *
    * @param data the data for the updated status
    */
  private def updateArchiveStatus(data: StaticTextData): Unit =
    formBean.archiveStatus = data

  /**
    * Updates the statistics fields of the UI based on the given data.
    *
    * @param stats the statistics
    */
  private def updateStatistics(stats: ArchiveComponentStatistics): Unit =
    formatStats(stats.mediaCount, stats.songCount, stats.size, stats.duration)
    updateForm()

  /**
    * Requests statistics for the given archive component from the union
    * archive and updates the UI when the information becomes available.
    *
    * @param archiveID the ID of the archive
    */
  private def loadStatistics(archiveID: String): Unit =
    if archiveID != null then
      log.info(s"Retrieving statistics for archive '$archiveID'.")
      updateStatisticsWithIndicator(LoadingIndicator)

      application.invokeActor(application.mediaFacadeActors.metadataManager,
        GetArchiveComponentStatistics(archiveID)).executeUIThread[ArchiveComponentStatistics, Unit]:
        case Success(stats) =>
          archiveStatistics += (stats.archiveComponentID -> stats)
          if isArchiveSelected(archiveID) then
            updateStatistics(stats)
        case Failure(exception) =>
          log.error(s"Error when loading statistics for $archiveID", exception)
          if isArchiveSelected(archiveID) then
            updateStatisticsWithIndicator(ErrorIndicator)

  /**
    * Checks if the archive component specified is currently selected.
    *
    * @param archiveID the archive ID
    * @return '''true''' if this component is currently selected; '''false'''
    *         otherwise
    */
  private def isArchiveSelected(archiveID: String): Boolean =
    archiveID == comboArchives.getData

  /**
    * Updates the statistics fields in the UI to display the given indicator
    * string.
    *
    * @param indicator the indicator string to be displayed
    */
  private def updateStatisticsWithIndicator(indicator: String): Unit =
    formBean.mediaCount setText indicator
    formBean.songCount setText indicator
    formBean.fileSize setText indicator
    formBean.playbackDuration setText indicator
    updateForm()

  /**
    * Updates the form with the current properties set in the form bean. This
    * method must be called whenever changes in the archive's state should be
    * made visible.
    */
  private def updateForm(): Unit =
    componentBuilderData.getForm initFields formBean

  /**
    * Updates the enabled state of the managed actions. All actions referenced
    * in the passed in set are enabled; all others are disabled.
    *
    * @param enabledActions set with the names of the actions to enable
    */
  private def updateActions(enabledActions: Set[String]): Unit =
    ManagedActions foreach { a =>
      enableAction(a, enabledActions contains a)
    }

  /**
    * Sets the enabled state of the action with the given name.
    *
    * @param name    the action name
    * @param enabled the enabled state to be set
    */
  private def enableAction(name: String, enabled: Boolean): Unit =
    val action = actionStore getAction name
    action.setEnabled(enabled)

  /**
    * Selects the union archive in the combo box with archives. This is the
    * first entry in the combo box.
    */
  private def selectUnionArchive(): Unit =
    val unionArchive = comboArchives.getListModel.getValueObject(0)
    comboArchives.setData(unionArchive)

  /**
    * Removes all entries from the combo box for archives.
    */
  private def clearArchivesCombo(): Unit =
    @tailrec def removeItem(idx: Int): Unit =
      if idx >= 0 then
        comboArchives.removeItem(idx)
        removeItem(idx - 1)

    removeItem(comboArchives.getListModel.size() - 1)

  /**
    * Populates the combo box for the known archives with the given set of
    * archive names. If the number of archives is different than 1, an entry is
    * added for the union archive.
    *
    * @param archiveNames the set with the archive names
    */
  private def initializeArchivesCombo(archiveNames: Set[String]): Unit =
    clearArchivesCombo()
    val sortedArchiveNames = archiveNames.toList.sorted
    val modelArchiveNames = if sortedArchiveNames.size == 1 then sortedArchiveNames
    else unionArchiveName :: sortedArchiveNames
    modelArchiveNames.zipWithIndex.foreach { e =>
      comboArchives.addItem(e._2, e._1, e._1)
    }

    archiveStatistics = Map.empty
    unionArchiveComponentID = if sortedArchiveNames.size == 1 then sortedArchiveNames.head
    else unionArchiveName
    comboArchives.setData(unionArchiveComponentID)

  /**
    * Creates the form bean instance and initializes all static text
    * properties.
    *
    * @return the form bean instance
    */
  private def createFormBean(): ArchiveAdminUIData =
    val bean = new ArchiveAdminUIData
    bean.mediaCount = new StaticTextDataImpl
    bean.songCount = new StaticTextDataImpl
    bean.fileSize = new StaticTextDataImpl
    bean.playbackDuration = new StaticTextDataImpl
    bean
