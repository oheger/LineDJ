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

package de.oliver_heger.linedj.browser.media

import de.oliver_heger.linedj.media.MediumID
import net.sf.jguiraffe.gui.builder.components.model.{TableHandler, ListComponentHandler,
TreeHandler, TreeNodePath}
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import net.sf.jguiraffe.gui.forms.ComponentHandler
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''SelectionChangeHandler''.
 */
class SelectionChangeHandlerSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
   * Creates a change event for the specified component handler.
   * @param handler the component handler
   * @return the change event
   */
  private def createChangeEvent(handler: ComponentHandler[_]): FormChangeEvent =
    new FormChangeEvent(this, handler, "some name")

  "A SelectionChangeHandler" should "react on a changed medium selection" in {
    val Medium = MediumID("Some Test Medium", None)
    val controller = mock[MediaController]
    val listHandler = mock[ListComponentHandler]
    doReturn(Medium).when(listHandler).getData
    val handler = new SelectionChangeHandler(controller)

    handler elementChanged createChangeEvent(listHandler)
    verify(controller).selectMedium(Medium)
  }

  it should "react on a changed album selection" in {
    val SelectedPaths = Array(mock[TreeNodePath], mock[TreeNodePath])
    val controller = mock[MediaController]
    val treeHandler = mock[TreeHandler]
    when(treeHandler.getSelectedPaths).thenReturn(SelectedPaths)
    val handler = new SelectionChangeHandler(controller)

    handler elementChanged createChangeEvent(treeHandler)
    verify(controller).selectAlbums(SelectedPaths)
  }

  it should "react on a changed songs selection" in {
    val tableHandler = mock[TableHandler]
    val controller = mock[MediaController]
    val handler = new SelectionChangeHandler(controller)

    handler elementChanged createChangeEvent(tableHandler)
    verify(controller).songSelectionChanged()
  }
}
