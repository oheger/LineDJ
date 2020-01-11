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

package de.oliver_heger.linedj.pleditor.ui.playlist

import java.util

import net.sf.jguiraffe.gui.builder.action.{ActionStore, FormAction}
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''PlaylistActionEnabler''.
  */
class PlaylistActionEnablerSpec extends FlatSpec with MockitoSugar {
  "A PlaylistActionEnabler" should "update the managed actions' enabled state" in {
    val action1, action2 = mock[FormAction]
    val manipulator1, manipulator2 = mock[PlaylistManipulator]
    val store = mock[ActionStore]
    val tabHandler = mock[TableHandler]
    val context = PlaylistSelectionContext(tabHandler)
    val Name1 = "Action1"
    val Name2 = "Action2"
    when(store.getAction(Name1)).thenReturn(action1)
    when(store.getAction(Name2)).thenReturn(action2)
    when(manipulator1.isEnabled(context)).thenReturn(false)
    when(manipulator2.isEnabled(context)).thenReturn(true)
    val map = new util.HashMap[String, PlaylistManipulator]
    map.put(Name1, manipulator1)
    map.put(Name2, manipulator2)
    val enabler = new PlaylistActionEnabler(store, tabHandler, map)

    enabler elementChanged mock[FormChangeEvent]
    verify(action1).setEnabled(false)
    verify(action2).setEnabled(true)
  }
}
