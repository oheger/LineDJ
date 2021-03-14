/*
 * Copyright 2015-2021 The Developers Team.
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

import java.util.concurrent.atomic.AtomicReference

import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator

/**
  * A task class for the ''refresh meta data files'' action.
  *
  * This task class just delegates to the [[MetaDataFilesController]].
  *
  * @param controller the ''MetaDataFilesController''
  */
class RefreshFilesTask(controller: MetaDataFilesController) extends Runnable {
  override def run(): Unit = {
    controller.refresh()
  }
}

/**
  * A task class for the ''remove meta data files'' action.
  *
  * This task class just delegates to the [[MetaDataFilesController]].
  *
  * @param controller the ''MetaDataFilesController''
  */
class RemoveFilesTask(controller: MetaDataFilesController) extends Runnable {
  override def run(): Unit = {
    controller.removeFiles()
  }
}

/**
  * A task class for the action for closing the meta data files window.
  *
  * This task class just delegates to the [[MetaDataFilesController]].
  *
  * @param controller the ''MetaDataFilesController''
  */
class CloseMetaDataFilesDialogTask(controller: MetaDataFilesController) extends Runnable {
  override def run(): Unit = {
    controller.close()
  }
}

/**
  * A specialized command class for opening the dialog with meta data files for
  * the currently selected archive component.
  *
  * This class makes sure that the selection of the archive component is
  * correctly passed to the meta data files controller.
  *
  * @param locator      the locator for the dialog script
  * @param refArchiveID the reference containing the selected archive
  */
class OpenMetaDataFilesDlgCommand(locator: Locator, refArchiveID: AtomicReference[String])
  extends OpenWindowCommand(locator) {
  override def prepareBuilderData(builderData: ApplicationBuilderData): Unit = {
    super.prepareBuilderData(builderData)
    builderData.addProperty(MetaDataFilesController.PropSelectedArchiveID, refArchiveID.get())
  }
}
