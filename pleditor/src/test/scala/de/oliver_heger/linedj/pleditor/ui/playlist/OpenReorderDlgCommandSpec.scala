/*
 * Copyright 2015-2021 The Developers Team.
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

import de.oliver_heger.linedj.platform.audio.model.SongData
import net.sf.jguiraffe.gui.app.ApplicationBuilderData
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.locators.URLLocator
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object OpenReorderDlgCommandSpec {
  /** The test locator. */
  private val Locator = URLLocator getInstance "http://line-dj-test.org"
}

/**
  * Test class for ''OpenReorderDlgCommand''.
  */
class OpenReorderDlgCommandSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import OpenReorderDlgCommandSpec._

  "An OpenReorderDlgCommand" should "store the script locator" in {
    val command = new OpenReorderDlgCommand(Locator, mock[TableHandler])

    command.getLocator should be(Locator)
  }

  it should "store information about the reorder operation in the builder data" in {
    val builderData = mock[ApplicationBuilderData]
    val handler = mock[TableHandler]
    val model = java.util.Arrays.asList(mock[SongData], mock[SongData], mock[SongData],
      mock[SongData], mock[SongData], mock[SongData], mock[SongData], mock[SongData])
    doReturn(model).when(handler).getModel
    when(handler.getSelectedIndices).thenReturn(Array(1, 3, 4, 6))

    def song(idx: Int): SongData = model get idx
    val expectedSongs = List(song(1), song(2), song(3), song(4), song(5), song(6))
    val command = new OpenReorderDlgCommand(Locator, handler)

    command prepareBuilderData builderData
    verify(builderData).addProperty("reorderSongs", expectedSongs)
    verify(builderData).addProperty("reorderStartIndex", 1)
  }

  it should "have a dummy implementation for updatePlaylist()" in {
    val context = mock[PlaylistSelectionContext]
    val command = new OpenReorderDlgCommand(Locator, mock[TableHandler])

    command.updatePlaylist(context)
    verifyZeroInteractions(context)
  }

  it should "be enabled if a selection exists" in {
    val context = mock[PlaylistSelectionContext]
    when(context.hasSelection).thenReturn(true)
    val command = new OpenReorderDlgCommand(Locator, mock[TableHandler])

    command isEnabled context shouldBe true
  }

  it should "be disabled if no selection exists" in {
    val context = mock[PlaylistSelectionContext]
    when(context.hasSelection).thenReturn(false)
    val command = new OpenReorderDlgCommand(Locator, mock[TableHandler])

    command isEnabled context shouldBe false
  }
}
