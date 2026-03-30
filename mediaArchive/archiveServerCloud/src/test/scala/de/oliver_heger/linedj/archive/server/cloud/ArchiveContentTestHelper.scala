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

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess

/**
  * A test helper object providing functionality to generate test data for
  * cloud archives. This test data is required by multiple test classes.
  */
object ArchiveContentTestHelper:
  /** The name of a test archive. */
  final val TestArchiveName = "Test-Archive"

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
    * Generates a medium details object for a test medium.
    *
    * @param index the index of the test medium
    * @return the details for this test medium
    */
  def testMediumDetails(index: Int): ArchiveModel.MediumDetails =
    ArchiveModel.MediumDetails(
      overview = ArchiveModel.MediumOverview(testMediumID(index), s"TestMedium-$index"),
      description = s"Description of test medium $index.",
      orderMode = Some(ArchiveModel.OrderMode.fromOrdinal(index % ArchiveModel.OrderMode.values.length)),
      archiveName = TestArchiveName
    )

  /**
    * Generates a valid medium description for a test medium.
    *
    * @param index          the index of the test medium
    * @param optDescription an optional description
    * @return the medium description for this test medium
    */
  def testMediumDescription(index: Int, optDescription: Option[String] = None): String =
    val details = testMediumDetails(index)
    val description = optDescription.getOrElse(details.description)
    s"""
       |{
       |  "description": "$description",
       |  "name": "${details.title}",
       |  "orderMode": "${details.orderMode.get}"
       |}
       |""".stripMargin

  /**
    * Generates a number of data objects to simulate metadata for the songs of
    * a test medium.
    *
    * @param index    the index of the test medium
    * @param optCount the optional number of songs to generate
    * @return data about the songs on this test medium
    */
  def testSongDataForMedium(index: Int, optCount: Option[Int] = None): List[MetadataProcessingSuccess] =
    val mediumID = MediumID("someUri" + index, Some("someDescription" + index), testMediumID(index).checksum)
    val count = optCount.getOrElse(index)
    (1 to count).map: track =>
      MetadataProcessingSuccess(
        mediumID = mediumID,
        uri = MediaFileUri(s"/medium_$index/songs/$track"),
        metadata = MediaMetadata(
          size = track * 1000 + index,
          checksum = s"song-checksum-$index-$track",
          trackNumber = Some(track),
          title = Some(s"Song $track on medium $index")
        )
      )
    .toList

  /**
    * Generates valid metadata for a test medium consisting of a number of test
    * songs.
    *
    * @param index    the index of the test medium
    * @param optCount the optional number of songs to generate
    * @return the metadata for this test medium
    */
  def testMediumMetadata(index: Int, optCount: Option[Int] = None): String =
    testSongDataForMedium(index, optCount).map(metadataToJson).mkString(start = "[", sep = ",", end = "]")

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

  /**
    * Generates a string with the JSON representation for the given metadata
    * about a song.
    *
    * @param data the data object with all information about a song
    * @return the JSON representation for this data
    */
  private def metadataToJson(data: MetadataProcessingSuccess): String =
    s"""{
       |  "checksum": "${data.metadata.checksum}",
       |  "size": ${data.metadata.size},
       |  "title": "${data.metadata.title.get}",
       |  "trackNumber": ${data.metadata.trackNumber.get},
       |  "uri": "${data.uri.uri}"
       |}
       |""".stripMargin
