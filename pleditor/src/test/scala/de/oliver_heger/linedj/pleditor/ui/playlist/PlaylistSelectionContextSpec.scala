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

package de.oliver_heger.linedj.pleditor.ui.playlist

import java.util.Collections

import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''PlaylistSelectionContext''.
  */
class PlaylistSelectionContextSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  /**
    * Creates a mock for the table handler. Optionally, a size of the table
    * model can be specified. In this case, a corresponding model is created.
    * @param indices the selected indices
    * @param optModelSize an optional size of the table model
    * @return the mock table handler
    */
  private def createHandler(indices: List[Int], optModelSize: Option[Int] = None): TableHandler =
    val handler = mock[TableHandler]
    when(handler.getSelectedIndices).thenReturn(indices.toArray)
    optModelSize foreach { size =>
      val model = Collections.nCopies(size, "Test")
      doReturn(model).when(handler).getModel
    }
    handler

  "A PlaylistSelectionContext" should "return the minimum selection index" in:
    val context = PlaylistSelectionContext(createHandler(List(8, 4, 9, 12, 28)))

    context.minimumSelectionIndex should be(4)

  it should "return the maximum selection index" in:
    val context = PlaylistSelectionContext(createHandler(List(8, 4, 9, 12, 28, 1)))

    context.maximumSelectionIndex should be(28)

  it should "report whether the first element is selected if this is the case" in:
    val context = PlaylistSelectionContext(createHandler(List(8, 4, 9, 12, 0, 1)))

    context.isFirstElementSelected shouldBe true

  it should "report whether the first element is selected if this is not the case" in:
    val context = PlaylistSelectionContext(createHandler(List(8, 4, 9, 12, 7, 1)))

    context.isFirstElementSelected shouldBe false

  it should "report whether the last element is selected if this is the case" in:
    val context = PlaylistSelectionContext(createHandler(List(1, 2, 9, 8, 5), Some(10)))

    context.isLastElementSelected shouldBe true

  it should "report whether the last element is selected if this is not the case" in:
    val context = PlaylistSelectionContext(createHandler(List(1, 2, 9, 8, 5), Some(11)))

    context.isLastElementSelected shouldBe false

  it should "return the correct selected indices" in:
    val indices = List(2, 4, 6, 8)
    val context = PlaylistSelectionContext(createHandler(indices))

    context.selectedIndices should be(indices.toArray)

  it should "return the hasSelection flag if there is no selection" in:
    val context = PlaylistSelectionContext(createHandler(Nil))

    context.hasSelection shouldBe false

  it should "return the hasSelection flag if there is a selection" in:
    val context = PlaylistSelectionContext(createHandler(List(0)))

    context.hasSelection shouldBe true
