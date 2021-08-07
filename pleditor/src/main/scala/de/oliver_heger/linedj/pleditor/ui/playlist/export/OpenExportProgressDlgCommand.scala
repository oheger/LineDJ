/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.playlist.export

import java.io.IOException
import java.nio.file.{Path, Paths}

import de.oliver_heger.linedj.pleditor.ui.playlist.export.ExportActor.ExportData
import de.oliver_heger.linedj.io.{DirectoryScanner, ScanResult}
import de.oliver_heger.linedj.platform.audio.model.SongData
import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator

object OpenExportProgressDlgCommand {
  /**
   * The name of the property under which the export data is stored in the
   * bean context.
   */
  val PropExportData = "exportData"

  /** Constant for an empty scan result. */
  private val EmptyScanResult = ScanResult(Nil, Nil)
}

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
 * @param scanner the directory scanner
 */
class OpenExportProgressDlgCommand(scriptLocator: Locator, settings: ExportSettings, exportSongs:
java.util.List[SongData], scanner: DirectoryScanner)
  extends OpenWindowCommand(scriptLocator) {

  import OpenExportProgressDlgCommand._

  override protected[export] def prepareBuilderData(builderData: ApplicationBuilderData): Unit = {
    super.prepareBuilderData(builderData)

    builderData.addProperty(PropExportData, createExportData())
  }

  /**
   * Creates the ''ExportData'' object to be passed to the export controller.
   * @return the ''ExportData''
   */
  private def createExportData(): ExportData = {
    import scala.jdk.CollectionConverters._
    val exportPath = Paths get settings.targetDirectory
    ExportActor.ExportData(exportSongs.asScala.toSeq, scanIfNecessary(exportPath), exportPath, clearTarget =
      settings.clearMode == ExportSettings.ClearAll,
      overrideFiles = settings.clearMode == ExportSettings.ClearOverride)
  }

  /**
   * Scans the target directory if this is required. Note: If parsing fails, an
   * empty result object is returned. This typically means that the export
   * directory is invalid. This problem will be handled later by the export
   * actor.
   * @param path the path to be scanned
   * @return the result of the scan operation
   */
  private def scanIfNecessary(path: Path): ScanResult = {
    if (settings.clearMode != ExportSettings.ClearOverride) {
      try {
        scanner scan path
      } catch {
        case _: IOException => EmptyScanResult
      }
    } else EmptyScanResult
  }
}
