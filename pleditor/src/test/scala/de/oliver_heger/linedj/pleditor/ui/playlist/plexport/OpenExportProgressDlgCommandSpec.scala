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
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.URLLocator
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Path, Paths}
import java.util

object OpenExportProgressDlgCommandSpec:
  /** A test locator. */
  private val Locator = URLLocator getInstance "http://www.test.org/progress_dlg.tst"

  /** The name of the export target directory. */
  private val ExportDirectoryName = "TestExportDirectory"

  /** Test target path for the export. */
  private val ExportPath = path(ExportDirectoryName)

  /** A test list with song data to be exported. */
  private val ExportSongs = util.Arrays.asList(createSongData(1), createSongData(2))

  /**
    * Creates a path with the given name.
    *
    * @param n the name
    * @return the path
    */
  private def path(n: String): Path = Paths get n

  /**
    * Creates a test song data object.
    *
    * @param index the index to derive properties from
    * @return the test song data object
    */
  private def createSongData(index: Int): SongData =
    val title = "Song " + index
    SongData(MediaFileID(MediumID("Medium" + index, None), "song://TestSong" + index),
      MediaMetadata.UndefinedMediaData.copy(title = Some(title)), title, null, null)

  /**
    * Creates a settings object with the specified clear mode.
    *
    * @param clearMode the clear mode
    * @return the settings object
    */
  private def createSettings(clearMode: Int): ExportSettings =
    val settings = new ExportSettings
    settings.targetDirectory = ExportDirectoryName
    settings.clearMode = clearMode
    settings
  
  /**
    * Helper function for creating an ''ExportData'' object.
    *
    * @param clearTarget   the clear target flag
    * @param overrideFiles the override files flag
    * @return the generated data object
    */
  private def exportData(clearTarget: Boolean, overrideFiles: Boolean):
  ExportActor.ExportData =
    import scala.jdk.CollectionConverters.*
    ExportActor.ExportData(songs = ExportSongs.asScala.toSeq, exportPath = ExportPath, clearTarget, overrideFiles)

/**
  * Test class for ''OpenExportProgressDlgCommand''.
  */
class OpenExportProgressDlgCommandSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import OpenExportProgressDlgCommandSpec.*

  "An OpenExportProgressDlgCommand" should "pass the script locator to the base class" in :
    val command = new OpenExportProgressDlgCommand(Locator, createSettings(ExportSettings
      .ClearAll), ExportSongs)

    command.getLocator should be(Locator)

  it should "produce an ExportData object" in :
    val builderData = mock[ApplicationBuilderData]
    val settings = createSettings(ExportSettings.ClearOverride)
    val command = new OpenExportProgressDlgCommand(Locator, settings, ExportSongs)

    command prepareBuilderData builderData
    val expData = exportData(clearTarget = false, overrideFiles = true)
    verify(builderData).addProperty("exportData", expData)
