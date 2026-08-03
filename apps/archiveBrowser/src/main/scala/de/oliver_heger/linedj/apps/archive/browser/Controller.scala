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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.apps.archive.browser.Controller.MediaChanged
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.archiveclient.{ArchiveService, ArchiveStateMonitor}
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import net.sf.jguiraffe.gui.builder.components.model.ListComponentHandler
import org.apache.pekko.actor.Actor.Receive

import java.util.Locale
import scala.annotation.tailrec

object Controller:
  /**
    * An internally used message class the controller sends to itself when it
    * is notified about a change in the media data of the connected archive
    * server.
    *
    * @param media the updated media data
    */
  private[browser] case class MediaChanged(media: ArchiveModel.MediaOverview)

  /** An ordering for sorting media in the combobox. */
  private given mediumOverviewOrdering: Ordering[ArchiveModel.MediumOverview] =
    Ordering.by(_.title.toLowerCase(Locale.ROOT))
end Controller

/**
  * Controller class for the archive browser application.
  *
  * This class manages various controls to browse the content of a media
  * archive hosted by a connected archive server. Interaction with this server
  * takes place via an [[ArchiveService]] instance. The UI contains a combobox
  * to select a specific medium. It then shows different views of the songs
  * stored on the selected medium.
  *
  * @param archiveService the service to interact with the archive
  * @param messageBus     the message bus
  * @param comboMedia     the combobox to select a medium
  */
class Controller(archiveService: ArchiveService,
                 messageBus: MessageBus,
                 comboMedia: ListComponentHandler)
  extends ArchiveStateMonitor.ArchiveChangeListener[ArchiveModel.MediaOverview], MessageBusListener:
  /**
    * Initializes this controller. This function is called by the DI framework
    * when the bean is created.
    */
  def initialize(): Unit =
    archiveService.addChangeListener(this)

  /**
    * Destroys this controller. Performs cleanup. This function is called by
    * the DI framework when the application shuts down.
    */
  def destroy(): Unit =
    archiveService.removeChangeListener(this)

  override def archiveStateChanged(state: ArchiveModel.MediaOverview): Unit =
    messageBus.publish(MediaChanged(state))

  override def receive: Receive =
    case MediaChanged(media) =>
      updateMedia(media)

  /**
    * Updates the combobox with media to contain exactly the given data. This
    * function is called when an update notification for media data is 
    * received.
    *
    * @param media the updated media data
    */
  private def updateMedia(media: ArchiveModel.MediaOverview): Unit =
    val currentSelection = comboMedia.getData

    removeExistingMediaFromComboBox()
    import Controller.mediumOverviewOrdering
    media.media.sorted.zipWithIndex.foreach:
      case (medium, index) =>
        comboMedia.addItem(index, medium.title, medium.id)

    val nextSelection = if currentSelection != null && media.media.exists(_.id == currentSelection) then
      currentSelection
    else
      null
    comboMedia.setData(nextSelection)

  /**
    * Clears the current list model for the combo box.
    */
  private def removeExistingMediaFromComboBox(): Unit =
    @tailrec def clearListModel(index: Int): Unit =
      if index >= 0 then
        comboMedia removeItem index
        clearListModel(index - 1)

    clearListModel(comboMedia.getListModel.size() - 1)
