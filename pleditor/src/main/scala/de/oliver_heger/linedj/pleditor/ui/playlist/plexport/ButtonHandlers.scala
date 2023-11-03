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

import java.io.File

import de.oliver_heger.linedj.pleditor.ui.config.PlaylistEditorConfig
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener}
import net.sf.jguiraffe.gui.builder.window.ctrl.FormController
import net.sf.jguiraffe.gui.dlg.DialogResultCallback
import net.sf.jguiraffe.gui.dlg.filechooser.{DirectoryChooserOptions, FileChooserDialogService}
import net.sf.jguiraffe.gui.forms.ComponentHandler

/**
  * An event listener class which reacts on the button for setting default
  * export settings.
  *
  * The dialog for export settings has a button for defining defaults. This
  * button is handled by this class. An instance has a reference to the form
  * controller for the export settings dialog and the central configuration
  * object. When the monitored button is clicked, the form controller is
  * triggered to perform a validation, and - if successful - the resulting
  * settings are written into the configuration.
  *
  * @param controller the controller for the export settings dialog
  * @param config     the configuration object
  * @param settings   the object with settings data
  */
class SetDefaultSettingsHandler(controller: FormController, config: PlaylistEditorConfig, settings:
ExportSettings) extends FormActionListener:
  /**
    * @inheritdoc This method gets called when the monitored button is clicked.
    */
  override def actionPerformed(formActionEvent: FormActionEvent): Unit =
    if controller.validateAndDisplayMessages().isValid then
      config.exportClearMode = settings.clearMode
      config.exportPath = settings.targetDirectory

/**
  * An event listener class that reacts on the button to select the export
  * directory.
  *
  * When the button is clicked a directory chooser dialog is opened. The
  * directory selected by the user (if any) is then written into the text field
  * for the export directory.
  *
  * @param controller     the controller for the export settings dialog
  * @param chooserService the service to show file chooser dialogs
  * @param textDir        the text field for the export directory
  */
class ChooseExportDirectoryHandler(controller: FormController,
                                   chooserService: FileChooserDialogService,
                                   textDir: ComponentHandler[String]) extends FormActionListener:
  override def actionPerformed(e: FormActionEvent): Unit =
    val callback = new DialogResultCallback[File, Void]:
      override def onDialogResult(result: File, d: Void): Unit =
        directorySelected(controller, textDir, result)
    val currentDir = textDir.getData
    val options = new DirectoryChooserOptions(callback)
      .setTitleResource("exp_directorydlg_title")
    if currentDir != null && currentDir.length > 0 then
      val initDir = new File(currentDir)
      if initDir.isDirectory then
        options.setInitialDirectory(initDir)
    chooserService.showChooseDirectoryDialog(options)

  /**
    * Processes the result of the directory selection dialog. The path of the
    * directory is written into the text field and a new input validation is
    * triggered.
    *
    * @param controller the form controller
    * @param textDir    the text field for the export directory
    * @param directory  the directory selected by the user
    */
  private def directorySelected(controller: FormController, textDir: ComponentHandler[String],
                                directory: File): Unit =
    textDir setData directory.getAbsolutePath
    controller.validateAndDisplayMessages()
