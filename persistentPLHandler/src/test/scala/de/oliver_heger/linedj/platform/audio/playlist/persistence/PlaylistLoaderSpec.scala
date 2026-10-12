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
import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.*
import DefaultJsonProtocol.*
import org.apache.pekko.stream.scaladsl.Framing

import java.nio.file.Path
import scala.concurrent.Future

object PlaylistLoaderSpec:
  /** The maximum file size used by the test cases. */
  private val DefaultMaxFileSize = 64000

  /**
    * Generates the ID of a test media file based on the given index.
    *
    * @param index the index of the test file
    * @return the ID of this file
    */
  private def fileID(index: Int): String = s"test-file-id-$index"

  /**
    * Generates a list with the IDs of test media files in the given range.
    *
    * @param fromIndex the start index
    * @param toIndex   the end index (including)
    * @return the list with file IDs
    */
  private def fileIDs(fromIndex: Int, toIndex: Int): List[String] =
    (fromIndex to toIndex).map(fileID).toList

  /**
    * Generates a test playlist item based on the given index.
    *
    * @param index the index
    * @return the test item with this index
    */
  private def playlistItem(index: Int): PlaylistModel.PlaylistItem =
    PlaylistModel.PlaylistItem(fileID(index))

  /**
    * Generates a list with test playlist items with a specific size.
    *
    * @param count the number of items in the playlist
    * @return the list with playlist items
    */
  private def playlistItems(count: Int): List[PlaylistModel.PlaylistItem] =
    (1 to count).map(playlistItem).toList

  /**
    * Returns a JSON representation of the given list of playlist items.
    *
    * @param items the playlist items
    * @return the JSON representation of this list
    */
  private def toJson(items: List[PlaylistModel.PlaylistItem]): String =
    items.toJson.prettyPrint

  /**
    * Returns a JSON representation of the given playlist position.
    *
    * @param position the position
    * @return the JSON representation of this position
    */
  private def toJson(position: PlaylistModel.CurrentPlaylistPosition): String =
    position.toJson.prettyPrint
end PlaylistLoaderSpec

/**
  * Test class for [[PlaylistLoader]].
  */
class PlaylistLoaderSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike, BeforeAndAfterAll,
  Matchers, FileTestHelper, OptionValues:
  def this() = this(ActorSystem("PlaylistLoaderSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    tearDownTestFile()
    super.afterAll()

  import PlaylistLoaderSpec.*

  /**
    * Invokes the default loader function provided by [[PlaylistLoader]] with
    * the provided parameters and expects a playlist to be returned. The result
    * is then passed to the partial check function to evaluate it.
    *
    * @param itemsPath    the path to the file with the playlist items
    * @param positionPath the path to the file with the current position
    * @param maxSize      the maximum file size
    * @param check        the check function
    * @return the result of the check
    */
  private def loadPlaylistAndCheckSuccess(itemsPath: Path, positionPath: Path, maxSize: Int = DefaultMaxFileSize)
                                         (check: PartialFunction[AudioPlayerCommands, Assertion]): Future[Assertion] =
    loadPlaylistAndCheck(itemsPath, positionPath, maxSize): optResult =>
      check(optResult.value)

  /**
    * Invokes the default loader function provided by [[PlaylistLoader]] with
    * the provided parameters and then calls the given check function with the
    * result.
    *
    * @param itemsPath    the path to the file with the playlist items
    * @param positionPath the path to the file with the current position
    * @param maxSize      the maximum file size
    * @param check        the check function
    * @return the result of the check
    */
  private def loadPlaylistAndCheck(itemsPath: Path, positionPath: Path, maxSize: Int = DefaultMaxFileSize)
                                  (check: Option[AudioPlayerCommands] => Assertion): Future[Assertion] =
    loadPlaylist(itemsPath, positionPath, maxSize) map : optPlaylist =>
      check(optPlaylist)

  /**
    * Invokes the default playlist loader function with the provided
    * parameters.
    *
    * @param itemsPath    the path to the file with the playlist items
    * @param positionPath the path to the file with the current position
    * @param maxSize      the maximum file size
    * @return the result of the loader function
    */
  private def loadPlaylist(itemsPath: Path,
                           positionPath: Path,
                           maxSize: Int = DefaultMaxFileSize): Future[Option[AudioPlayerCommands]] =
    val config = PlaylistLoader.Config(itemsPath, positionPath, maxSize)
    PlaylistLoader.defaultLoaderFunc(config)

  "PlaylistLoader" should "return None if the items file does not exist" in :
    val positionPath = writeFileContent(createPathInDirectory("irrelevant.pos"), "no position")
    val itemsPath = testDirectory.resolve("non-existing-file.lst")

    loadPlaylistAndCheck(itemsPath, positionPath): optResult =>
      optResult shouldBe empty

  it should "load a playlist with no position file" in :
    val ItemCount = 32
    val itemsPath = writeFileContent(createPathInDirectory("playlist-no-pos.lst"), toJson(playlistItems(ItemCount)))
    val positionPath = testDirectory.resolve("non-existing-file.pos")

    loadPlaylistAndCheckSuccess(itemsPath, positionPath):
      case setPl: AudioPlayerCommands.SetPlaylist =>
        setPl.positionOffset should be(0)
        setPl.timeOffset.toMillis should be(0)
        setPl.closePlaylist shouldBe true
        setPl.playlist.playedSongs shouldBe empty
        setPl.playlist.pendingSongs should contain theSameElementsInOrderAs fileIDs(1, ItemCount)

  it should "load a playlist with position information" in :
    val ItemCount = 20
    val CurrentIndex = 8
    val PositionOffset = 1024L
    val TimeOffset = 6000L
    val itemsPath = writeFileContent(createPathInDirectory("playlist-with-pos.lst"), toJson(playlistItems(ItemCount)))
    val position = PlaylistModel.CurrentPlaylistPosition(CurrentIndex, PositionOffset, TimeOffset)
    val positionPath = writeFileContent(createPathInDirectory("playlist-with-pos.pos"), toJson(position))

    loadPlaylistAndCheckSuccess(itemsPath, positionPath):
      case setPl: AudioPlayerCommands.SetPlaylist =>
        setPl.positionOffset should be(PositionOffset)
        setPl.timeOffset.toSeconds should be(TimeOffset)
        setPl.closePlaylist shouldBe true
        setPl.playlist.playedSongs should contain theSameElementsInOrderAs fileIDs(1, CurrentIndex).reverse
        setPl.playlist.pendingSongs should contain theSameElementsInOrderAs fileIDs(CurrentIndex + 1, ItemCount)

  it should "handle a position file with all values 0" in :
    val ItemCount = 20
    val itemsPath = writeFileContent(createPathInDirectory("playlist-zero-pos.lst"), toJson(playlistItems(ItemCount)))
    val position = PlaylistModel.CurrentPlaylistPosition(0, 0, 0)
    val positionPath = writeFileContent(createPathInDirectory("playlist-zero-pos.pos"), toJson(position))

    loadPlaylistAndCheckSuccess(itemsPath, positionPath):
      case setPl: AudioPlayerCommands.SetPlaylist =>
        setPl.positionOffset should be(0)
        setPl.timeOffset.toMillis should be(0)
        setPl.closePlaylist shouldBe true
        setPl.playlist.playedSongs shouldBe empty
        setPl.playlist.pendingSongs should contain theSameElementsInOrderAs fileIDs(1, ItemCount)

  it should "ignore a position file with invalid content" in :
    val ItemCount = 12
    val itemsPath = writeFileContent(
      createPathInDirectory("playlist-invalid-pos.lst"),
      toJson(playlistItems(ItemCount))
    )
    val positionPath = writeFileContent(createPathInDirectory("playlist-invalid-pos.pos"), FileTestHelper.TestData)

    loadPlaylistAndCheckSuccess(itemsPath, positionPath):
      case setPl: AudioPlayerCommands.SetPlaylist =>
        setPl.positionOffset should be(0)
        setPl.timeOffset.toMillis should be(0)
        setPl.closePlaylist shouldBe true
        setPl.playlist.playedSongs shouldBe empty
        setPl.playlist.pendingSongs should contain theSameElementsInOrderAs fileIDs(1, ItemCount)

  it should "ignore a position file that exceeds the maximum file size" in :
    val playlistJson = toJson(List(PlaylistModel.PlaylistItem("1")))
    val positionJson = toJson(PlaylistModel.CurrentPlaylistPosition(0, Long.MaxValue, Long.MaxValue))
    val itemsPath = writeFileContent(createPathInDirectory("playlist-pos-too-long.lst"), playlistJson)
    val positionPath = writeFileContent(createPathInDirectory("huge-position.pos"), positionJson)

    loadPlaylistAndCheckSuccess(itemsPath, positionPath, maxSize = playlistJson.length):
      case setPl: AudioPlayerCommands.SetPlaylist =>
        setPl.positionOffset should be(0)
        setPl.timeOffset.toMillis should be(0)

  it should "return a failed Future if the playlist items file is too large" in :
    val positionPath = writeFileContent(createPathInDirectory("playlist-too-large.lst"), FileTestHelper.TestData)

    recoverToSucceededIf[IllegalStateException]:
      loadPlaylist(
        positionPath,
        testDirectory.resolve("non-existing.pos"),
        FileTestHelper.TestData.length - 1
      )

  it should "return a failed Future if the playlist file is invalid" in :
    val playlistPath = writeFileContent(createPathInDirectory("playlist-invalid.lst"), FileTestHelper.TestData)

    recoverToSucceededIf[Framing.FramingException]:
      loadPlaylist(playlistPath, testDirectory.resolve("non-existing.pos"))
