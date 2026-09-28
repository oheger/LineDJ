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
import org.apache.pekko.stream.KillSwitch
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.when
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Files
import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer
import scala.compiletime.uninitialized
import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag
import scala.util.Using

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
  private val DownloadChunkSize = 256

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
  private def songData(index: Int): SongData = songData(index, mediaFileContent(index).length.toLong)

  /**
    * Generates a synthetic [[SongData]] object for the media file with the
    * given index and the given size. Tests can use this function to define a
    * song with a specific file size. Note that a download request for this song
    * needs to be answered with content of exactly this size, for instance by
    * means of [[ExporterTestHelper.expectDownloadRequest]].
    *
    * @param index the index of the file
    * @param size  the size of the file (in bytes)
    * @return the generated [[SongData]] object
    */
  private def songData(index: Int, size: Long): SongData =
    val fileID = mediaFileID(index)
    val metadata = MediaMetadata(
      title = Some(s"Title $index"),
      artist = Some(s"Artist $index"),
      album = Some(s"Album $index"),
      inceptionYear = Some(2000 + index),
      trackNumber = Some(index),
      duration = Some(index * 60),
      size = size,
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

  /**
    * Creates synthetic content for a media file with the given size. Tests can
    * use this content to define a media file with a specific size.
    *
    * @param size the size of the content (in bytes)
    * @return the generated content
    */
  private def testContent(size: Int): String = "0123456789" * (size / 10) + "0" * (size % 10)

  /**
    * Returns the index of the test media file which is referenced by the given
    * song. The ID of a test media file is generated from its index.
    *
    * @param song the song to check
    * @return the index of the referenced media file
    */
  private def mediaFileIndex(song: SongData): Int = song.fileID.stripPrefix(FileIDPrefix).toInt

  /** A pattern for the names of the files generated by an export operation. */
  private val ExportedFilePattern = """\d+ - Title \d+\.mp3""".r

  /**
    * Computes the size of the content which is served when the media file
    * referenced by the given song is downloaded.
    *
    * @param song the song to check
    * @return the size of the content (in bytes)
    */
  private def contentSize(song: SongData): Long = mediaFileContent(mediaFileIndex(song)).length.toLong

  /**
    * Computes the total size of the given playlist. The size of a song is
    * taken from the metadata of the associated media file. Songs for which no
    * metadata is available contribute a size of 0.
    *
    * @param songs the songs of the playlist
    * @return the total size of all songs (in bytes)
    */
  private def totalSize(songs: Seq[SongData]): Long =
    songs.map(_.optFileInfo.map(_.metadata.size).getOrElse(0L)).sum

  /**
    * Generates the name of the file which the export operation is expected to
    * create for the given song. The numeric prefix of the name is padded to
    * the given number of digits. Songs without metadata are named by this
    * prefix and the file extension only.
    *
    * @param song   the song to be exported
    * @param index  the index of the song
    * @param digits the number of digits in the prefix of the file name
    * @return the name of the file to be created
    */
  private def exportedFileName(song: SongData, index: Int, digits: Int): String =
    val indexString = index.toString
    val prefix =
      if indexString.length >= digits then indexString
      else "0" * (digits - indexString.length) + indexString
    song.optFileInfo match
      case Some(_) => s"$prefix - ${song.getTitle}.mp3"
      case None => s"$prefix.mp3"

  /**
    * Generates all notifications that are expected to be published for the
    * given song. Notifications are expected for each chunk of data that has
    * been copied, plus a final one which reports that the copy operation has
    * been completed.
    *
    * @param song           the song to be exported
    * @param fileName       the name of the file the song is copied to
    * @param operationIndex the index of the copy operation
    * @param totalOps       the total number of operations
    * @param totalSize      the total size of the export process
    * @return a list with the notifications for this song
    */
  private def progressNotifications(song: SongData,
                                    fileName: String,
                                    operationIndex: Int,
                                    totalOps: Int,
                                    totalSize: Long): List[PlaylistExporter.ProgressNotification] =
    val currentSize = song.optFileInfo.map(_.metadata.size).getOrElse(0L)
    val copyNotification = (progressedSize: Long) =>
      PlaylistExporter.ProgressNotification.Copy(totalOps, operationIndex, totalSize, currentSize, progressedSize,
        fileName)
    val chunks = (1L to currentSize / ProgressChunkSize)
      .map(_ * ProgressChunkSize)
      .filter(_ < currentSize)
    (chunks.map(copyNotification) :+ copyNotification(currentSize)).toList

  /**
    * Generates the notifications that are expected to be published for a song
    * whose metadata is not available. As the size of the file is not known
    * upfront, progress can only be reported once the file has been copied
    * completely. Thus, a single notification is expected. It reports the size
    * of the file that has been copied.
    *
    * @param song           the song to be exported
    * @param fileName       the name of the file the song is copied to
    * @param operationIndex the index of the copy operation
    * @param totalOps       the total number of operations
    * @param totalSize      the total size of the export process
    * @return a list with the notification for this file
    */
  private def progressNotificationsWithoutMetadata(song: SongData,
                                                   fileName: String,
                                                   operationIndex: Int,
                                                   totalOps: Int,
                                                   totalSize: Long): List[PlaylistExporter.ProgressNotification] =
    val currentSize = contentSize(song)
    List(PlaylistExporter.ProgressNotification.Copy(totalOps, operationIndex, totalSize, currentSize, currentSize,
      fileName))

  /**
    * Generates progress notifications for copy operations for all songs in the
    * given playlist.
    *
    * @param playlist the playlist
    * @return a list with the expected notifications for this playlist
    */
  private def progressNotifications(playlist: Seq[SongData]): List[PlaylistExporter.ProgressNotification] =
    progressNotifications(playlist, playlist.indices)

  /**
    * Generates all notifications that are expected to be published for a
    * subset of the songs in the given playlist. Tests using this function
    * operate on small playlists. Therefore, the number of digits in the file
    * names can be hardcoded to 1. Note that songs are numbered starting with 1,
    * as this is the way they are numbered by the export operation.
    *
    * Songs can be filtered out before they are copied, for instance, because a
    * file with the same name already exists on the target medium. The names of
    * the files to be created are still derived from the positions of the songs
    * in the playlist, while the copy operations are numbered consecutively.
    *
    * @param playlist the list of songs in the playlist
    * @param copied   the positions of the copied songs in this playlist
    * @return a list with the expected notifications for this playlist
    */
  private def progressNotifications(playlist: Seq[SongData],
                                    copied: Seq[Int]): List[PlaylistExporter.ProgressNotification] =
    val digits = 1
    val totalOps = copied.size
    val exportSize = totalSize(copied.map(playlist))
    copied.zipWithIndex.flatMap: (position, operation) =>
      val song = playlist(position)
      val fileName = exportedFileName(song, position + 1, digits)
      if song.optFileInfo.isEmpty then
        progressNotificationsWithoutMetadata(song, fileName, operation + 1, totalOps, exportSize)
      else progressNotifications(song, fileName, operation + 1, totalOps, exportSize)
    .toList
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

  it should "export a song without metadata using the minimum file name" in :
    val Count = 3
    val NoMetadataIndex = 3
    val songs = (1 to Count).map(songData)
      .updated(NoMetadataIndex - 1, songData(NoMetadataIndex).copy(optFileInfo = None))
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, Count)
      .triggerExport(data)
      .waitForExportSuccess()

    val song = songs(NoMetadataIndex - 1)
    val expPath = testDirectory.resolve(exportedFileName(song, NoMetadataIndex, digits = 1))
    Files.exists(expPath) should be(true)
    Files.readAllBytes(expPath) should be(FileTestHelper.toBytes(mediaFileContent(NoMetadataIndex)))

  it should "not override existing files if the override flag is not set" in :
    val Count = 3
    val ExistingIndex = 2
    val existingContent = FileTestHelper.toBytes("This file must not be overridden!")
    val songs = (1 to Count).map(songData)
    val existingPath = testDirectory.resolve(exportedFileName(songs(ExistingIndex - 1), ExistingIndex, digits = 1))
    Files.write(existingPath, existingContent)
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, Count)
      .triggerExport(data)
      .waitForExportSuccess()

    Files.readAllBytes(existingPath) should be(existingContent)
    // the remaining files have been exported as usual
    checkExportedFiles(1, ExistingIndex - 1, digits = 1)
    checkExportedFiles(ExistingIndex + 1, Count, digits = 1)

  it should "override existing files if the override flag is set" in :
    val Count = 3
    val ExistingIndex = 2
    val songs = (1 to Count).map(songData)
    val existingPath = testDirectory.resolve(exportedFileName(songs(ExistingIndex - 1), ExistingIndex, digits = 1))
    Files.write(existingPath, FileTestHelper.toBytes("This file is to be overridden!"))
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = true)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, Count)
      .triggerExport(data)
      .waitForExportSuccess()

    Files.readAllBytes(existingPath) should be(FileTestHelper.toBytes(mediaFileContent(ExistingIndex)))
    checkExportedFiles(1, Count, digits = 1)

  it should "clear the target directory if the flag is set" in :
    val Count = 3
    val songs = (1 to Count).map(songData)
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = true, overrideFiles = false)
    val helper = new ExporterTestHelper
    val matchingFile = testDirectory.resolve(exportedFileName(songs.head, 1, digits = 1))
    Files.write(matchingFile, FileTestHelper.toBytes("This file is to be deleted!"))
    val obsoletePaths = List(
      writeFileContent(testDirectory.resolve("OtherFile.txt"), "Other file content."),
      writeFileContent(testDirectory.resolve("Folder/FolderFile.txt"), "Folder file content."),
      writeFileContent(testDirectory.resolve("Folder/SubFolder/NestedFile.txt"), "Nested file content."),
      testDirectory.resolve("Folder"),
      testDirectory.resolve("Folder/SubFolder")
    )

    helper.expectDownloadRequests(1, Count)
      .triggerExport(data)
      .waitForExportSuccess()

    checkExportedFiles(1, Count, digits = 1)
    obsoletePaths.filter(Files.exists(_)) shouldBe empty
    Files.exists(testDirectory) should be(true)

  it should "not clear the target directory if the flag is not set" in :
    val Count = 2
    val songs = (1 to Count).map(songData)
    val otherContent = FileTestHelper.toBytes("This file must not be deleted!")
    val otherFile = testDirectory.resolve("OtherFile.txt")
    Files.write(otherFile, otherContent)
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = true)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, Count)
      .triggerExport(data)
      .waitForExportSuccess()

    Files.readAllBytes(otherFile) should be(otherContent)
    checkExportedFiles(1, Count, digits = 1)

  it should "publish notifications for deleted elements" in :
    val Count = 2
    val songs = (1 to Count).map(songData)
    // writing the nested files also creates the folders containing them
    val expFileNames = List(
      exportedFileName(songs.head, 1, digits = 1),
      "OtherFile.txt",
      "Folder/FolderFile.txt",
      "Folder/SubFolder/NestedFile.txt"
    )
    expFileNames.foreach: name =>
      writeFileContent(testDirectory.resolve(name), "Some content.")
    val expNames = expFileNames ++ List("Folder/SubFolder", "Folder")
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = true, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, Count)
      .triggerExport(data)
    val notifications = helper.gatherDeleteNotifications()

    // the elements may be deleted in an arbitrary order, so only the set of
    // names and the set of operation indices can be checked
    val expTotalOps = expNames.size
    notifications.map(_.totalOperations) should contain theSameElementsAs
      List.fill(expTotalOps)(expTotalOps)
    notifications.map(_.currentOperation) should contain theSameElementsAs (1 to expTotalOps)
    notifications.map(_.elementName) should contain theSameElementsAs expNames

  it should "publish progress notifications only for the files that are not filtered out" in :
    val TotalOps = 3
    val ExistingIndex = 2
    val existingContent = FileTestHelper.toBytes("This file must not be overridden!")
    val songs = (1 to TotalOps).map(songData)
    val existingPath = testDirectory.resolve(exportedFileName(songs(ExistingIndex - 1), ExistingIndex, digits = 1))
    Files.write(existingPath, existingContent)
    val expectedNotifications = progressNotifications(songs, copied = Seq(0, 2))
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, TotalOps)
      .triggerExport(data)

    helper.gatherProgressNotifications() should contain theSameElementsAs expectedNotifications
    Files.readAllBytes(existingPath) should be(existingContent)

  it should "publish progress notifications for copy operations" in :
    val TotalOps = 3
    val songs = (1 to TotalOps).map(songData)
    val expectedNotifications = progressNotifications(songs)

    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, TotalOps)
      .triggerExport(data)

    helper.gatherProgressNotifications() should contain theSameElementsAs expectedNotifications

  it should "publish only a single notification for a song without metadata" in :
    val TotalOps = 3
    val NoMetadataIndex = 3
    val songs = (1 to TotalOps).map(songData)
      .updated(NoMetadataIndex - 1, songData(NoMetadataIndex).copy(optFileInfo = None))
    val expectedNotifications = progressNotifications(songs)

    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequests(1, TotalOps)
      .triggerExport(data)

    helper.gatherProgressNotifications() should contain theSameElementsAs expectedNotifications

  it should "not repeat the final notification if the file size is a multiple of the progress size" in :
    val FileSize = ProgressChunkSize * 3
    val song = songData(1, FileSize)
    val expectedNotifications = progressNotifications(List(song))

    val data = PlaylistExporter.ExportData(List(song), testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    helper.expectDownloadRequest(1, testContent(FileSize))
      .triggerExport(data)

    helper.gatherProgressNotifications() should contain theSameElementsAs expectedNotifications

  it should "allow to interrupt a running export operation" in :
    val Count = 25
    val BlockedIndex = Count
    val NotificationsBeforeKill = 5
    val songs = (1 to Count).map(songData)
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper
    val blockedRequest = Promise[HttpResponse]()
    helper.expectDownloadRequests(1, Count)
      // the download of the last song never finishes; this keeps the export running
      .expectDownloadRequest(BlockedIndex, blockedRequest.future)
    val killSwitch = helper.triggerExport(data).exportKillSwitch
    // let the export make some progress before interrupting it
    (1 to NotificationsBeforeKill).foreach: _ =>
      helper.nextMessage[PlaylistExporter.ProgressNotification]

    killSwitch.shutdown()

    helper.waitForExportEnd() should be(PlaylistExporter.ExportResult.Success)
    countExportedFiles() should be < Count

  it should "interrupt the removal of elements on the target directory" in :
    val FileCount = 100
    val obsoleteFiles = (1 to FileCount).map: index =>
      testDirectory.resolve(s"ObsoleteFile$index.txt")
    obsoleteFiles.foreach(writeFileContent(_, "Some content."))
    val data = PlaylistExporter.ExportData(
      List(songData(1)),
      testDirectory,
      clearTarget = true,
      overrideFiles = false
    )
    val helper = new ExporterTestHelper

    val killSwitch = helper.expectDownloadRequest(1).triggerExport(data).exportKillSwitch
    killSwitch.shutdown()

    helper.waitForExportEnd() should be(PlaylistExporter.ExportResult.Success)
    obsoleteFiles.count(Files.exists(_)) should be > 0

  it should "abort the export operation when an error occurs" in :
    val Count = 8
    val ErrorIndex = 4
    val exception = new IllegalStateException("Test exception: Could not download media file.")
    val songs = (1 to Count).map(songData)
    val data = PlaylistExporter.ExportData(songs, testDirectory, clearTarget = false, overrideFiles = false)
    val helper = new ExporterTestHelper

    val exportResult = helper.expectDownloadRequests(1, Count)
      .expectDownloadRequest(ErrorIndex, Future.failed(exception))
      .triggerExport(data)
      .waitForExportEnd()

    exportResult should be(PlaylistExporter.ExportResult.Failure(exception))
    checkExportedFiles(1, ErrorIndex - 1, digits = 1)
    forEvery((ErrorIndex to Count).toList): index =>
      val expPath = testDirectory.resolve(exportedFileName(songData(index), index, 1))
      Files.exists(expPath) shouldBe false

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
      val expPath = testDirectory.resolve(exportedFileName(songData(index), index, digits))
      Files.exists(expPath) should be(true)
      Files.readAllBytes(expPath) should be(FileTestHelper.toBytes(mediaFileContent(index)))

  /**
    * Counts the files in the test directory that were generated by an export
    * operation.
    *
    * @return the number of exported files
    */
  private def countExportedFiles(): Int =
    Using.resource(Files.list(testDirectory)): stream =>
      stream.filter(p => ExportedFilePattern.matches(p.getFileName.toString)).count().toInt

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
      * A buffer for the progress notifications that have been received on the
      * message bus. Notifications are collected while waiting for the end of
      * the export operation.
      */
    private val receivedProgress = ListBuffer.empty[PlaylistExporter.ProgressNotification]

    /** The kill switch of the export operation that was started last. */
    private var killSwitch: KillSwitch = uninitialized

    /**
      * Prepares the mock for the [[ArchiveService]] to expect a request to 
      * download the media file with an ID derived from the given index. The
      * request is answered with the content of the test media file based on
      * this index.
      *
      * @param index the index of the media file
      * @return this test helper
      */
    def expectDownloadRequest(index: Int): ExporterTestHelper = expectDownloadRequest(index, mediaFileContent(index))

    /**
      * Prepares the mock for the [[ArchiveService]] to expect a request to
      * download the media file with an ID derived from the given index. The
      * request is answered with the given content instead of the content of
      * the test media file for this index. Tests can use this to define a
      * media file with a specific size.
      *
      * @param index   the index of the media file
      * @param content the content the download request is answered with
      * @return this test helper
      */
    def expectDownloadRequest(index: Int, content: String): ExporterTestHelper =
      val entity = HttpEntity(ContentTypes.`application/octet-stream`,
        Source.fromIterator(() => ByteString(content).grouped(DownloadChunkSize)))
      val result = Future.successful(HttpResponse(status = StatusCodes.OK, entity = entity))
      expectDownloadRequest(index, result)

    /**
      * Prepares the mock for the [[ArchiveService]] to expect a request to
      * download the media file with an ID derived from the given index and
      * allows specifying the resulting [[Future]]
      *
      * @param index  the index of the media file
      * @param result the [[Future]] with the result of the operation
      * @return this test helper
      */
    def expectDownloadRequest(index: Int, result: Future[HttpResponse]): ExporterTestHelper =
      val fileID = mediaFileID(index)
      val request = HttpRequest(uri = s"$DownloadUriPrefix$fileID$DownloadUriSuffix")
      when(archiveService.sendRequest(request)).thenReturn(result)
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
      * Listens on the message bus until an export result message is received.
      * Progress notifications are recorded so they can be inspected by tests
      * later on. This message is then returned.
      *
      * @return the result message that was received
      */
    @tailrec final def waitForExportEnd(): PlaylistExporter.ExportResult =
      nextMessage[PlaylistExporter.ProgressNotification | PlaylistExporter.ExportResult] match
        case n: PlaylistExporter.ProgressNotification =>
          receivedProgress += n
          waitForExportEnd()
        case r: PlaylistExporter.ExportResult => r

    /**
      * Listens on the message bus until a success result for the export 
      * operation is received. Progress messages are recorded.
      *
      * @return this test helper
      */
    def waitForExportSuccess(): ExporterTestHelper =
      waitForExportEnd() should be(PlaylistExporter.ExportResult.Success)
      this

    /**
      * Listens on the message bus until the export operation has ended and
      * returns all progress notifications that have been received meanwhile.
      *
      * @return the notifications in the order of their reception
      */
    def gatherProgressNotifications(): List[PlaylistExporter.ProgressNotification] =
      waitForExportEnd()
      receivedProgress.toList

    /**
      * Listens on the message bus until the export operation has ended and
      * returns the notifications about delete operations that have been
      * received meanwhile.
      *
      * @return the delete notifications in the order of their reception
      */
    def gatherDeleteNotifications(): List[PlaylistExporter.ProgressNotification.Delete] =
      val notifications = gatherProgressNotifications()
      notifications.collect:
        case d: PlaylistExporter.ProgressNotification.Delete => d

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
      killSwitch = exporter.run()
      this

    /**
      * Returns the kill switch of the export operation that was started last.
      * It can be used to abort this operation.
      *
      * @return the kill switch for the current export operation
      */
    def exportKillSwitch: KillSwitch = killSwitch
