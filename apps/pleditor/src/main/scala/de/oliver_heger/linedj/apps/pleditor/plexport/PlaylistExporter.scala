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

import de.oliver_heger.linedj.apps.pleditor.SongData
import de.oliver_heger.linedj.apps.pleditor.plexport.PlaylistExporter.ExportData
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.stream.{Materializer, SystemMaterializer}

import java.nio.file.Path
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object PlaylistExporter:
  /**
    * An enumeration defining the possible outcomes of an export operation. At
    * the end of an export, [[PlaylistExporter]] publishes a message of this
    * type on the system message bus. Thus, interested parties can find out
    * whether the action was successful or not.
    */
  enum ExportResult:
    /**
      * A success result. The export operation was successful.
      */
    case Success

    /**
      * A failure result. The export operation was aborted since an error
      * occurred. Further information is contained in the object.
      *
      * @param exception the exception causing the operation to fail
      */
    case Failure(exception: Throwable)
  end ExportResult

  /**
    * An enumeration defining progress notifications published by
    * [[PlaylistExporter]] on the system message bus while executing specific
    * operations. By following these notifications, consumers can keep track on
    * the progress of the export operation.
    */
  enum ProgressNotification:
    /**
      * Notification about a Copy operation. A media file referenced in the
      * playlist is downloaded from the archive server and copied to the target
      * directory.
      *
      * @param totalOperations  the total number of copy operations
      * @param currentOperation the index of the current operation
      * @param totalSize        the total size to be copied (in bytes)
      * @param currentSize      the size for this operation (in bytes)
      * @param progressedSize   the number of bytes copied for this operation
      * @param fileName         the name of the file which is currently copied
      */
    case Copy(totalOperations: Int,
              currentOperation: Int,
              totalSize: Long,
              currentSize: Long,
              progressedSize: Long,
              fileName: String)
  end ProgressNotification

  /**
    * A data class that defines a full export operation.
    *
    * An instance contains all the files to be exported plus some additional
    * settings which impact the operation. For instance, it can be specified
    * whether the target medium is to be cleaned before the export or whether
    * files existing on the medium are to be overridden.
    *
    * @param songs         the songs to be exported
    * @param exportPath    the path where songs are exported
    * @param clearTarget   a flag whether the target medium is to be cleaned
    * @param overrideFiles a flag whether files on the target medium are to be
    *                      overridden if they exist
    */
  final case class ExportData(songs: Seq[SongData],
                              exportPath: Path,
                              clearTarget: Boolean,
                              overrideFiles: Boolean)

  /** An expression defining illegal characters in a song title. */
  private val InvalidCharacters = "[:*?\"<>\t|/\\\\]".r

  /** The prefix of the URI for downloading files from the archive. */
  private val ArchiveDownloadURIPrefix = "/api/archive/files/"

  /** The suffix of the URI for downloading files from the archive. */
  private val ArchiveDownloadURISuffix = "/download"

  /** The parallelism level for exporting single files. */
  private val ExportParallelism = 3

  /**
    * The file extension to be used for a song whose file information is not
    * available. In this case, the extension cannot be derived from the file
    * URI, so a default is used.
    */
  private val DefaultFileExtension = ".mp3"

  /**
    * Generates a sequence with the paths for storing the songs to be copied.
    *
    * The number of digits used for the numeric prefix of a file name is
    * derived dynamically from the number of songs. The names are generated by
    * [[generateTargetFileName]].
    *
    * @param exportPath the export path
    * @param songs      the list of songs to be copied
    * @return a list with the corresponding target paths
    */
  private def generateTargetPaths(exportPath: Path, songs: Seq[SongData]): Seq[Path] =
    val digits = songs.length.toString.length
    songs.zipWithIndex.map(s => exportPath.resolve(generateTargetFileName(s, digits)))

  /**
    * Generates the name of a song file on the target medium.
    *
    * If the metadata of a song is not available, then the name of the file
    * consists only of the numeric prefix and the default file extension.
    *
    * @param s      the data of the affected song and its index
    * @param digits the number of digits for the song index
    * @return the song name
    */
  private def generateTargetFileName(s: (SongData, Int), digits: Int): String =
    val prefix = formatIndex(s._2, digits)
    s._1.optFileInfo match
      case Some(fileInfo) =>
        val uri = fileInfo.fileUri.uri
        s"$prefix - ${validTitle(s._1.getTitle)}${extractExtension(uri)}"
      case None => s"$prefix$DefaultFileExtension"

  /**
    * Generates a song title that contains only valid characters to be used in
    * file names. All invalid characters are replaced by a '_' character.
    *
    * @param title the song title
    * @return the validated title
    */
  private def validTitle(title: String): String = InvalidCharacters.replaceAllIn(title, "_")

  /**
    * Extracts the file extension from the given URI.
    *
    * @param uri the URI
    * @return the extension
    */
  private def extractExtension(uri: String): String =
    val dot = uri.lastIndexOf('.')
    if dot > 0 then uri substring dot
    else ""

  /**
    * Generates the index of a song in the exported list with the given number
    * of digits. The number of digits is calculated based on the number of
    * songs.
    *
    * @param index  the index
    * @param digits the number of digits
    * @return the formatted song index
    */
  private def formatIndex(index: Int, digits: Int): String =
    val sIdx = (index + 1).toString
    if sIdx.length >= digits then sIdx
    else "".padTo(digits - sIdx.length, '0') + sIdx

  /**
    * Returns the size of the media file which is referenced by the given song.
    * The size is read from the metadata of the file. If this metadata is not
    * available, then 0 is returned.
    *
    * @param song the song to check
    * @return the size of the media file (in bytes)
    */
  private def songSize(song: SongData): Long =
    song.optFileInfo.map(_.metadata.size).getOrElse(0L)
end PlaylistExporter

/**
  * A helper class that performs a playlist export operation.
  *
  * The class is passed all relevant information via constructor injection.
  *
  * @param archiveService the service to access the archive
  * @param actorSystem    the current actor system
  * @param messageBus     the system message bus
  * @param config         the configuration for the export functionality
  * @param exportData     the parameters for the current export
  */
class PlaylistExporter(archiveService: ArchiveService,
                       actorSystem: ActorSystem,
                       messageBus: MessageBus,
                       config: PlaylistExportConfig,
                       exportData: ExportData):

  import PlaylistExporter.*

  /** The [[Materializer]] for running the streams during an export. */
  given Materializer = SystemMaterializer(actorSystem).materializer

  /** The execution context for the stream and future interactions. */
  given ExecutionContext = actorSystem.dispatcher

  /**
    * Runs the configured export operation asynchronously. Information about
    * the progress is published on the system message bus. When the export is
    * done, the function publishes a corresponding [[ExportResult]] on the 
    * message bus.
    */
  def run(): Unit =
    val songs = exportData.songs
    val targetPaths = PlaylistExporter.generateTargetPaths(exportData.exportPath, songs)
    val totalSize = songs.map(songSize).sum
    Source(songs.zipWithIndex).zip(Source(targetPaths))
      .mapAsync(ExportParallelism):
        case ((song, index), path) => exportFile(index + 1, totalSize, song, path)
      .runWith(Sink.ignore)
      .onComplete:
        case Success(_) => messageBus.publish(PlaylistExporter.ExportResult.Success)
        case Failure(exception) => messageBus.publish(PlaylistExporter.ExportResult.Failure(exception))

  /**
    * Exports a single file from the playlist. The function queries the file's
    * content from the [[ArchiveService]] and writes it to the specified target
    * path. While the file is copied, progress notifications are published on
    * the message bus.
    *
    * @param operationIndex the index of this operation (starting with 1)
    * @param totalSize      the total size of all files to be copied
    * @param song           the song to be exported
    * @param targetPath     the path where to store the file
    * @return a [[Future]] with the outcome of the operation
    */
  private def exportFile(operationIndex: Int,
                         totalSize: Long,
                         song: SongData,
                         targetPath: Path): Future[Unit] =
    val request = HttpRequest(uri = s"$ArchiveDownloadURIPrefix${song.fileID}$ArchiveDownloadURISuffix")
    val progressSize = config.progressSize
    val currentSize = songSize(song)
    val fileName = targetPath.getFileName.toString
    val progressStage = new CopyProgressNotificationStage(
      messageBus,
      exportData.songs.size,
      operationIndex,
      totalSize,
      currentSize,
      fileName,
      progressSize
    )

    for
      response <- archiveService.sendRequest(request)
      _ <- response.entity.dataBytes
        .via(progressStage)
        .runWith(FileIO.toPath(targetPath))
    yield ()
