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
import org.apache.pekko.stream.scaladsl.Framing
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}

import java.nio.file.Path
import scala.concurrent.Future

object PlaylistLoaderSpec:
  /** The maximum file size used by the test cases. */
  private val DefaultMaxFileSize = 64000
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
  import PlaylistTestHelper.*

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
    val itemsPath = writeFileContent(createPathInDirectory("playlist-no-pos.lst"), playlistItems(ItemCount).toJson)
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
    val itemsPath = writeFileContent(createPathInDirectory("playlist-with-pos.lst"), playlistItems(ItemCount).toJson)
    val position = PlaylistModel.CurrentPlaylistPosition(CurrentIndex, PositionOffset, TimeOffset)
    val positionPath = writeFileContent(createPathInDirectory("playlist-with-pos.pos"), position.toJson)

    loadPlaylistAndCheckSuccess(itemsPath, positionPath):
      case setPl: AudioPlayerCommands.SetPlaylist =>
        setPl.positionOffset should be(PositionOffset)
        setPl.timeOffset.toSeconds should be(TimeOffset)
        setPl.closePlaylist shouldBe true
        setPl.playlist.playedSongs should contain theSameElementsInOrderAs fileIDs(1, CurrentIndex).reverse
        setPl.playlist.pendingSongs should contain theSameElementsInOrderAs fileIDs(CurrentIndex + 1, ItemCount)

  it should "handle a position file with all values 0" in :
    val ItemCount = 20
    val itemsPath = writeFileContent(createPathInDirectory("playlist-zero-pos.lst"), playlistItems(ItemCount).toJson)
    val position = PlaylistModel.CurrentPlaylistPosition(0, 0, 0)
    val positionPath = writeFileContent(createPathInDirectory("playlist-zero-pos.pos"), position.toJson)

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
      playlistItems(ItemCount).toJson
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
    val playlistJson = List(PlaylistModel.PlaylistItem("1")).toJson
    val positionJson = PlaylistModel.CurrentPlaylistPosition(0, Long.MaxValue, Long.MaxValue).toJson
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
