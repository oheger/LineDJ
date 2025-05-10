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

package de.oliver_heger.linedj.pleditor.ui.playlist.plexport

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.pleditor.ui.playlist.plexport.ExportActor.ExportData
import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator

import java.nio.file.Paths

object OpenExportProgressDlgCommand:
  /**
   * The name of the property under which the export data is stored in the
   * bean context.
   */
  final val PropExportData = "exportData"

/**
 * A specialized command for opening the dialog which displays the progress of
 * an export operation.
 *
 * This class prepares some data which is needed by [[ExportController]]. Based
 * on the settings entered by the user in the export settings dialog, a data
 * object describing the export is created. For this purpose, the content of
 * the target directory may have to be scanned. The corresponding scanner is
 * expected as constructor argument.
 *
 * @param scriptLocator the locator for the builder script
 * @param settings the bean with the data entered in the settings dialog
 * @param exportSongs the list with songs to be exported
 */
class OpenExportProgressDlgCommand(scriptLocator: Locator, 
                                   settings: ExportSettings,
                                   exportSongs: java.util.List[SongData])
  extends OpenWindowCommand(scriptLocator):

  import OpenExportProgressDlgCommand.*

  override protected[plexport] def prepareBuilderData(builderData: ApplicationBuilderData): Unit =
    super.prepareBuilderData(builderData)

    builderData.addProperty(PropExportData, createExportData())

  /**
   * Creates the ''ExportData'' object to be passed to the export controller.
   * @return the ''ExportData''
   */
  private def createExportData(): ExportData =
    import scala.jdk.CollectionConverters.*
    val exportPath = Paths get settings.targetDirectory
    ExportActor.ExportData(exportSongs.asScala.toSeq, /*scanIfNecessary(exportPath),*/ exportPath, clearTarget =
      settings.clearMode == ExportSettings.ClearAll,
      overrideFiles = settings.clearMode == ExportSettings.ClearOverride)
