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

import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''AbstractPlaylistManipulationTask''.
  */
class AbstractPlaylistManipulationTaskSpec extends AnyFlatSpec with MockitoSugar {
  "An AbstractPlaylistManipulationTask" should "trigger a playlist update" in {
    val controller = mock[PlaylistController]
    val task = new AbstractPlaylistManipulationTask(controller) with PlaylistManipulator {
      override def updatePlaylist(context: PlaylistSelectionContext): Unit = {
        fail("Unexpected invocation!")
      }

      override def isEnabled(context: PlaylistSelectionContext): Boolean = true
    }

    task.run()
    verify(controller).updatePlaylist(task)
  }
}
