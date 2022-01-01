/*
 * Copyright 2015-2022 The Developers Team.
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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''PlaylistMoveUpTask''.
  */
class PlaylistMoveUpTaskSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a test task with a mock controller.
    * @return the test task
    */
  private def createTask(): PlaylistMoveUpTask = {
    new PlaylistMoveUpTask(mock[PlaylistController])
  }

  "A PlaylistMoveUpTask" should "not be enabled if there is no selection" in {
    val context = mock[PlaylistSelectionContext]
    when(context.hasSelection).thenReturn(false)
    val task = createTask()

    task isEnabled context shouldBe false
  }

  it should "be enabled if the first element is not part of the selection" in {
    val context = mock[PlaylistSelectionContext]
    when(context.hasSelection).thenReturn(true)
    when(context.isFirstElementSelected).thenReturn(false)
    val task = createTask()

    task isEnabled context shouldBe true
  }

  it should "not be enabled if the first element is selected" in {
    val context = mock[PlaylistSelectionContext]
    when(context.hasSelection).thenReturn(true)
    when(context.isFirstElementSelected).thenReturn(true)
    val task = createTask()

    task isEnabled context shouldBe false
  }

  it should "move selected items one position up" in {
    val context = mock[PlaylistSelectionContext]
    val tabHandler = mock[TableHandler]
    val model = new util.ArrayList(java.util.Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
    when(context.selectedIndices).thenReturn(Array(5, 2, 8, 4))
    when(context.minimumSelectionIndex).thenReturn(2)
    when(context.maximumSelectionIndex).thenReturn(8)
    when(context.tableHandler).thenReturn(tabHandler)
    doReturn(model).when(tabHandler).getModel
    val task = createTask()

    task updatePlaylist context
    model should be(java.util.Arrays.asList(0, 2, 1, 4, 5, 3, 6, 8, 7, 9, 10))
    verify(tabHandler).rowsUpdated(1, 8)
    verify(tabHandler).setSelectedIndices(Array(4, 1, 7, 3))
  }
}
