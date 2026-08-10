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

package de.oliver_heger.linedj.apps.archive.browser

import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.resources.Message
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[StatusLineController]].
  */
class StatusLineControllerSpec extends AnyFlatSpec, Matchers, MockitoSugar:
  "A StatusLineController" should "display the medium title when it is set" in :
    val MediumTitle = "TestMedium"
    val statusText = mock[StaticTextHandler]
    val controller = new StatusLineController(mock, statusText, mock)

    controller.setMediumTitle(Some(MediumTitle))

    verify(statusText).setText(MediumTitle)

  it should "display an empty text if no medium is selected" in :
    val statusText = mock[StaticTextHandler]
    val controller = new StatusLineController(mock, statusText, mock)

    controller.setMediumTitle(None)

    verify(statusText).setText("")

  it should "display a message in the status line" in :
    val message = new Message("someResourceKey")
    val ResourceText = "This is the text of the message."
    val appCtx = mock[ApplicationContext]
    doReturn(ResourceText).when(appCtx).getResourceText(message)
    val statusText = mock[StaticTextHandler]
    val controller = new StatusLineController(appCtx, statusText, mock)

    controller.setStatusMessage(message)

    verify(statusText).setText(ResourceText)

  it should "show the progress indicator when a load operation starts" in :
    val statusText = mock[StaticTextHandler]
    val indicator = mock[WidgetHandler]
    val controller = new StatusLineController(mock, statusText, indicator)

    controller.loadOperationStarts()

    verify(indicator).setVisible(true)
    verifyNoInteractions(statusText)

  it should "hide the progress indicator and restore the status line text when a load operation ends" in :
    val MediumTitle = "The current medium"
    val statusText = mock[StaticTextHandler]
    val indicator = mock[WidgetHandler]
    val controller = new StatusLineController(mock, statusText, indicator)
    controller.setMediumTitle(Some(MediumTitle))

    controller.loadOperationStarts()
    controller.loadOperationEnds()

    val io = Mockito.inOrder(indicator)
    io.verify(indicator).setVisible(true)
    io.verify(indicator).setVisible(false)
    verify(statusText, times(2)).setText(MediumTitle)

  it should "support multiple interleaving load operations" in :
    val MediumTitle = "Medium with much content"
    val statusText = mock[StaticTextHandler]
    val indicator = mock[WidgetHandler]
    val appCtx = mock[ApplicationContext]
    doReturn("Temporary text").when(appCtx).getResourceText(any(classOf[Message]))
    val controller = new StatusLineController(mock, statusText, indicator)
    controller.setMediumTitle(Some(MediumTitle))

    controller.loadOperationStarts()
    controller.setStatusMessage(new Message("some-resource"))
    controller.loadOperationStarts()
    controller.loadOperationEnds()

    verify(indicator, times(1)).setVisible(true)
    verify(indicator, never()).setVisible(false)
    verify(statusText, times(1)).setText(MediumTitle)

    controller.loadOperationEnds()
    verify(indicator).setVisible(false)
    verify(statusText, times(2)).setText(MediumTitle)

  it should "not allow a negative number of load operations in progress" in :
    val indicator = mock[WidgetHandler]
    val controller = new StatusLineController(mock, mock, indicator)

    controller.loadOperationEnds()
    controller.loadOperationStarts()

    verify(indicator).setVisible(true)
