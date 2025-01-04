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

import de.oliver_heger.linedj.playlist.persistence.PersistentPlaylistModel.PlaylistItemData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

/**
  * Test class for [[PersistentPlaylistParser]].
  */
class PersistentPlaylistParserSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("PersistentPlaylistParserSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "PersistentPlaylistParser" should "parse a playlist file" in :
    val playlistPath = Paths.get(getClass.getResource("/playlist.json").toURI)
    val source = FileIO.fromPath(playlistPath)
    val sink = Sink.fold[List[PlaylistItemData], PlaylistItemData](List.empty) { (lst, item) =>
      item :: lst
    }
    val expectedPlaylist = List(
      PlaylistItemData(
        index = 0,
        fileID = MediaFileID(
          mediumID = MediumID("Medium1", Some("Medium1/playlist.settings"), "test-archive"),
          uri = "Medium1/1%20-%20TestArtist/01%20TestTitle1.mp3",
          checksum = Some("7F35F692EE26E62652FDC63B61A9A3E550367F18")
        )
      ),
      PlaylistItemData(
        index = 1,
        fileID = MediaFileID(
          mediumID = MediumID("Medium2", Some("Medium2/playlist.settings"), "test-archive"),
          uri = "Medium2/2%20-%20TestArtist2/07%20TestTitle2.mp3",
          checksum = Some("6F35F692EE26E62652FDC63B61A9A3E550367F19")
        )
      ),
      PlaylistItemData(
        index = 2,
        fileID = MediaFileID(
          mediumID = MediumID("Medium3", None, "test-archive"),
          uri = "Medium3/3%20-%20TestArtist3/05%20TestTitle3.mp3",
          checksum = None
        )
      )
    )

    PersistentPlaylistParser.parsePlaylist(source).runWith(sink) map { results =>
      results.reverse should contain theSameElementsInOrderAs expectedPlaylist
    }
    