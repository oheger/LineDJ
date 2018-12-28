/*
 * Copyright 2015-2018 The Developers Team.
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

import java.io.File
import java.util

import de.oliver_heger.linedj.pleditor.ui.config.PlaylistEditorConfig
import net.sf.jguiraffe.gui.builder.window.ctrl.FormController
import net.sf.jguiraffe.gui.dlg.filechooser.{DirectoryChooserOptions, FileChooserDialogService}
import net.sf.jguiraffe.gui.forms.{ComponentHandler, DefaultFormValidatorResults, FormValidatorResults}
import net.sf.jguiraffe.transform.ValidationResult
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for the button handlers of the export settings dialog.
  */
class ButtonHandlersSpec extends FlatSpec with Matchers with MockitoSugar {
  "A SetDefaultSettingsHandler" should "store default settings" in {
    val ClearMode = ExportSettings.ClearOverride
    val ExportPath = "some/export/path"
    val controller = mock[FormController]
    val config = mock[PlaylistEditorConfig]
    val settings = new ExportSettings
    when(controller.validateAndDisplayMessages()).thenAnswer(new Answer[FormValidatorResults] {
      override def answer(invocationOnMock: InvocationOnMock): FormValidatorResults = {
        settings setClearMode ClearMode
        settings setTargetDirectory ExportPath
        val fields = new util.HashMap[String, ValidationResult]
        new DefaultFormValidatorResults(fields)
      }
    })
    val handler = new SetDefaultSettingsHandler(controller, config, settings)

    handler.actionPerformed(null)
    verify(config).exportPath = ExportPath
    verify(config).exportClearMode = ClearMode
  }

  it should "not update the configuration if validation fails" in {
    val controller = mock[FormController]
    val config = mock[PlaylistEditorConfig]
    val settings = new ExportSettings
    val results = mock[FormValidatorResults]
    when(results.isValid).thenReturn(false)
    when(controller.validateAndDisplayMessages()).thenReturn(results)
    val handler = new SetDefaultSettingsHandler(controller, config, settings)

    handler.actionPerformed(null)
    verifyZeroInteractions(config)
  }

  /**
    * Verifies that the given service mock has been invoked and returns the
    * ''DirectoryChooserOptions'' object that has been passed to it.
    *
    * @param service the mock file chooser service
    * @return the options passed to the service
    */
  private def fetchDirectoryChooserOptions(service: FileChooserDialogService):
  DirectoryChooserOptions = {
    val captor = ArgumentCaptor.forClass(classOf[DirectoryChooserOptions])
    verify(service).showChooseDirectoryDialog(captor.capture())
    captor.getValue
  }

  "A ChooseExportDirectoryHandler" should "configure the initial directory" in {
    val CurrentDir = new File(".").getAbsolutePath
    val service = mock[FileChooserDialogService]
    val controller = mock[FormController]
    val textDir = mock[ComponentHandler[String]]
    when(textDir.getData).thenReturn(CurrentDir)
    val handler = new ChooseExportDirectoryHandler(controller, service, textDir)

    handler.actionPerformed(null)
    fetchDirectoryChooserOptions(service).getInitialDirectory should be(new File(CurrentDir))
  }

  /**
    * Checks the configuration of the directory chooser dialog if the text
    * field for the export directory does not contain a value.
    *
    * @param currentDir the current directory
    */
  private def checkNoInitialDirectoryIsSetIfUndefined(currentDir: String): Unit = {
    val service = mock[FileChooserDialogService]
    val controller = mock[FormController]
    val textDir = mock[ComponentHandler[String]]
    when(textDir.getData).thenReturn(currentDir)
    val handler = new ChooseExportDirectoryHandler(controller, service, textDir)

    handler.actionPerformed(null)
    fetchDirectoryChooserOptions(service).getInitialDirectory should be(null)
  }

  it should "not set an initial directory is the current directory is null" in {
    checkNoInitialDirectoryIsSetIfUndefined(null)
  }

  it should "not set an initial directory if the current directory is empty" in {
    checkNoInitialDirectoryIsSetIfUndefined("")
  }

  it should "not set an initial directory if the current directory does not exist" in {
    checkNoInitialDirectoryIsSetIfUndefined("a directory that does not exist!")
  }

  it should "process the dialog result" in {
    val ResultFile = new File("/data/music/target")
    val service = mock[FileChooserDialogService]
    val controller = mock[FormController]
    val textDir = mock[ComponentHandler[String]]
    val handler = new ChooseExportDirectoryHandler(controller, service, textDir)
    handler.actionPerformed(null)
    val options = fetchDirectoryChooserOptions(service)

    options.getResultCallback.onDialogResult(ResultFile, null)
    verify(textDir).setData(ResultFile.getAbsolutePath)
    verify(controller).validateAndDisplayMessages()
  }
}
