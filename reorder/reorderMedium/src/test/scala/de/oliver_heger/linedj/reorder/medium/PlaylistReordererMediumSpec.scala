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

package de.oliver_heger.linedj.reorder.medium

import de.oliver_heger.linedj.reorder.spi.MetadataExtractorTestHelper.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[PlaylistReordererMedium]].
  */
class PlaylistReordererMediumSpec extends AnyFlatSpec with Matchers:
  "A PlaylistReordererMedium" should "return a name" in:
    val reorder = new PlaylistReordererMedium

    reorder.name should not be null

  it should "order files by their URI" in:
    val songA = createFile(uri = "A")
    val songB = createFile(uri = "B")
    val songC = createFile(uri = "C")
    val songD = createFile(uri = "D")
    val songs = List(songB, songD, songA, songC)
    val orderedSongs = List(songA, songB, songC, songD)
    val reorder = new PlaylistReordererMedium

    reorder.reorder(songs, TestMetadataExtractor) should be(orderedSongs)
