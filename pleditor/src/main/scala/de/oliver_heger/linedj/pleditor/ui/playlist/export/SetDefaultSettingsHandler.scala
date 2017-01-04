/*
 * Copyright 2015-2017 The Developers Team.
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

import de.oliver_heger.linedj.pleditor.ui.config.PlaylistEditorConfig
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener}
import net.sf.jguiraffe.gui.builder.window.ctrl.FormController

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
 * @param config the configuration object
 * @param settings the object with settings data
 */
class SetDefaultSettingsHandler(controller: FormController, config: PlaylistEditorConfig, settings:
ExportSettings) extends FormActionListener {
  /**
   * @inheritdoc This method gets called when the monitored button is clicked.
   */
  override def actionPerformed(formActionEvent: FormActionEvent): Unit = {
    if (controller.validateAndDisplayMessages().isValid) {
      config.exportClearMode = settings.getClearMode
      config.exportPath = settings.getTargetDirectory
    }
  }
}
