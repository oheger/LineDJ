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

package de.oliver_heger.linedj.apps.pleditor.plexport

import de.oliver_heger.linedj.apps.pleditor.SongData
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.URLLocator
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.util

object OpenExportProgressDlgCommandSpec:
  /** A test locator. */
  private val Locator = URLLocator.getInstance("https://www.test.org/progress_dlg.tst")

  /** The name of the export target directory. */
  private val ExportDirectoryName = "TestExportDirectory"

  /** Test target path for the export. */
  private val ExportPath = Paths.get(ExportDirectoryName)

  /** A test medium ID used by the test cases. */
  private val TestMediumID = Checksums.MediumChecksum("test-medium")

  /** A test list with song data to be exported. */
  private val ExportSongs = util.Arrays.asList(createSongData(1), createSongData(2))

  /**
    * Creates a test song data object.
    *
    * @param index the index to derive properties from
    * @return the test song data object
    */
  private def createSongData(index: Int): SongData =
    val title = "Song " + index
    val fileInfo = ArchiveModel.MediaFileInfo(
      metadata = MediaMetadata.UndefinedMediaData.copy(title = Some(title)),
      fileUri = MediaFileUri(s"songFile$index.mp3"),
      mediumID = TestMediumID
    )
    SongData(s"test-song-$index", Some(fileInfo))

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
    * Helper function for creating an _ExportData_ object.
    *
    * @param clearTarget   the clear target flag
    * @param overrideFiles the override files flag
    * @return the generated data object
    */
  private def exportData(clearTarget: Boolean, overrideFiles: Boolean): PlaylistExporter.ExportData =
    import scala.jdk.CollectionConverters.*
    PlaylistExporter.ExportData(songs = ExportSongs.asScala.toSeq, exportPath = ExportPath, clearTarget, overrideFiles)

/**
  * Test class for [[OpenExportProgressDlgCommand]].
  */
class OpenExportProgressDlgCommandSpec extends AnyFlatSpec, Matchers, MockitoSugar:

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
