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

import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import net.sf.jguiraffe.gui.builder.components.model.ListComponentHandler
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[SelectionChangeHandler]].
  */
class SelectionChangeHandlerSpec extends AnyFlatSpec, Matchers, MockitoSugar:
  "A SelectionChangeHandler" should "notify the controller about a changed medium selection" in :
    val controller = mock[Controller]
    val listHandler = mock[ListComponentHandler]
    val selectedMedium = Checksums.MediumChecksum("test-medium-id")
    doReturn(selectedMedium).when(listHandler).getData
    val event = new FormChangeEvent("someSource", listHandler, "someName")

    val handler = new SelectionChangeHandler(controller)
    handler.elementChanged(event)

    verify(controller).mediumSelected(Some(selectedMedium))

  it should "notify the controller when the medium selection is reset" in :
    val controller = mock[Controller]
    val listHandler = mock[ListComponentHandler]
    doReturn(null).when(listHandler).getData
    val event = new FormChangeEvent("someSource", listHandler, "someName")

    val handler = new SelectionChangeHandler(controller)
    handler.elementChanged(event)

    verify(controller).mediumSelected(None)
