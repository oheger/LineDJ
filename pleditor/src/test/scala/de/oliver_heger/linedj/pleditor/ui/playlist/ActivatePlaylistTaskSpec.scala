/*
 * Copyright 2015-2024 The Developers Team.
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
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ActivatePlaylistTaskSpec:
  /** A test medium used by test songs. */
  private val TestMedium = MediumID("testMedium", None)

  /**
    * Creates the ID for a test song in the playlist.
    *
    * @param idx the index of the test song
    * @return the ID of this test song
    */
  private def fileID(idx: Int): MediaFileID =
    MediaFileID(TestMedium, "testSong" + idx)

  /**
    * Creates a ''SongData'' object from the specified index.
    *
    * @param idx the index of the test song
    * @return the data object representing this test song
    */
  private def testSong(idx: Int): SongData =
    SongData(fileID(idx), MediaMetaData(), "title" + idx, "artist" + idx, "album" + idx)

/**
  * Test class for ''ActivatePlaylistTask''.
  */
class ActivatePlaylistTaskSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import ActivatePlaylistTaskSpec._
  import scala.jdk.CollectionConverters._

  "An ActivatePlaylistTask" should "send a correct SetPlaylist message" in:
    val indices = List(1, 2, 4, 8, 11, 23)
    val model = new util.ArrayList[SongData](indices.size)
    model.addAll((indices map testSong).asJava)
    val plService = mock[PlaylistService[Playlist, MediaFileID]]
    val bus = mock[MessageBus]
    val playlist = mock[Playlist]
    val expSongIds = indices map fileID
    when(plService.toPlaylist(expSongIds, 0)).thenReturn(Some(playlist))

    val task = new ActivatePlaylistTask(plService, bus, model)
    task.run()
    verify(bus).publish(SetPlaylist(playlist))

  it should "handle a None result of the playlist service" in:
    val model = new util.ArrayList[SongData]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]
    val bus = mock[MessageBus]
    when(plService.toPlaylist(any(), anyInt())).thenReturn(None)

    val task = new ActivatePlaylistTask(plService, bus, model)
    task.run()
    verifyNoInteractions(bus)
