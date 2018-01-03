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

package de.oliver_heger.linedj.archiveadmin

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerRegistration
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider
import de.oliver_heger.linedj.platform.mediaifc.ext.{ArchiveAvailabilityExtension, StateListenerExtension}
import de.oliver_heger.linedj.platform.ui.DurationTransformer
import de.oliver_heger.linedj.shared.archive.metadata.{MetaDataScanCompleted, MetaDataState, MetaDataStateEvent, MetaDataStateUpdated}
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.components.model.StaticTextData
import net.sf.jguiraffe.gui.builder.components.tags.StaticTextDataImpl
import net.sf.jguiraffe.transform.Transformer

import scala.beans.BeanProperty

object ArchiveAdminController {
  /** Factor for converting bytes to mega bytes. */
  private val MegaBytes = 1024.0 * 1024

  /** Bean name for the action store. */
  private val BeanActionStore = "ACTION_STORE"

  /** The action for starting a media scan. */
  private val ActionStartScan = "startScanAction"

  /** The action for canceling a media scan. */
  private val ActionCancelScan = "cancelScanAction"

  /** The action for opening the window with meta data files. */
  private val ActionMetaDataFiles = "metaDataFilesAction"

  /** A set containing all managed actions. */
  private val ManagedActions = Set(ActionStartScan, ActionCancelScan, ActionMetaDataFiles)

  /**
    * Creates a ''StaticTextData'' object with the specified properties.
    *
    * @param text the text
    * @param icon the icon
    * @return the data object
    */
  private def staticTextData(text: String, icon: AnyRef): StaticTextData = {
    val data = new StaticTextDataImpl
    data setText text
    data setIcon icon
    data
  }

  /**
    * Returns a set with the names of the actions that are enabled for the
    * given archive state.
    *
    * @param archiveAvailable flag whether the archive is available
    * @param scanInProgress   flag whether a scan is in progress
    * @return the set with actions that need to be enabled
    */
  private def enabledActionsForState(archiveAvailable: Boolean, scanInProgress: Boolean):
  Set[String] = {
    if (!archiveAvailable) Set.empty
    else if (scanInProgress) Set(ActionCancelScan)
    else Set(ActionStartScan, ActionMetaDataFiles)
  }
}

/**
  * The controller class for the archive admin UI.
  *
  * This class is mainly responsible for updating UI controls when events from
  * the archive are received. To receive these events, some consumers are
  * registered. Note that this controller does not directly interact with the
  * UI, but it populates the properties of a form bean and then delegates to
  * the current form to initialize its fields.
  *
  * @param componentBuilderData the current builder data object
  * @param stringTransformer    a transformer for producing strings
  */
class ArchiveAdminController(componentBuilderData: ComponentBuilderData,
                             stringTransformer: Transformer)
  extends ConsumerRegistrationProvider {

  import ArchiveAdminController._

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

  /**
    * The consumer registrations for this controller.
    */
  override val registrations: Iterable[ConsumerRegistration[_]] = List(
    StateListenerExtension.StateListenerRegistration(ComponentID(), consumeStateEvent),
    ArchiveAvailabilityExtension.ArchiveAvailabilityRegistration(ComponentID(),
      consumeAvailabilityEvent)
  )

  /**
    * The consumer function for processing meta data state events.
    *
    * @param event the state event
    */
  private def consumeStateEvent(event: MetaDataStateEvent): Unit = {
    def formatText(value: Any, data: StaticTextData): Unit = {
      data setText stringTransformer.transform(value,
        componentBuilderData.getTransformerContext).asInstanceOf[String]
    }

    event match {
      case MetaDataStateUpdated(state) =>
        formatText(state.mediaCount, formBean.mediaCount)
        formatText(state.songCount, formBean.songCount)
        formatText(state.size / MegaBytes, formBean.fileSize)
        formatText(DurationTransformer.formatDuration(state.duration),
          formBean.playbackDuration)
        val data = archiveStatusForUpdateEvent(state)
        updateArchiveStatus(data)
        updateForm()
        updateActions(enabledActionsForState(archiveAvailable = true,
          scanInProgress = state.scanInProgress))

      case MetaDataScanCompleted =>
        updateArchiveStatus(archiveStatusForScanFlag(scanInProgress = false))
        updateForm()
        updateActions(enabledActionsForState(archiveAvailable = true,
          scanInProgress = false))

      case _ =>
    }
  }

  /**
    * The consumer function for processing archive availability events.
    *
    * @param event the availability event
    */
  private def consumeAvailabilityEvent(event: MediaFacade.MediaArchiveAvailabilityEvent): Unit = {
    event match {
      case MediaFacade.MediaArchiveUnavailable =>
        updateArchiveStatus(staticTextData(stateUnavailableText, stateUnavailableIcon))
        updateForm()
        updateActions(enabledActionsForState(archiveAvailable = false,
          scanInProgress = false))

      case _ => // ignore because this is handled by state update events
    }
  }

  /**
    * Generates the archive status data based on the given scan in progress
    * flag.
    *
    * @param scanInProgress the flag whether a scan in progress
    * @return the status to be displayed for the archive
    */
  private def archiveStatusForScanFlag(scanInProgress: Boolean): StaticTextData =
  if (scanInProgress)
    staticTextData(stateScanInProgressText, stateScanInProgressIcon)
  else staticTextData(stateNoScanInProgressText, stateNoScanInProgressIcon)

  /**
    * Generates the archive status data based on the given state from an
    * update event.
    *
    * @param state the state
    * @return the status to be displayed for the archive
    */
  private def archiveStatusForUpdateEvent(state: MetaDataState): StaticTextData =
  archiveStatusForScanFlag(state.scanInProgress)

  /**
    * Updates the status property of the form bean.
    *
    * @param data the data for the updated status
    */
  private def updateArchiveStatus(data: StaticTextData): Unit = {
    formBean setArchiveStatus data
  }

  /**
    * Updates the form with the current properties set in the form bean. This
    * method must be called whenever changes in the archive's state should be
    * made visible.
    */
  private def updateForm(): Unit = {
    componentBuilderData.getForm initFields formBean
  }

  /**
    * Updates the enabled state of the managed actions. All actions referenced
    * in the passed in set are enabled; all others are disabled.
    *
    * @param enabledActions set with the names of the actions to enable
    */
  private def updateActions(enabledActions: Set[String]): Unit = {
    ManagedActions foreach { a =>
      val action = actionStore getAction a
      action.setEnabled(enabledActions contains a)
    }
  }

  /**
    * Creates the form bean instance and initializes all static text
    * properties.
    *
    * @return the form bean instance
    */
  private def createFormBean(): ArchiveAdminUIData = {
    val bean = new ArchiveAdminUIData
    bean setMediaCount new StaticTextDataImpl
    bean setSongCount new StaticTextDataImpl
    bean setFileSize new StaticTextDataImpl
    bean setPlaybackDuration new StaticTextDataImpl
    bean
  }
}
