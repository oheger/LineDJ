/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.reorder

import java.util

import de.oliver_heger.linedj.platform.audio.model.SongData
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''ReorderResultHandler''.
  */
class ReorderResultHandlerSpec extends FlatSpec with Matchers with MockitoSugar {
  "A ReorderResultHandler" should "apply the results of a reorder operation" in {
    val tableHandler = mock[TableHandler]
    val song1, song2, song3 = mock[SongData]
    val ReorderRequest = ReorderActor.ReorderRequest(Nil, 2)
    val ReorderResult = ReorderActor.ReorderResponse(List(song1, song2, song3), ReorderRequest)
    val modelOld = new util.ArrayList[AnyRef](java.util.Arrays.asList("0", "1", "2", "3", "4",
      "5", "6"))
    val modelExp = new util.ArrayList(modelOld)
    modelExp.set(2, song1)
    modelExp.set(3, song2)
    modelExp.set(4, song3)
    when(tableHandler.getModel).thenReturn(modelOld)
    val handler = new ReorderResultHandler(tableHandler)

    handler receive ReorderResult
    modelOld should be(modelExp)
    verify(tableHandler).rowsUpdated(2, 4)
    verify(tableHandler).setSelectedIndices(Array(2, 3, 4))
  }
}
