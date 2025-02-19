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

import java.util

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.pleditor.ui.config.PlaylistEditorConfig
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.locators.URLLocator
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.{eq => argEq}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object OpenExportSettingsDlgCommandSpec:
  /** A test locator. */
  private val Locator = URLLocator getInstance "http://www.test.org"

  /** A test list with song data to be exported. */
  private val ExportSongs = util.Arrays.asList(createSongData(1), createSongData(2))

  /**
   * Creates a test song data object.
   * @param index the index to derive properties from
   * @return the test song data object
   */
  private def createSongData(index: Int): SongData =
    val title = "Song " + index
    SongData(MediaFileID(MediumID("Medium" + index, None), "song://TestSong" + index),
      MediaMetadata(title = Some(title)), title, null, null)

/**
 * Test class for ''OpenExportSettingsDlgCommand''.
 */
class OpenExportSettingsDlgCommandSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import OpenExportSettingsDlgCommandSpec._

  "An OpenExportSettingsDlgCommand" should "pass its locator to the super class" in:
    val command = new OpenExportSettingsDlgCommand(Locator, mock[PlaylistEditorConfig], ExportSongs)

    command.getLocator should be(Locator)

  it should "initialize the builder data with the songs to be exported" in:
    val builderData = mock[ApplicationBuilderData]
    val command = new OpenExportSettingsDlgCommand(Locator, mock[PlaylistEditorConfig], ExportSongs)

    command prepareBuilderData builderData
    verify(builderData).addProperty("exportSongs", ExportSongs)

  it should "initialize the builder data with export settings" in:
    val builderData = mock[ApplicationBuilderData]
    val config = mock[PlaylistEditorConfig]
    val ExportPath = "export/path"
    val ClearMode = ExportSettings.ClearOverride
    when(config.exportClearMode).thenReturn(ClearMode)
    when(config.exportPath).thenReturn(ExportPath)
    val command = new OpenExportSettingsDlgCommand(Locator, config, ExportSongs)

    command prepareBuilderData builderData
    val captor = ArgumentCaptor.forClass(classOf[ExportSettings])
    verify(builderData).addProperty(argEq("exportSettings"), captor.capture())
    captor.getValue.clearMode should be(ClearMode)
    captor.getValue.targetDirectory should be(ExportPath)
