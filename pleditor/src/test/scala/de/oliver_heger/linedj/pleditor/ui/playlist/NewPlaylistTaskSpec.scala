/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.platform.audio.SetPlaylist
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''NewPlaylistTask''.
  */
class NewPlaylistTaskSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "A NewPlaylistTask" should "always be enabled" in {
    val context = mock[PlaylistSelectionContext]
    val task = new NewPlaylistTask(mock[PlaylistController], mock[MessageBus])

    task isEnabled context shouldBe true
    verifyNoInteractions(context)
  }

  it should "clear the table model and reset the playlist" in {
    val context = mock[PlaylistSelectionContext]
    val tableHandler = mock[TableHandler]
    val model = new util.ArrayList(util.Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8))
    when(context.tableHandler).thenReturn(tableHandler)
    doReturn(model).when(tableHandler).getModel
    val bus = mock[MessageBus]

    val task = new NewPlaylistTask(mock[PlaylistController], bus)
    task.updatePlaylist(context)

    model.size() should be(0)
    verify(tableHandler).tableDataChanged()
    verify(bus).publish(SetPlaylist(Playlist(Nil, Nil), closePlaylist = false))
  }
}
