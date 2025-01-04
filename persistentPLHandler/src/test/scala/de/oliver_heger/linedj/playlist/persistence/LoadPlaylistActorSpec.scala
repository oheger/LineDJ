/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio.SetPlaylist
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.playlist.persistence.PersistentPlaylistModel.{CurrentPlaylistPosition, LoadedPlaylist}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, Terminated}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Path, Paths}
import scala.annotation.tailrec

object LoadPlaylistActorSpec extends PlaylistTestHelper:
  /** The maximum file size loaded by the test actor. */
  private val MaxFileSize = 8192

  /**
    * Generates the JSON representation for a song in the persistent playlist.
    *
    * @param idx the index of the test song
    * @return the JSON representation for this test song
    */
  private def generatePersistentSong(idx: Int): String =
    val mid = mediumFor(idx)

    def descPath: String =
      mid.mediumDescriptionPath map (p => s""""mediumDescriptionPath": "$p", """) getOrElse ""

    s"""{ "index": $idx, "mediumURI": "${mid.mediumURI}",$descPath""" +
      s""""archiveComponentID": "${mid.archiveComponentID}", "uri": "${songUri(idx)}" }"""

  /**
    * Generates a playlist in JSON representation with the given number of
    * songs. The playlist can then be written to disk.
    *
    * @param count the number of songs in the playlist
    * @return a JSON representation of this playlist
    */
  private def generatePersistentPlaylist(count: Int): String =
    val songs = (0 until count) map generatePersistentSong
    songs.mkString("[\n", ",\n", "]")

  /**
    * Generates a JSON representation of a position object.
    *
    * @param position the position
    * @return the JSON representation of this position
    */
  private def generatePersistentPosition(position: CurrentPlaylistPosition): String =
    s"""{
       |"index": ${position.index},
       |"position": ${position.position},
       |"time": ${position.time}
       |}
  """.stripMargin

/**
  * Test class for ''LoadPlaylistActor''.
  */
class LoadPlaylistActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with FileTestHelper:
  def this() = this(ActorSystem("LoadPlaylistActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    tearDownTestFile()

  import LoadPlaylistActorSpec._

  "A LoadPlaylistActor" should "return an empty list if no data can be read" in:
    val helper = new LoadActorTestHelper

    helper.triggerLoad(Paths get "nonExistingPlaylist.json",
      Paths get "nonExistingPos.json")
      .expectPlaylistResult(Playlist(Nil, Nil), closed = false)

  it should "load the content of a playlist file" in:
    val SongCount = 8
    val strPlaylist = generatePersistentPlaylist(SongCount)
    val playlistPath = createDataFile(strPlaylist)
    val expPlaylist = generatePlaylist(SongCount, 0)
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, Paths get "nonExistingPos.json")
      .expectPlaylistResult(expPlaylist)

  it should "filter out invalid items from the playlist file" in:
    val content =
      """[
        |{ "index": 0, "mediumURI": "medium1","mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1", "uri": "song://TestSong_0.mp3" },
        |{ "mediumURI": "medium1","mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1", "uri": "song://missingIndex.mp3" },
        |{ "index": "noInteger", "mediumURI": "medium1",
        |"mediumDescriptionPath": "medium1.settings", "archiveComponentID": "component1",
        |"uri": "song://InvalidIndex.mp3" },
        |{ "index": 1, "mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1", "uri": "song://NoMediumURI.mp3" },
        |{ "index": 2, "mediumURI": "medium1","mediumDescriptionPath": "medium1.settings",
        |"uri": "song://NoArchiveComponentID.mp3" },
        |{ "index": 3, "mediumURI": "noSongURI","mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1" },
        |{ "index": 1, "mediumURI": "medium2","archiveComponentID": "component2",
        |"uri": "song://TestSong_1.mp3" }
        |]
      """.stripMargin
    val playlistPath = createDataFile(content)
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, Paths get "nonExistingPos.json")
      .expectPlaylistResult(generatePlaylist(2, 0))

  it should "evaluate the checksum property in a persistent playlist" in:
    val Checksum = "CHECKSUM_FOR_TEST_MEDIUM"
    val content =
      s"""[
        |{ "index": 0, "mediumURI": "medium1","mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1", "uri": "song://TestSong_0.mp3",
        |"mediumChecksum": "$Checksum" },
        |{ "index": 1, "mediumURI": "medium2","archiveComponentID": "component2",
        |"uri": "song://TestSong_1.mp3", "mediumChecksum": "$Checksum" }
        |]
      """.stripMargin
    val playlistPath = createDataFile(content)
    val expPlaylist = generatePlaylistWithChecksum(2, 0, Checksum)
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, Paths get "nonExistingPos.json")
      .expectPlaylistResult(expPlaylist)

  it should "evaluate the position file" in:
    val SongCount = 8
    val position = CurrentPlaylistPosition(5, 0, 0)
    val playlistPath = createDataFile(generatePersistentPlaylist(SongCount))
    val positionPath = createDataFile(generatePersistentPosition(position))
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, positionPath)
      .expectPlaylistResult(generatePlaylist(SongCount, position.index))

  it should "handle the current index even if it cannot be found" in:
    val content =
      """[
        |{ "index": 0, "mediumURI": "medium1","mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1", "uri": "song://TestSong_0.mp3" },
        |{ "index": 1, "mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1", "uri": "song://NoMediumURI.mp3" },
        |{ "index": 2, "mediumURI": "medium2","archiveComponentID": "component2",
        |"uri": "song://TestSong_1.mp3" }
        |]
      """.stripMargin
    val playlistPath = createDataFile(content)
    val posPath = createDataFile(generatePersistentPosition(CurrentPlaylistPosition(1, 0, 0)))
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, posPath)
      .expectPlaylistResult(generatePlaylist(2, 1))

  it should "evaluate the offsets in the position file" in:
    val SongCount = 4
    val position = CurrentPlaylistPosition(1, 1000, 6000)
    val playlistPath = createDataFile(generatePersistentPlaylist(SongCount))
    val positionPath = createDataFile(generatePersistentPosition(position))
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, positionPath)
      .expectPlaylistResult(generateSetPlaylist(SongCount, position.index, position.position,
        position.time))

  it should "apply the offsets in the position file only to the correct index" in:
    val content =
      """[
        |{ "index": 0, "mediumURI": "medium1","mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1", "uri": "song://TestSong_0.mp3" },
        |{ "index": 1, "mediumDescriptionPath": "medium1.settings",
        |"archiveComponentID": "component1", "uri": "song://NoMediumURI.mp3" },
        |{ "index": 2, "mediumURI": "medium2","archiveComponentID": "component2",
        |"uri": "song://TestSong_1.mp3" }
        |]
      """.stripMargin
    val playlistPath = createDataFile(content)
    val posPath = createDataFile(generatePersistentPosition(CurrentPlaylistPosition(1, 10, 11)))
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, posPath)
      .expectPlaylistResult(generatePlaylist(2, 1))

  it should "correctly handle an index larger than the number of songs" in:
    val SongCount = 4
    val position = CurrentPlaylistPosition(SongCount + 1, 1000, 6000)
    val playlistPath = createDataFile(generatePersistentPlaylist(SongCount))
    val positionPath = createDataFile(generatePersistentPosition(position))
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, positionPath)
      .expectPlaylistResult(generatePlaylist(SongCount, SongCount))

  it should "stop after sending a load result on the message bus" in:
    val helper = new LoadActorTestHelper

    helper.triggerLoad(Paths get "somePl.json", Paths get "somePos.json")
      .verifyLoaderActorStopped()

  it should "only read playlist files up to the maximum size" in:
    val playlistPath = createDataFile(generatePersistentPlaylist(100))
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, Paths get "somePos.json")
      .expectPlaylistResult(Playlist(Nil, Nil), closed = false)

  it should "only read position files up to the maximum size" in:
    val SongCount = 4
    val Properties = 300

    @tailrec def generateProperties(lst: List[String], index: Int): List[String] =
      if index == Properties then lst
      else generateProperties(s"""  "property_$index":"value_$index"""" :: lst, index + 1)

    val posContent = generateProperties(Nil, 0).mkString(
      """{"index": 2,""", ",\n", "\n}")
    posContent.length should be > MaxFileSize
    val playlistPath = createDataFile(generatePersistentPlaylist(SongCount))
    val positionPath = createDataFile(posContent)
    val helper = new LoadActorTestHelper

    helper.triggerLoad(playlistPath, positionPath)
      .expectPlaylistResult(generatePlaylist(SongCount, 0))

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class LoadActorTestHelper:
    /** The test message bus. */
    private val messageBus = new MessageBusTestImpl

    /** The test actor instance. */
    private val loader = createLoaderActor()

    /**
      * Sends a request to load a playlist to the test actor.
      *
      * @param playlistPath the path for the playlist file
      * @param positionPath the path for the position file
      * @return this test helper
      */
    def triggerLoad(playlistPath: Path, positionPath: Path): LoadActorTestHelper =
      val request = LoadPlaylistActor.LoadPlaylistData(playlistPath, positionPath,
        MaxFileSize, messageBus)
      loader ! request
      this

    /**
      * Expects that the test actor has published a playlist as result on the
      * message bus and returns it.
      *
      * @return the ''SetPlaylist'' command obtained from the test actor
      */
    def fetchPlaylistResult(): SetPlaylist =
      messageBus.expectMessageType[LoadedPlaylist].setPlaylist

    /**
      * Expects that the test actor has published the specified playlist as
      * result of a load operation on the message bus.
      *
      * @param playlist the expected playlist
      * @param posOfs   the position offset
      * @param timeOfs  the time offset
      * @param closed   flag whether the playlist should be closed
      * @return this test helper
      */
    def expectPlaylistResult(playlist: Playlist, posOfs: Long = 0, timeOfs: Long = 0,
                             closed: Boolean = true): LoadActorTestHelper =
      expectPlaylistResult(SetPlaylist(playlist = playlist, positionOffset = posOfs,
        timeOffset = timeOfs, closePlaylist = closed))

    /**
      * Expects that the test actor has published the specified playlist
      * command as result of a load operation on the message bus.
      *
      * @param cmd the command defining the new playlist
      * @return this test helper
      */
    def expectPlaylistResult(cmd: SetPlaylist): LoadActorTestHelper =
      fetchPlaylistResult() should be(cmd)
      this

    /**
      * Checks that the loader actor has been stopped.
      *
      * @return this test helper
      */
    def verifyLoaderActorStopped(): LoadActorTestHelper =
      val probe = TestProbe()
      probe watch loader
      probe.expectMsgType[Terminated]
      this

    /**
      * Creates an instance of the test actor.
      *
      * @return the test actor instance
      */
    private def createLoaderActor(): ActorRef =
      system.actorOf(Props[LoadPlaylistActor]())

