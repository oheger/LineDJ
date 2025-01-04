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

import de.oliver_heger.linedj.platform.ui.EmptyListModel
import net.sf.jguiraffe.gui.builder.components.model.StaticTextData
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}

import scala.beans.BeanProperty

/**
  * A simple class representing the information to be displayed on the archive
  * admin UI.
  *
  * An instance of this class serves as form bean. Information about the
  * archive is written into its properties, and then the form controller is
  * triggered to populate the UI controls.
  *
  * As the information in the UI is represented in form of ''StaticText''
  * objects, all properties are of type ''StaticTextData''. The controller is
  * responsible for converting the data to be displayed to this type.
  */
class ArchiveAdminUIData:
  /** Contains information about the number of media. */
  @BeanProperty var mediaCount: StaticTextData = _

  /** Contains information about the number of songs in the archive. */
  @BeanProperty var songCount: StaticTextData = _

  /** Contains information about the total size of all media files. */
  @BeanProperty var fileSize: StaticTextData = _

  /** Contains information about the total playback duration of all songs. */
  @BeanProperty var playbackDuration: StaticTextData = _

  /** Represents the archive status, such as scan in progress, available, etc. */
  @BeanProperty var archiveStatus: StaticTextData = _

/**
  * A class representing the initial empty list model of the combo box for
  * archive components.
  */
class EmptyArchiveComponentsListModel extends EmptyListModel:
  override def getType: Class[_] = classOf[String]

/**
  * A listener class that reacts on changes of the selection of the archive
  * components combo box.
  *
  * When a change in the selection is detected the controller is invoked to
  * handle the event.
  *
  * @param controller the archive admin controller
  */
class ArchiveComponentsListChangeHandler(controller: ArchiveAdminController) extends FormChangeListener:
  override def elementChanged(formChangeEvent: FormChangeEvent): Unit =
    controller.archiveSelectionChanged()
