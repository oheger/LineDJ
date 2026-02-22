/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.shared.archive.metadata.Checksums

/**
  * A test helper object providing functionality to generate test data for
  * cloud archives. This test data is required by multiple test classes.
  */
object ArchiveContentTestHelper:
  /** The prefix for generated test medium IDs. */
  private val TestMediumIDPrefix = "testMedium-"

  /**
    * Generates an ID for the test medium with the given index.
    *
    * @param index the index of the test medium
    * @return the ID for this test medium
    */
  def testMediumID(index: Int): Checksums.MediumChecksum =
    Checksums.MediumChecksum(s"$TestMediumIDPrefix$index")

  /**
    * Extracts the ID of test medium for the given medium ID.
    *
    * @param id the ID of the test medium
    * @return the corresponding test medium index
    */
  def testMediumIndex(id: Checksums.MediumChecksum): Int =
    id.checksum.substring(TestMediumIDPrefix.length).toInt

  /**
    * Generates a [[MediumEntry]] object for the test medium with the given
    * index.
    *
    * @param index the index of the test medium
    * @return the entry for this test medium
    */
  def testMediumEntry(index: Int): MediumEntry =
    MediumEntry(testMediumID(index), 20260216213017L + index * 1000)

  /**
    * Generates text to represent a medium description for a test medium. Note
    * that for this test class, the exact format of the test data is
    * irrelevant; only some unique text needs to be generated.
    *
    * @param index the index of the test medium
    * @return the medium description for this test medium
    */
  def testMediumDescription(index: Int): String =
    s"Description for medium $index: ${FileTestHelper.TestData}"

  /**
    * Generates metadata for a test medium analogously to
    * [[testMediumDescription]].
    *
    * @param index the index of the test medium
    * @return the metadata for this test medium
    */
  def testMediumMetadata(index: Int): String =
    s"Metadata for medium $index: ${FileTestHelper.TestData}"

  /**
    * Generates a [[CloudArchiveContent]] object with the given number of test
    * media starting from index 1.
    *
    * @param mediaCount the number of media to be contained
    * @return the content object with these test media
    */
  def archiveContent(mediaCount: Int): CloudArchiveContent =
    val media = (1 to mediaCount).foldRight(Map.empty[Checksums.MediumChecksum, MediumEntry]): (index, map) =>
      val entry = testMediumEntry(index)
      map + (entry.id -> entry)
    CloudArchiveContent(media)
