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
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TableHandler, TreeHandler, TreeNodePath}
import org.apache.commons.configuration.tree.DefaultConfigurationNode
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

object AppendPlaylistActionTasksSpec:
  /** The ID of a test medium. */
  private val TestMediumID = Checksums.MediumChecksum("test-medium-id")

  /**
    * Generates the ID of a test song based on a provided index.
    *
    * @param index the index of the test song
    * @return the ID of this test song
    */
  private def testSongID(index: Int): String = s"song$index"

  /**
    * Generates a test song based on a provided given index.
    *
    * @param index the index of the test song
    * @return the test song for this index
    */
  private def testSong(index: Int): SongData =
    SongData(MediaMetadata(checksum = testSongID(index), size = index * 100))

  /**
    * Returns a collection with test songs for the indices from 1 to count.
    *
    * @param count the number of test songs
    * @return the collection with the test songs
    */
  private def generateTestSongs(count: Int): Iterable[SongData] =
    (1 to count).map(testSong)
end AppendPlaylistActionTasksSpec

/**
  * Test class for the action tasks to appends songs to the playlist.
  */
class AppendPlaylistActionTasksSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import AppendPlaylistActionTasksSpec.*

  "AppendTableSongsTask" should "publish a message with all songs in the table" in :
    val songs = generateTestSongs(3)
    val tableModel = new util.ArrayList[AnyRef]
    songs.foreach(tableModel.add)
    val tableHandler = mock[TableHandler]
    doReturn(tableModel).when(tableHandler).getModel
    val bus = new MessageBusTestImpl

    val task = new AppendTableSongsTask(bus, tableHandler, selectedOnly = false)
    task.run()

    val expectedCommand = AudioPlayerCommands.AppendPlaylist(List(testSongID(1), testSongID(2), testSongID(3)))
    bus.expectMessageType[AudioPlayerCommands] should be(expectedCommand)

  it should "publish a message with only the selected songs in the table" in :
    val songs = generateTestSongs(16)
    val selectedIndices = Array(0, 2, 3, 5, 7, 8, 14)
    val tableModel = new util.ArrayList[AnyRef]
    songs.foreach(tableModel.add)
    val tableHandler = mock[TableHandler]
    doReturn(tableModel).when(tableHandler).getModel
    doReturn(selectedIndices).when(tableHandler).getSelectedIndices
    val bus = new MessageBusTestImpl

    val task = new AppendTableSongsTask(bus, tableHandler, selectedOnly = true)
    task.run()

    val expectedCommand = AudioPlayerCommands.AppendPlaylist(
      List(testSongID(1), testSongID(3), testSongID(4), testSongID(6), testSongID(8), testSongID(9), testSongID(15))
    )
    bus.expectMessageType[AudioPlayerCommands] should be(expectedCommand)

  /**
    * Creates an initialized mock for a list handler that is prepared to return
    * the test medium ID as selected element.
    *
    * @return the mock for the list handler
    */
  private def createMediumListHandler(): ListComponentHandler =
    val handler = mock[ListComponentHandler]
    doReturn(TestMediumID).when(handler).getData
    handler

  /**
    * Creates an initialized mock for a tree handler that is prepared to return
    * a specific selected path.
    *
    * @param selectedPath the selected path to return
    * @return the mock for the tree handler
    */
  private def createTreeHandler(selectedPath: TreeNodePath): TreeHandler =
    val handler = mock[TreeHandler]
    doReturn(selectedPath).when(handler).getSelectedPath
    handler

  "AppendArtistSongsTask" should "append the songs of the currently selected artist" in :
    val artistID = Controller.ArtistID("selected-artist")
    val artistUrl = s"/api/archive/media/${TestMediumID.checksum}/artists/${artistID.id}/songs"
    val songs = generateTestSongs(8)
    val songsResult = ArchiveModel.ItemsResult(songs.map(_.metadata).toList)
    val root = new DefaultConfigurationNode("root")
    val node = new DefaultConfigurationNode("Some Artist", artistID)
    node.setParentNode(root)
    val path = new TreeNodePath(node)
    val treeHandler = createTreeHandler(path)
    val archiveService = mock[ArchiveService]
    doReturn(Future.successful(songsResult)).when(archiveService).queryData(argEq(artistUrl))(using any())
    val bus = new MessageBusTestImpl

    val task = AppendArtistSongsTask(
      archiveService,
      ExecutionContext.global,
      bus,
      treeHandler,
      createMediumListHandler()
    )
    task.run()

    val expectedCommand = AudioPlayerCommands.AppendPlaylist(songs.map(_.metadata.checksum))
    bus.expectMessageType[AudioPlayerCommands] should be(expectedCommand)

  it should "handle a failed request to the archive service" in :
    val artistID = Controller.ArtistWithoutAlbumsID("selected-artist")
    val songs = generateTestSongs(4)
    val songsResult = ArchiveModel.ItemsResult(songs.map(_.metadata).toList)
    val node = new DefaultConfigurationNode("Some Artist", artistID)
    val path = new TreeNodePath(node)
    val treeHandler = createTreeHandler(path)
    val archiveService = mock[ArchiveService]
    doReturn(Future.failed(new IllegalStateException("Test exception")), Future.successful(songsResult))
      .when(archiveService).queryData(any())(using any())
    val bus = new MessageBusTestImpl

    val task = AppendArtistSongsTask(
      archiveService,
      ExecutionContext.global,
      bus,
      treeHandler,
      createMediumListHandler()
    )
    task.run()

    bus.expectNoMessage(100.millis)
    task.run()
    bus.expectMessageType[AudioPlayerCommands]

  it should "append the songs of the artist who owns the currently selected album" in :
    val artistID = Controller.ArtistID("selected-artist")
    val artistUrl = s"/api/archive/media/${TestMediumID.checksum}/artists/${artistID.id}/songs"
    val songs = generateTestSongs(10)
    val songsResult = ArchiveModel.ItemsResult(songs.map(_.metadata).toList)
    val rootNode = new DefaultConfigurationNode("root")
    val artistNode = new DefaultConfigurationNode("Dire Straits", artistID)
    artistNode.setParentNode(rootNode)
    val albumNode = new DefaultConfigurationNode("Brothers in Arms", Controller.AlbumID("sel-album"))
    albumNode.setParentNode(artistNode)
    val path = new TreeNodePath(albumNode)
    val treeHandler = createTreeHandler(path)
    val archiveService = mock[ArchiveService]
    doReturn(Future.successful(songsResult)).when(archiveService).queryData(argEq(artistUrl))(using any())
    val bus = new MessageBusTestImpl

    val task = AppendArtistSongsTask(
      archiveService,
      ExecutionContext.global,
      bus,
      treeHandler,
      createMediumListHandler()
    )
    task.run()

    val expectedCommand = AudioPlayerCommands.AppendPlaylist(songs.map(_.metadata.checksum))
    bus.expectMessageType[AudioPlayerCommands] should be(expectedCommand)

  "AppendMediumSongsTask" should "append all songs of the currently selected medium" in :
    val songIDs = (1 to 12).map(i => s"medium-song-$i").toList
    val songsResult = ArchiveModel.ItemsResult(songIDs)
    val mediumUrl = s"/api/archive/media/${TestMediumID.checksum}/songids"
    val archiveService = mock[ArchiveService]
    doReturn(Future.successful(songsResult)).when(archiveService).queryData(argEq(mediumUrl))(using any())
    val bus = new MessageBusTestImpl

    val task = AppendMediumSongsTask(
      archiveService,
      ExecutionContext.global,
      bus,
      createMediumListHandler()
    )
    task.run()

    val expectedCommand = AudioPlayerCommands.AppendPlaylist(songIDs)
    bus.expectMessageType[AudioPlayerCommands] should be(expectedCommand)

  it should "handle a failed request to the archive service" in :
    val songsResult = ArchiveModel.ItemsResult(List("some-song-id"))
    val archiveService = mock[ArchiveService]
    doReturn(Future.failed(new IllegalStateException("Test exception")), Future.successful(songsResult))
      .when(archiveService).queryData(any())(using any())
    val bus = new MessageBusTestImpl

    val task = AppendMediumSongsTask(
      archiveService,
      ExecutionContext.global,
      bus,
      createMediumListHandler()
    )
    task.run()

    bus.expectNoMessage(100.millis)
    task.run()
    bus.expectMessageType[AudioPlayerCommands]
