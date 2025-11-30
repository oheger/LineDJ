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

package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.archive.server.model.ArchiveCommands
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object MediaFileActorSpec:
  /**
    * Returns the ID of the test file with the given index.
    *
    * @param index the index of the test file
    * @return the ID of this test file
    */
  private def fileID(index: Int): String = s"media-file-$index"

  /**
    * Returns the URI for the test file with the given index.
    *
    * @param index the index of the test file
    * @return the URI of this test file
    */
  private def mediaFileUri(index: Int): MediaFileUri =
    MediaFileUri(s"test/file/$index.mp3")

  /**
    * Creates a [[MediaMetadata]] object for the test file with the given
    * index.
    *
    * @param index the index of the test file
    * @return the metadata for this test file
    */
  private def metadata(index: Int): MediaMetadata =
    MediaMetadata(
      title = Some(s"TestTitle$index"),
      artist = Some(s"TestArtist$index"),
      album = Some(s"TestAlbum$index"),
      size = 10000 + index,
      checksum = fileID(index)
    )

  /**
    * Returns the ID of the test medium with the given index.
    *
    * @param index the index of the test medium
    * @return the ID of this test medium
    */
  private def mediumID(index: Int): Checksums.MediumChecksum =
    Checksums.MediumChecksum(s"test-medium-id-$index")
end MediaFileActorSpec

/**
  * Test class for [[MediaFileActor]].
  */
class MediaFileActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with OptionValues:

  import MediaFileActorSpec.*

  "A MediaFileActor" should "return information about an existing file" in :
    val actor = testKit.spawn(MediaFileActor.behavior())
    (1 to 16).foreach: index =>
      actor ! MediaFileActor.MediaFileCommand.AddFile(
        mediumID = mediumID(index / 2),
        fileUri = mediaFileUri(index),
        metadata = metadata(index)
      )
    val testIndex = 4
    val testFileID = fileID(testIndex)

    val probe = testKit.createTestProbe[ArchiveCommands.GetFileInfoResponse]()
    actor ! MediaFileActor.MediaFileCommand.GetFileInfo(testFileID, probe.ref)

    val response = probe.expectMessageType[ArchiveCommands.GetFileInfoResponse]
    response.fileID should be(testFileID)
    val info = response.optFileInfo.value
    info.mediumID should be(mediumID(testIndex / 2))
    info.metadata should be(metadata(testIndex))
    info.fileUri should be(mediaFileUri(testIndex))

  it should "handle a non-existing file ID" in :
    val testFileID = fileID(42)
    val actor = testKit.spawn(MediaFileActor.behavior())

    val probe = testKit.createTestProbe[ArchiveCommands.GetFileInfoResponse]()
    actor ! MediaFileActor.MediaFileCommand.GetFileInfo(testFileID, probe.ref)

    val response = probe.expectMessageType[ArchiveCommands.GetFileInfoResponse]
    response.fileID should be(testFileID)
    response.optFileInfo shouldBe empty
    