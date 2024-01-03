/*
 * Copyright 2015-2024 The Developers Team.
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

import java.io.IOException
import java.nio.file.{Path, Paths}
import java.util

import de.oliver_heger.linedj.io.{DirectoryScanner, FileData, ScanResult}
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.URLLocator
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

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
   * @param n the name
   * @return the path
   */
  private def path(n: String): Path = Paths get n

  /**
   * Creates a test song data object.
   * @param index the index to derive properties from
   * @return the test song data object
   */
  private def createSongData(index: Int): SongData =
    val title = "Song " + index
    SongData(MediaFileID(MediumID("Medium" + index, None), "song://TestSong" + index),
      MediaMetaData(title = Some(title)), title, null, null)

  /**
   * Creates a settings object with the specified clear mode.
   * @param clearMode the clear mode
   * @return the settings object
   */
  private def createSettings(clearMode: Int): ExportSettings =
    val settings = new ExportSettings
    settings.targetDirectory = ExportDirectoryName
    settings.clearMode = clearMode
    settings

  /**
   * Creates a test ''ScanResult'' object.
   * @return the scan result
   */
  private def createScanResult(): ScanResult =
    val dirs = List("dir1", "otherDir") map path
    val files = List("song1.mp3", "lala.mp3", "dumdum.ogg") map (s => FileData(path(s), 100))
    ScanResult(directories = dirs, files = files)

  /**
   * Helper function for creating an ''ExportData'' object.
   * @param scanResult the scan result
   * @param clearTarget the clear target flag
   * @param overrideFiles the override files flag
   * @return the generated data object
   */
  private def exportData(scanResult: ScanResult, clearTarget: Boolean, overrideFiles: Boolean):
  ExportActor.ExportData =
    import scala.jdk.CollectionConverters._
    ExportActor.ExportData(songs = ExportSongs.asScala.toSeq, targetContent = scanResult, exportPath =
      ExportPath, clearTarget, overrideFiles)

/**
 * Test class for ''OpenExportProgressDlgCommand''.
 */
class OpenExportProgressDlgCommandSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import OpenExportProgressDlgCommandSpec._

  "An OpenExportProgressDlgCommand" should "pass the script locator to the base class" in:
    val command = new OpenExportProgressDlgCommand(Locator, createSettings(ExportSettings
      .ClearAll), ExportSongs, mock[DirectoryScanner])

    command.getLocator should be(Locator)

  it should "produce an ExportData object if the target directory does not have to be scanned" in:
    val builderData = mock[ApplicationBuilderData]
    val scanner = mock[DirectoryScanner]
    val settings = createSettings(ExportSettings.ClearOverride)
    val command = new OpenExportProgressDlgCommand(Locator, settings, ExportSongs, scanner)

    command prepareBuilderData builderData
    val expData = exportData(ScanResult(Nil, Nil), clearTarget = false, overrideFiles = true)
    verifyNoInteractions(scanner)
    verify(builderData).addProperty("exportData", expData)

  /**
   * Helper method for checking the generated ExportData if the target
   * directory has to be scanned.
   * @param clearMode the clear mode
   * @param clearTarget expected clear target flag
   * @param overrideFiles expected override files flag
   */
  private def checkExportDataWithScan(clearMode: Int, clearTarget: Boolean, overrideFiles:
  Boolean): Unit =
    val builderData = mock[ApplicationBuilderData]
    val scanner = mock[DirectoryScanner]
    val scanResult = createScanResult()
    when(scanner.scan(ExportPath)).thenReturn(scanResult)
    val settings = createSettings(clearMode)
    val command = new OpenExportProgressDlgCommand(Locator, settings, ExportSongs, scanner)

    command prepareBuilderData builderData
    val expData = exportData(scanResult, clearTarget, overrideFiles)
    verify(builderData).addProperty("exportData", expData)

  it should "produce an ExportData object for clear mode ClearAll" in:
    checkExportDataWithScan(ExportSettings.ClearAll, clearTarget = true, overrideFiles = false)

  it should "produce an ExportData object for clear mode ClearNothing" in:
    checkExportDataWithScan(ExportSettings.ClearNothing, clearTarget = false, overrideFiles = false)

  it should "handle an exception thrown by the scanner" in:
    val builderData = mock[ApplicationBuilderData]
    val scanner = mock[DirectoryScanner]
    when(scanner.scan(ExportPath)).thenThrow(new IOException("TestException"))
    val settings = createSettings(ExportSettings.ClearAll)
    val command = new OpenExportProgressDlgCommand(Locator, settings, ExportSongs, scanner)

    command prepareBuilderData builderData
    val expData = exportData(ScanResult(Nil, Nil), clearTarget = true, overrideFiles = false)
    verify(builderData).addProperty("exportData", expData)
