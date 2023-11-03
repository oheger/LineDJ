/*
 * Copyright 2015-2023 The Developers Team.
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
import de.oliver_heger.linedj.pleditor.ui.config.PlaylistEditorConfig
import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator

object OpenExportSettingsDlgCommand:
  /**
   * The name of the property under which the songs to be exported are stored
   * in the builder data.
   */
  val ExportSongsPropertyKey = "exportSongs"

  /**
   * The name of the property under which the export settings are stored in the
   * builder data.
   */
  val ExportSettingsPropertyKey = "exportSettings"

/**
 * A command class which is responsible for opening the dialog with export
 * settings.
 *
 * The main functionality for opening a dialog window is already implemented by
 * ''OpenWindowCommand''. However, before the dialog can be opened, it has to
 * be ensured that some data is fetched and stored in the context to be
 * available for the builder script. This is done by this command
 * implementation. Mainly the list of songs to be exported has to be provided
 * to the dialog controller.
 *
 * @param scriptLocator the locator for the builder script
 * @param config the object with configuration settings
 * @param exportSongs the list with songs to be exported
 */
class OpenExportSettingsDlgCommand(scriptLocator: Locator, config: PlaylistEditorConfig,
                                   exportSongs: java.util.List[SongData])
  extends OpenWindowCommand(scriptLocator):

  import OpenExportSettingsDlgCommand._

  /**
   * @inheritdoc This implementation stores some additional properties in the
   *             builder data object, so that they are available when the
   *             builder script gets executed.
   */
  override protected[plexport] def prepareBuilderData(builderData: ApplicationBuilderData): Unit =
    super.prepareBuilderData(builderData)
    builderData.addProperty(ExportSongsPropertyKey, exportSongs)
    builderData.addProperty(ExportSettingsPropertyKey, createExportSettings())

  /**
   * Creates an object with export settings from the central configuration.
   * @return the object with export settings
   */
  private def createExportSettings(): ExportSettings =
    val settings = new ExportSettings
    settings.clearMode = config.exportClearMode
    settings.targetDirectory = config.exportPath
    settings
