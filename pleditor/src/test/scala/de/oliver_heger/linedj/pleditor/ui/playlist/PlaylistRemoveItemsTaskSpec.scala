/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.playlist

import java.util

import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{Matchers, FlatSpec}

/**
  * Test class for ''PlaylistRemoveItemsTask''.
  */
class PlaylistRemoveItemsTaskSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a task for the tests.
    * @return the test task
    */
  private def createTask(): PlaylistRemoveItemsTask = {
    new PlaylistRemoveItemsTask(mock[PlaylistController])
  }

  "A PlaylistRemoveItemsTask" should "return the enabled flag if there is no selection" in {
    val context = mock[PlaylistSelectionContext]
    when(context.hasSelection).thenReturn(false)
    val task = createTask()

    task isEnabled context shouldBe false
  }

  it should "return the enabled flag if there is a selection" in {
    val context = mock[PlaylistSelectionContext]
    when(context.hasSelection).thenReturn(true)
    val task = createTask()

    task isEnabled context shouldBe true
  }

  it should "remove selected items from the playlist" in {
    val context = mock[PlaylistSelectionContext]
    val tableHandler = mock[TableHandler]
    val model = new util.ArrayList(util.Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8))
    val selectedIndices = Array(0, 5, 2, 6)
    when(context.tableHandler).thenReturn(tableHandler)
    doReturn(model).when(tableHandler).getModel
    when(context.selectedIndices).thenReturn(selectedIndices)
    val task = createTask()

    task updatePlaylist context
    model should be(util.Arrays.asList(1, 3, 4, 7, 8))
    verify(tableHandler).tableDataChanged()
  }
}
