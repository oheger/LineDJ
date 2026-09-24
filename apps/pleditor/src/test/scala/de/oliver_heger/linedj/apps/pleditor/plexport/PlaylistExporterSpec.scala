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

package de.oliver_heger.linedj.apps.pleditor.plexport

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.apps.pleditor.SongData
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Files
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.reflect.ClassTag

object PlaylistExporterSpec:
  /** The base name for synthetic media file IDs. */
  private val FileIDPrefix = "testFileID"

  /** The base URI for synthetic media files. */
  private val FileURIPrefix = "/test files/file"

  /** The prefix of the URI for downloading media files from the archive. */
  private val DownloadUriPrefix = "/api/archive/files/"

  /** The suffix of the URI for downloading media files from the archive. */
  private val DownloadUriSuffix = "/download"

  /** The chunk size for simulating a download with multiple data chunks. */
  private val DownloadChunkSize = 1024

  /** The chunk size for sending out progress notifications. */
  private val ProgressChunkSize = 512

  /**
    * Generates a synthetic ID for a media file based on the given index.
    *
    * @param index the index of the file
    * @return the generated file ID
    */
  private def mediaFileID(index: Int): String = s"$FileIDPrefix$index"

  /**
    * Generates a synthetic [[SongData]] object for the media file with the
    * given index.
    *
    * @param index the index of the file
    * @return the generated [[SongData]] object
    */
  private def songData(index: Int): SongData =
    val fileID = mediaFileID(index)
    val metadata = MediaMetadata(
      title = Some(s"Title $index"),
      artist = Some(s"Artist $index"),
      album = Some(s"Album $index"),
      inceptionYear = Some(2000 + index),
      trackNumber = Some(index),
      duration = Some(index * 60),
      size = index * 100,
      checksum = fileID
    )
    val fileInfo = ArchiveModel.MediaFileInfo(
      metadata = metadata,
      fileUri = MediaFileUri(s"$FileURIPrefix$index.mp3"),
      mediumID = Checksums.MediumChecksum(s"medium-$index")
    )
    SongData(fileID, Some(fileInfo))

  /**
    * Generates synthetic content for a media file based on the given index.
    *
    * @param index the index of the file
    * @return the content of this test media file
    */
  private def mediaFileContent(index: Int): String =
    s"Media file $index content - ${FileTestHelper.TestData}" * index
end PlaylistExporterSpec

/**
  * Test class for [[PlaylistExporter]].
  */
class PlaylistExporterSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, BeforeAndAfterAll,
  BeforeAndAfterEach, Matchers, MockitoSugar, FileTestHelper:
  def this() = this(ActorSystem("PlaylistExporterSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import PlaylistExporterSpec.*

  /**
    * The mock for the export configuration. It can be shared by all test 
    * cases.
    */
  private val exportConfig = createExportConfig()

  /**
    * Creates a mock for the export config that is prepared to return values
    * for the relevant properties.
    *
    * @return the mock for the export config
    */
  private def createExportConfig(): PlaylistExportConfig =
    val config = mock[PlaylistExportConfig]
    when(config.progressSize).thenReturn(ProgressChunkSize)
    config

  "A PlaylistExporter" should "export a playlist with 8 files successfully" in :
    val Count = 8
    val songs = (1 to Count).map(songData)
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, Count)
      .triggerExport(data)
      .waitForExportSuccess()

    checkExportedFiles(1, Count, digits = 1)

  it should "dynamically adjust the number of digits in file names to the playlist size" in :
    val Count = 20
    val songs = (1 to Count).map(songData)
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, Count)
      .triggerExport(data)
      .waitForExportSuccess()

    checkExportedFiles(1, Count, digits = 2)

  it should "replace special characters in song titles by underscores in file names" in :
    val title = "Song:\t My *, \"<Love>|Pet/Heart Come\\ - yes?"
    val expTitle = "Song__ My _, __Love__Pet_Heart Come_ - yes_"
    val expFileName = s"1 - $expTitle.mp3"
    val original = songData(1)
    val song = original.copy(
      optFileInfo = original.optFileInfo.map(info => info.copy(metadata = info.metadata.copy(title = Some(title))))
    )
    val data = PlaylistExporter.ExportData(List(song), testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequest(1)
      .triggerExport(data)
      .waitForExportSuccess()

    val expPath = testDirectory.resolve(expFileName)
    Files.exists(expPath) should be(true)
    Files.readAllBytes(expPath) should be(FileTestHelper.toBytes(mediaFileContent(1)))

  /**
    * Checks that an export produced the expected files on the target
    * directory.
    *
    * The method iterates over all indices in the given range. For each index
    * it checks that the file with the name generated by the export process
    * exists in the temporary test directory and that its content equals the
    * content of the corresponding test media file.
    *
    * @param fromIndex the start index (inclusive)
    * @param toIndex   the end index (inclusive)
    * @param digits    the number of digits for the numeric prefix
    */
  private def checkExportedFiles(fromIndex: Int, toIndex: Int, digits: Int): Unit =
    (fromIndex to toIndex).foreach: index =>
      val prefix = index.toString
      val paddedPrefix = if prefix.length >= digits then prefix
      else "0" * (digits - prefix.length) + prefix
      val expPath = testDirectory.resolve(s"$paddedPrefix - ${songData(index).getTitle}.mp3")
      Files.exists(expPath) should be(true)
      Files.readAllBytes(expPath) should be(FileTestHelper.toBytes(mediaFileContent(index)))

  /**
    * A test helper class that manages an exporter under test and its 
    * dependencies.
    */
  private class ExporterTestHelper:
    /** A mock for the [[ArchiveService]]. */
    private val archiveService = mock[ArchiveService]

    /** The test message bus for communication with the test exporter. */
    private val messageBus = new MessageBusTestImpl

    /**
      * Prepares the mock for the [[ArchiveService]] to expect a request to 
      * download the media file with an ID derived from the given index. The
      * request is answered with the content of the test media file based on
      * this index.
      *
      * @param index the index of the media file
      * @return this test helper
      */
    def expectDownloadRequest(index: Int): ExporterTestHelper =
      val fileID = mediaFileID(index)
      val request = HttpRequest(uri = s"$DownloadUriPrefix$fileID$DownloadUriSuffix")
      val content = mediaFileContent(index)
      val entity = HttpEntity(ContentTypes.`application/octet-stream`,
        Source.fromIterator(() => ByteString(content).grouped(DownloadChunkSize)))
      when(archiveService.sendRequest(request))
        .thenReturn(Future.successful(HttpResponse(status = StatusCodes.OK, entity = entity)))
      this

    /**
      * Prepares the mock for the [[ArchiveService]] to expect download
      * requests for all media files with IDs derived from the given range of
      * indices. Each request is answered with the content of the test media
      * file based on the corresponding index.
      *
      * @param fromIndex the start index (inclusive)
      * @param toIndex   the end index (inclusive)
      * @return this test helper
      */
    def expectDownloadRequests(fromIndex: Int, toIndex: Int): ExporterTestHelper =
      (fromIndex to toIndex).foreach: index =>
        expectDownloadRequest(index)
      this

    /**
      * Expects a message of a specific type on the message bus and returns it.
      *
      * @param t the class tag
      * @tparam M the type of the message
      * @return the message
      */
    def nextMessage[M](using t: ClassTag[M]): M = messageBus.expectMessageType[M]

    /**
      * Listens on the message bus, ignoring progress messages, until an export
      * result message is received. This message is then returned.
      *
      * @return the result message that was received
      */
    @tailrec final def waitForExportEnd(): PlaylistExporter.ExportResult =
      nextMessage[PlaylistExporter.ExportProgress | PlaylistExporter.ExportResult] match
        case r: PlaylistExporter.ExportResult => r
        case _ => waitForExportEnd()

    /**
      * Listens on the message bus until a success result for the export 
      * operation is received. Progress messages are ignored.
      *
      * @return this test helper
      */
    def waitForExportSuccess(): ExporterTestHelper =
      waitForExportEnd() should be(PlaylistExporter.ExportResult.Success)
      this

    /**
      * Starts an asynchronous export operation with the given parameters.
      * Other helper methods can then be used to monitor the progress or wait
      * for the end.
      *
      * @param data the export data
      * @return this test helper
      */
    def triggerExport(data: PlaylistExporter.ExportData): ExporterTestHelper =
      val exporter = new PlaylistExporter(
        archiveService = archiveService,
        actorSystem = system,
        config = exportConfig,
        messageBus = messageBus,
        exportData = data
      )
      exporter.run()
      this
      