/*
 * Copyright 2015-2016 The Developers Team.
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

import java.util

import de.oliver_heger.linedj.pleditor.ui.config.PlaylistEditorConfig
import net.sf.jguiraffe.gui.builder.window.ctrl.FormController
import net.sf.jguiraffe.gui.forms.{DefaultFormValidatorResults, FormValidatorResults}
import net.sf.jguiraffe.transform.ValidationResult
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FlatSpec}

/**
 * Test class for ''SetDefaultSettingsHandler''.
 */
class SetDefaultSettingsHandlerSpec extends FlatSpec with Matchers with MockitoSugar {
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
}
