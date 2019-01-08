/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.config

import de.oliver_heger.linedj.pleditor.ui.playlist.export.ExportSettings
import net.sf.jguiraffe.gui.app.Application
import org.apache.commons.configuration.Configuration

object PlaylistEditorConfig {
  /** The prefix for all configuration keys. */
  private val KeyPrefix = "browser."

  /** The prefix for the export section. */
  private val ExportPrefix = KeyPrefix + "export."

  /** Key for the default export path. */
  private val KeyExportPath = ExportPrefix + "defaultPath"

  /** Key for the default export clear mode. */
  private val KeyExportClearMode = ExportPrefix + "defaultClearMode"

  /** Key for the download chunk size. */
  private val KeyDownloadChunkSize = ExportPrefix + "downloadChunkSize"

  /** Key for progress size. */
  private val KeyProgressSize = ExportPrefix + "progressSize"

  /** The default download chunk size. */
  private val DefaultDownloadChunkSize = 16384

  /** The default progress size. */
  private val DefaultProgressSize = 1024 * 1024
}

/**
 * A helper class providing access to configuration options used by the Browser
 * application.
 *
 * An instance wraps a configuration instance. It defines meaningful methods
 * for querying the several configuration options supported by this
 * application. The configuration can also be updated.
 *
 * @param app the main application object
 */
class PlaylistEditorConfig(app: Application) {

  import PlaylistEditorConfig._

  /** The wrapped user configuration. */
  val userConfiguration: Configuration = app.getUserConfiguration

  /**
   * Returns the default path to be used for export operations.
   * @return the export path
   */
  def exportPath: String = userConfiguration getString KeyExportPath

  def exportPath_=(p: String): Unit = {
    userConfiguration.setProperty(KeyExportPath, p)
  }

  /**
   * Returns the default clear mode to be used for export operations.
   * @return the export clear mode
   */
  def exportClearMode: Int = userConfiguration.getInt(KeyExportClearMode, ExportSettings
    .ClearNothing)

  def exportClearMode_=(mode: Int): Unit = {
    userConfiguration.setProperty(KeyExportClearMode, mode)
  }

  /**
    * Returns the chunk size for download operations.
    *
    * @return the download chunk size
    */
  def downloadChunkSize: Int =
    userConfiguration.getInt(KeyDownloadChunkSize, DefaultDownloadChunkSize)

  /**
    * Returns the progress size. This size controls the frequency of update
    * notifications during a copy operation. After this number of bytes has
    * been processed, the copy actor sends a notification.
    *
    * @return the size for progress notifications
    */
  def progressSize: Int =
    userConfiguration.getInt(KeyProgressSize, DefaultProgressSize)
}
