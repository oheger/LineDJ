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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.platform.audio2.impl.PlaylistServiceImpl
import de.oliver_heger.linedj.platform.audio2.playlist.Playlist
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.file.{Files, Path}

object PlaylistSaveServiceSpec:
  /**
    * Reads a file that is expected to contain JSON content. This content is
    * then converted to desired result type.
    *
    * @param path   the path to the input file
    * @param reader the JSON reader for the conversion
    * @tparam A the result type
    * @return the result object
    */
  private def readJsonFile[A](path: Path)(using reader: JsonReader[A]): A =
    val content = Files.readString(path)
    content.parseJson.convertTo[A]

  /**
    * Extracts the song IDs from the given list of playlist items.
    *
    * @param playlistItems the playlist items
    * @return a list with the extracted IDs
    */
  private def toSongs(playlistItems: List[PlaylistModel.PlaylistItem]): List[String] =
    playlistItems.map(_.fileID)
end PlaylistSaveServiceSpec

/**
  * Test class for [[PlaylistSaveService]].
  */
class PlaylistSaveServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, Matchers, FileTestHelper:
  def this() = this(ActorSystem("PlaylistSaveServiceSpec"))

  override protected def afterAll(): Unit =
    tearDownTestFile()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import PlaylistSaveServiceSpec.*
  import PlaylistTestHelper.*

  /**
    * Returns a new test instance for the save service. The instance uses a 
    * functional [[PlaylistService]].
    *
    * @return
    */
  private def createService(): PlaylistSaveService = PlaylistSaveService.create(PlaylistServiceImpl)

  "PlaylistSaveService" should "save a position file" in :
    val position = PlaylistModel.CurrentPlaylistPosition(17, 32000, 60000)

    val service = createService()
    val posSource = service.createPositionSource(position)
    val target = createPathInDirectory("test.pos")
    service.store(PlaylistSaveService.WriteOperation(target, posSource)) map : _ =>
      val position2 = readJsonFile[PlaylistModel.CurrentPlaylistPosition](target)
      position2 should be(position)

  it should "save a playlist file" in :
    val items = playlistItems(16)
    val playlist = Playlist(pendingSongs = toSongs(items), playedSongs = Nil)

    val service = createService()
    val itemsSource = service.createPlaylistSource(playlist)
    val target = createPathInDirectory("test.lst")
    service.store(PlaylistSaveService.WriteOperation(target, itemsSource)) map : _ =>
      val items2 = readJsonFile[List[PlaylistModel.PlaylistItem]](target)
      items2 should be(items)

  it should "save a playlist file with played songs" in :
    val items = playlistItems(16)
    val (playedSongs, pendingSongs) = toSongs(items).splitAt(7)
    val playlist = Playlist(pendingSongs = pendingSongs, playedSongs = playedSongs.reverse)

    val service = createService()
    val itemsSource = service.createPlaylistSource(playlist)
    val target = createPathInDirectory("test-played.lst")
    service.store(PlaylistSaveService.WriteOperation(target, itemsSource)) map : _ =>
      val items2 = readJsonFile[List[PlaylistModel.PlaylistItem]](target)
      items2 should be(items)
