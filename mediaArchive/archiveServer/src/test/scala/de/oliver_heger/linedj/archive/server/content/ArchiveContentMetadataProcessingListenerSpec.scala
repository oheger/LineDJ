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

import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumDescription, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata, MetadataProcessingEvent}
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingSuccess}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

object ArchiveContentMetadataProcessingListenerSpec:
  /** The ID of a test medium. */
  private val TestMediumID = MediumID("someMediumURI", None, "someComponentID")

  /** The checksum of a test medium. */
  private val TestChecksum = Checksums.MediumChecksum("some-test-checksum")
  
  /** The name of the archive used by tests. */
  private val ArchiveName = "TheTestArchive"
end ArchiveContentMetadataProcessingListenerSpec

/**
  * Test class for [[ArchiveContentMetadataProcessingListener]].
  */
class ArchiveContentMetadataProcessingListenerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike
  with Matchers:

  import ArchiveContentMetadataProcessingListenerSpec.*

  "An ArchiveContentMetadataProcessingListener" should "propagate information about media" in :
    val probeContent = testKit.createTestProbe[ArchiveCommands.UpdateArchiveContentCommand]()
    val mediumEvent = MetadataProcessingEvent.MediumAvailable(
      mediumID = TestMediumID,
      checksum = TestChecksum,
      files = List(MediaFileUri("some/path/song.mp3")),
      rootPath = Paths.get("archiveRoot"),
      archiveName = ArchiveName
    )
    val description = MediumDescription("someName", "Test description", "RandomAlbums")
    val descriptionEvent = MetadataProcessingEvent.MediumDescriptionAvailable(TestMediumID, description)
    val expectedDetails = ArchiveModel.MediumDetails(
      overview = ArchiveModel.MediumOverview(TestChecksum, description.name),
      description = description.description,
      orderMode = Some(ArchiveModel.OrderMode.RandomAlbums),
      archiveName = ArchiveName
    )

    val listener = testKit.spawn(ArchiveContentMetadataProcessingListener.behavior(probeContent.ref))
    listener ! mediumEvent
    listener ! descriptionEvent

    probeContent.expectMessage(ArchiveCommands.UpdateArchiveContentCommand.AddMedium(expectedDetails))

  it should "propagate information about media if message arrive in an alternative order" in :
    val probeContent = testKit.createTestProbe[ArchiveCommands.UpdateArchiveContentCommand]()
    val mediumEvent = MetadataProcessingEvent.MediumAvailable(
      mediumID = TestMediumID,
      checksum = TestChecksum,
      files = List(MediaFileUri("some/path/song.mp3")),
      rootPath = Paths.get("archiveRoot"),
      archiveName = ArchiveName
    )
    val description = MediumDescription("someName", "Test description", "RandomAlbums")
    val descriptionEvent = MetadataProcessingEvent.MediumDescriptionAvailable(TestMediumID, description)
    val expectedDetails = ArchiveModel.MediumDetails(
      overview = ArchiveModel.MediumOverview(TestChecksum, description.name),
      description = description.description,
      orderMode = Some(ArchiveModel.OrderMode.RandomAlbums),
      archiveName = ArchiveName
    )

    val listener = testKit.spawn(ArchiveContentMetadataProcessingListener.behavior(probeContent.ref))
    listener ! descriptionEvent
    listener ! mediumEvent

    probeContent.expectMessage(ArchiveCommands.UpdateArchiveContentCommand.AddMedium(expectedDetails))

  it should "handle an invalid order mode" in :
    val probeContent = testKit.createTestProbe[ArchiveCommands.UpdateArchiveContentCommand]()
    val mediumEvent = MetadataProcessingEvent.MediumAvailable(
      mediumID = TestMediumID,
      checksum = TestChecksum,
      files = List(MediaFileUri("some/path/song.mp3")),
      rootPath = Paths.get("archiveRoot"),
      archiveName = ArchiveName
    )
    val description = MediumDescription("someName", "Test description", "unknownOrder")
    val descriptionEvent = MetadataProcessingEvent.MediumDescriptionAvailable(TestMediumID, description)
    val expectedDetails = ArchiveModel.MediumDetails(
      overview = ArchiveModel.MediumOverview(TestChecksum, description.name),
      description = description.description,
      orderMode = None,
      archiveName = ArchiveName
    )

    val listener = testKit.spawn(ArchiveContentMetadataProcessingListener.behavior(probeContent.ref))
    listener ! mediumEvent
    listener ! descriptionEvent

    probeContent.expectMessage(ArchiveCommands.UpdateArchiveContentCommand.AddMedium(expectedDetails))

  it should "stop itself when receiving a ProcessingCompleted event" in :
    val probeContent = testKit.createTestProbe[ArchiveCommands.UpdateArchiveContentCommand]()
    val completedEvent = MetadataProcessingEvent.ProcessingCompleted(null)

    val listener = testKit.spawn(ArchiveContentMetadataProcessingListener.behavior(probeContent.ref))
    listener ! completedEvent

    probeContent.expectTerminated(listener)

  it should "propagate information about a media file" in :
    val metadata = MediaMetadata(
      title = Some("Cool Song"),
      size = 123456,
      checksum = "checksum-of-the-cool-song"
    )
    val processingResult = MetadataProcessingSuccess(
      mediumID = TestMediumID,
      uri = MediaFileUri("someUri"),
      metadata = metadata
    )
    val probeContent = testKit.createTestProbe[ArchiveCommands.UpdateArchiveContentCommand]()

    val listener = testKit.spawn(ArchiveContentMetadataProcessingListener.behavior(probeContent.ref))
    listener ! MetadataProcessingEvent.ProcessingResultAvailable(TestChecksum, processingResult)

    probeContent.expectMessage(
      ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
      TestChecksum,
      processingResult.uri,
      metadata)
    )

  it should "ignore a failure processing result" in :
    val metadata = MediaMetadata(
      title = Some("Another Cool Song"),
      size = 654321,
      checksum = "checksum-of-another-cool-song"
    )
    val processingResult = MetadataProcessingSuccess(
      mediumID = TestMediumID,
      uri = MediaFileUri("someOtherUri"),
      metadata = metadata
    )
    val failedResult = MetadataProcessingError(
      mediumID = TestMediumID,
      uri = MediaFileUri("failedFile"),
      exception = new IllegalStateException("Test exception: Processing of media file failed.")
    )
    val probeContent = testKit.createTestProbe[ArchiveCommands.UpdateArchiveContentCommand]()

    val listener = testKit.spawn(ArchiveContentMetadataProcessingListener.behavior(probeContent.ref))
    listener ! MetadataProcessingEvent.ProcessingResultAvailable(TestChecksum, failedResult)
    listener ! MetadataProcessingEvent.ProcessingResultAvailable(TestChecksum, processingResult)

    probeContent.expectMessage(
      ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(TestChecksum, processingResult.uri, metadata)
    )

  it should "derive a title for metadata from the file URI" in :
    val uri = MediaFileUri("path/to/album/My%20song.mp3")
    val metadata = MediaMetadata(size = 1000, checksum = "song-without-data")
    val expectedMetadata = metadata.copy(title = Some("My song"))
    val processingResult = MetadataProcessingSuccess(TestMediumID, uri, metadata)
    val probeContent = testKit.createTestProbe[ArchiveCommands.UpdateArchiveContentCommand]()

    val listener = testKit.spawn(ArchiveContentMetadataProcessingListener.behavior(probeContent.ref))
    listener ! MetadataProcessingEvent.ProcessingResultAvailable(TestChecksum, processingResult)

    val expectedCommand = ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
      TestChecksum,
      uri,
      expectedMetadata
    )
    probeContent.expectMessage(expectedCommand)
