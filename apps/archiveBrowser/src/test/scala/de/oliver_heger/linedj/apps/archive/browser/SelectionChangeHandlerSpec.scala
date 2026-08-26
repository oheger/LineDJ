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

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.ActionTestHelper
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TableHandler, TreeHandler, TreeNodePath}
import net.sf.jguiraffe.gui.builder.event.FormChangeEvent
import org.apache.commons.configuration.tree.ConfigurationNode
import org.mockito.Mockito.*
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util
import scala.compiletime.uninitialized

object SelectionChangeHandlerSpec:
  /**
    * A list with the names of all actions whose state is managed by the
    * selection change handler.
    */
  private val ManagedActions = List(
    SelectionChangeHandler.AddAlbumAction,
    SelectionChangeHandler.AddAlbumSongsAction,
    SelectionChangeHandler.AddArtistAction,
    SelectionChangeHandler.AddArtistAlbumAction,
    SelectionChangeHandler.AddArtistAlbumSongsAction,
    SelectionChangeHandler.AddMediumAction
  )
end SelectionChangeHandlerSpec

/**
  * Test class for [[SelectionChangeHandler]].
  */
class SelectionChangeHandlerSpec extends AnyFlatSpec, Matchers, MockitoSugar, ActionTestHelper:

  import SelectionChangeHandlerSpec.*

  /** Stores the action store associated with the test handler. */
  private var actionStore: ActionStore = uninitialized

  /**
    * Creates a test [[SelectionChangeHandler]] instance with the specified
    * controller and a test action store that provides the tracked actions.
    *
    * @param controller the controller
    * @return the test handler instance
    */
  private def createHandler(controller: Controller): SelectionChangeHandler =
    ManagedActions.foreach(action => createAction(action))
    actionStore = createActionStore()
    new SelectionChangeHandler(controller, actionStore)

  "A SelectionChangeHandler" should "notify the controller about a changed medium selection" in :
    val controller = mock[Controller]
    val listHandler = mock[ListComponentHandler]
    val selectedMedium = Checksums.MediumChecksum("test-medium-id")
    doReturn(selectedMedium).when(listHandler).getData
    val event = new FormChangeEvent("someSource", listHandler, "comboMedia")

    val handler = createHandler(controller)
    handler.elementChanged(event)

    verify(controller).mediumSelected(Some(selectedMedium))

  it should "notify the controller when the medium selection is reset" in :
    val controller = mock[Controller]
    val listHandler = mock[ListComponentHandler]
    doReturn(null).when(listHandler).getData
    val event = new FormChangeEvent("someSource", listHandler, "comboMedia")

    val handler = createHandler(controller)
    handler.elementChanged(event)

    verify(controller).mediumSelected(None)

  it should "reset all add actions when no medium is selected" in :
    val listHandler = mock[ListComponentHandler]
    doReturn(null).when(listHandler).getData
    val event = new FormChangeEvent("someSource", listHandler, "comboMedia")

    val handler = createHandler(mock)
    ManagedActions.foreach(action => actionStore.getAction(action).setEnabled(true))
    handler.elementChanged(event)

    forEvery(ManagedActions): action =>
      isActionEnabled(action) shouldBe false

  it should "enable the add medium action if a medium is enabled" in :
    val listHandler = mock[ListComponentHandler]
    doReturn(Checksums.MediumChecksum("some-medium-id")).when(listHandler).getData
    val event = new FormChangeEvent("someSource", listHandler, "comboMedia")

    val handler = createHandler(mock)
    actionStore.getAction(SelectionChangeHandler.AddMediumAction).setEnabled(false)
    handler.elementChanged(event)

    isActionEnabled(SelectionChangeHandler.AddMediumAction) shouldBe true

  /**
    * Initializes a mock tree handler to return a selected path that points to
    * the specified ID object.
    *
    * @param treeHandler the mock tree handler
    * @param targetID    the ID to return as selected path
    */
  private def initSelectedPath(treeHandler: TreeHandler, targetID: Any): Unit =
    val configNode = mock[ConfigurationNode]
    doReturn(targetID).when(configNode).getValue
    val nodePath = new TreeNodePath(configNode)
    doReturn(nodePath).when(treeHandler).getSelectedPath

  it should "notify the controller when an album is selected in the artists tree" in :
    val albumID = Controller.AlbumID("someAlbum")
    val controller = mock[Controller]
    val treeHandler = mock[TreeHandler]
    initSelectedPath(treeHandler, albumID)
    val event = new FormChangeEvent("someSource", treeHandler, "treeArtists")

    val handler = createHandler(controller)
    handler.elementChanged(event)

    verify(controller).artistAlbumSelected(Some(albumID))

  it should "notify the controller when the selection in the artist tree is reset" in :
    val controller = mock[Controller]
    val treeHandler = mock[TreeHandler]
    doReturn(null).when(treeHandler).getSelectedPath
    val event = new FormChangeEvent("someSource", treeHandler, "treeArtists")

    val handler = createHandler(controller)
    handler.elementChanged(event)

    verify(controller).artistAlbumSelected(None)

  it should "notify the controller when an artist is selected in the artists tree" in :
    val artistID = Controller.ArtistID("someArtist")
    val controller = mock[Controller]
    val treeHandler = mock[TreeHandler]
    initSelectedPath(treeHandler, artistID)
    val event = new FormChangeEvent("someSource", treeHandler, "treeArtists")

    val handler = createHandler(controller)
    handler.elementChanged(event)

    verify(controller).artistAlbumSelected(Some(artistID))

  it should "notify the controller if a node without a value is selected in the artists tree" in :
    val controller = mock[Controller]
    val treeHandler = mock[TreeHandler]
    initSelectedPath(treeHandler, null)
    val event = new FormChangeEvent("someSource", treeHandler, "treeArtists")

    val handler = createHandler(controller)
    handler.elementChanged(event)

    verify(controller).artistAlbumSelected(None)

  it should "notify the controller when an album is selected in the albums table" in :
    val controller = mock[Controller]
    val tabHandler = mock[TableHandler]
    val tableModel = new util.ArrayList[AnyRef]
    tableModel.add(AlbumData(ArchiveModel.AlbumInfo("alb1", "Album1", "art1"), "Artist1"))
    tableModel.add(AlbumData(ArchiveModel.AlbumInfo("alb2", "Album2", "art2"), "Artist2"))
    tableModel.add(AlbumData(ArchiveModel.AlbumInfo("alb3", "Album3", "art3"), "Artist3"))
    doReturn(tableModel).when(tabHandler).getModel
    doReturn(1).when(tabHandler).getSelectedIndex
    val event = new FormChangeEvent("someSource", tabHandler, "tableAlbums")

    val handler = createHandler(controller)
    handler.elementChanged(event)

    verify(controller).albumSelected(Some(Controller.AlbumID("alb2")))

  it should "notify the controller when the selection in the albums table is reset" in :
    val controller = mock[Controller]
    val tabHandler = mock[TableHandler]
    doReturn(-1).when(tabHandler).getSelectedIndex
    val event = new FormChangeEvent("someSource", tabHandler, "tableAlbums")

    val handler = createHandler(controller)
    handler.elementChanged(event)

    verify(controller).albumSelected(None)

  it should "enable the add album action when an album is selected in the albums table" in :
    val tabHandler = mock[TableHandler]
    val tableModel = new util.ArrayList[AnyRef]
    tableModel.add(AlbumData(ArchiveModel.AlbumInfo("alb1", "Album1", "art1"), "Artist1"))
    doReturn(tableModel).when(tabHandler).getModel
    doReturn(0).when(tabHandler).getSelectedIndex
    val event = new FormChangeEvent("someSource", tabHandler, "tableAlbums")

    val handler = createHandler(mock)
    actionStore.getAction(SelectionChangeHandler.AddAlbumAction).setEnabled(false)
    handler.elementChanged(event)

    isActionEnabled(SelectionChangeHandler.AddAlbumAction) shouldBe true

  it should "disable the add album action when the selection in the albums table is reset" in :
    val tabHandler = mock[TableHandler]
    doReturn(-1).when(tabHandler).getSelectedIndex
    val event = new FormChangeEvent("someSource", tabHandler, "tableAlbums")

    val handler = createHandler(mock)
    actionStore.getAction(SelectionChangeHandler.AddAlbumAction).setEnabled(true)
    handler.elementChanged(event)

    isActionEnabled(SelectionChangeHandler.AddAlbumAction) shouldBe false

  it should "enable the add album songs action when songs are selected in the album songs table" in :
    val tabHandler = mock[TableHandler]
    doReturn(Array(1, 2)).when(tabHandler).getSelectedIndices
    val event = new FormChangeEvent("someSource", tabHandler, "tableAlbumSongs")

    val handler = createHandler(mock)
    actionStore.getAction(SelectionChangeHandler.AddAlbumSongsAction).setEnabled(false)
    handler.elementChanged(event)

    isActionEnabled(SelectionChangeHandler.AddAlbumSongsAction) shouldBe true

  it should "disable the add album songs action when the selection in the album songs table is reset" in :
    val tabHandler = mock[TableHandler]
    doReturn(Array.empty[Int]).when(tabHandler).getSelectedIndices
    val event = new FormChangeEvent("someSource", tabHandler, "tableAlbumSongs")

    val handler = createHandler(mock)
    actionStore.getAction(SelectionChangeHandler.AddAlbumSongsAction).setEnabled(true)
    handler.elementChanged(event)

    isActionEnabled(SelectionChangeHandler.AddAlbumSongsAction) shouldBe false

  it should "enable the add artist album songs action when songs are selected in the artist album songs table" in :
    val tabHandler = mock[TableHandler]
    doReturn(Array(1, 2)).when(tabHandler).getSelectedIndices
    val event = new FormChangeEvent("someSource", tabHandler, "tableArtistSongs")

    val handler = createHandler(mock)
    actionStore.getAction(SelectionChangeHandler.AddArtistAlbumSongsAction).setEnabled(false)
    handler.elementChanged(event)

    isActionEnabled(SelectionChangeHandler.AddArtistAlbumSongsAction) shouldBe true

  it should "disable the add artist album songs action when the selection in the artist album songs table is reset" in :
    val tabHandler = mock[TableHandler]
    doReturn(Array.empty[Int]).when(tabHandler).getSelectedIndices
    val event = new FormChangeEvent("someSource", tabHandler, "tableArtistSongs")

    val handler = createHandler(mock)
    actionStore.getAction(SelectionChangeHandler.AddArtistAlbumSongsAction).setEnabled(true)
    handler.elementChanged(event)

    isActionEnabled(SelectionChangeHandler.AddArtistAlbumSongsAction) shouldBe false
