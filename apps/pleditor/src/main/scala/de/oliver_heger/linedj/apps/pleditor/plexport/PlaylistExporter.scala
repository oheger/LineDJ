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

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.utils.Walk
import de.oliver_heger.linedj.apps.pleditor.SongData
import de.oliver_heger.linedj.apps.pleditor.plexport.PlaylistExporter.ExportData
import de.oliver_heger.linedj.io.LocalFsUtils
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.comm.MessageBus
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
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

    /**
      * Notification about a Delete operation. An element on the target
      * directory is removed because this directory is to be cleared before the
      * media files of the playlist are copied. The affected element is
      * identified by its relative path to the target directory.
      *
      * @param totalOperations  the total number of delete operations
      * @param currentOperation the index of the current operation
      * @param elementName      the name of the affected element
      */
    case Delete(totalOperations: Int,
                currentOperation: Int,
                elementName: String)
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
    * The parallelism level for removing files from the target directory. As
    * folders can only be removed after their content, the elements need to be
    * processed one after the other.
    */
  private val DeleteParallelism = 1

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

  /** The actor system in the typed form, as required by the local file system. */
  private given typed.ActorSystem[_] = actorSystem.toTyped

  /**
    * A local file system for accessing the target directory. It is used for
    * scanning the directory and for removing files.
    */
  private lazy val localFs = LocalFsUtils.createLocalFs(exportData.exportPath, actorSystem)

  /**
    * Runs the configured export operation asynchronously. Information about
    * the progress is published on the system message bus. When the export is
    * done, the function publishes a corresponding [[ExportResult]] on the 
    * message bus.
    */
  def run(): Unit =
    val songs = exportData.songs
    val targetPaths = PlaylistExporter.generateTargetPaths(exportData.exportPath, songs)
    val futExport =
      for
        (elementsToDelete, filesToPreserve) <- prepareTarget()
        _ <- deleteElements(elementsToDelete)
        _ <- exportSongs(songs, targetPaths, filesToPreserve)
      yield ()
    futExport.onComplete:
      case Success(_) => messageBus.publish(PlaylistExporter.ExportResult.Success)
      case Failure(exception) => messageBus.publish(PlaylistExporter.ExportResult.Failure(exception))

  /**
    * Prepares the target directory of the export operation. Based on the
    * options of the current export, this function determines the elements that
    * need to be removed from the target directory and the files that already
    * exist there and thus must not be overridden. The target directory is
    * scanned only if this is necessary, which is the case if it is to be
    * cleared or if existing files are not to be overridden.
    *
    * @return a [[Future]] with a tuple containing the elements to be deleted
    *         and the paths of the files that must not be overridden; both
    *         results are empty if the target directory did not need to be
    *         scanned
    */
  private def prepareTarget(): Future[(Seq[Model.Element[Path]], Set[Path])] =
    if exportData.clearTarget || !exportData.overrideFiles then
      scanTarget().map: content =>
        val elementsToDelete = if exportData.clearTarget then content else Seq.empty
        val filesToPreserve =
          if exportData.clearTarget || exportData.overrideFiles then Set.empty[Path]
          else content.collect :
            case f: Model.File[?] => f.id
          .toSet
        (elementsToDelete, filesToPreserve)
    else Future.successful((Seq.empty, Set.empty))

  /**
    * Scans the target directory of the export operation. The result contains
    * all files and folders below this directory. The elements are stored in
    * reverse order of the walk, so that a folder appears after its content.
    * This way the elements can be removed safely, see [[deleteElements]].
    *
    * @return a [[Future]] with the elements found in the target directory
    */
  private def scanTarget(): Future[List[Model.Element[Path]]] =
    val walkConfig = Walk.WalkConfig(localFs, null, exportData.exportPath)
    Walk.dfsSource(walkConfig)
      .runWith(Sink.fold[List[Model.Element[Path]], Model.Element[Path]](List.empty)((l, e) => e :: l))

  /**
    * Removes the given elements from the target directory. The elements are
    * processed in the order they were returned by [[scanTarget]]: first all
    * files, then all folders. Since the scan stores folders after their
    * content, a folder is always empty when it needs to be removed. For this
    * reason, the elements must be processed one after the other. Before an
    * element is removed, a corresponding [[ProgressNotification.Delete]] is
    * published on the message bus.
    *
    * @param elements the elements to be removed
    * @return a [[Future]] which is completed when all elements are removed
    */
  private def deleteElements(elements: Seq[Model.Element[Path]]): Future[Unit] =
    val (folders, files) = elements.partition(!_.isInstanceOf[Model.File[?]])
    val elementsToDelete = files ++ folders
    val totalOperations = elementsToDelete.size
    Source(elementsToDelete.zipWithIndex)
      .mapAsync(DeleteParallelism):
        (element, index) =>
          messageBus.publish(ProgressNotification.Delete(totalOperations, index + 1, elementName(element.id)))
          deleteElement(element)
      .runWith(Sink.ignore)
      .map(_ => ())

  /**
    * Returns the name of an element in the target directory of the export
    * operation. As only the name is relevant for consumers of the progress
    * notifications, the path is made relative to the target directory.
    *
    * @param path the path of the element in question
    * @return the name of the element relative to the target directory
    */
  private def elementName(path: Path): String =
    exportData.exportPath.relativize(path).toString

  /**
    * Removes a single element from the target directory. The function supports
    * both files and folders.
    *
    * @param element the element to be removed
    * @return a [[Future]] which is completed when the element is removed
    */
  private def deleteElement(element: Model.Element[Path]): Future[Unit] =
    element match
      case file: Model.File[?] => localFs.deleteFile(file.id).run(null)
      case folder: Model.Folder[?] => localFs.deleteFolder(folder.id).run(null)
      case _ => Future.successful(())

  /**
    * Copies the songs of the playlist to the target directory.
    *
    * @param songs           the songs to be exported
    * @param targetPaths     the target paths of these songs
    * @param filesToPreserve the paths of files that exist in the target
    *                        directory and must not be overridden
    * @return a [[Future]] which is completed when all songs have been copied
    */
  private def exportSongs(songs: Seq[SongData],
                          targetPaths: Seq[Path],
                          filesToPreserve: Set[Path]): Future[Unit] =
    val songsToExport = selectSongsToExport(songs, targetPaths, filesToPreserve)
    val totalOperations = songsToExport.size
    val totalSize = songsToExport.map(s => songSize(s._1)).sum
    Source(songsToExport)
      .zipWithIndex
      .mapAsync(ExportParallelism):
        case ((song, path), index) => exportFile(index.toInt + 1, totalOperations, totalSize, song, path)
      .runWith(Sink.ignore)
      .map(_ => ())

  /**
    * Determines the songs that need to be copied together with their target
    * paths. Songs whose target file is contained in the set of files to
    * preserve are filtered out. As this set is empty unless existing files
    * must not be overridden, all songs are copied in any other case.
    *
    * @param songs           the songs to be exported
    * @param targetPaths     the target paths of these songs
    * @param filesToPreserve the paths of files that must not be overridden
    * @return the songs to be copied and their target paths
    */
  private def selectSongsToExport(songs: Seq[SongData],
                                  targetPaths: Seq[Path],
                                  filesToPreserve: Set[Path]): Seq[(SongData, Path)] =
    songs.zip(targetPaths).filterNot(s => filesToPreserve.contains(s._2))

  /**
    * Exports a single file from the playlist. The function queries the file's
    * content from the [[ArchiveService]] and writes it to the specified target
    * path. While the file is copied, progress notifications are published on
    * the message bus.
    *
    * @param operationIndex the index of this operation (starting with 1)
    * @param totalOperations the total number of copy operations
    * @param totalSize      the total size of all files to be copied
    * @param song           the song to be exported
    * @param targetPath     the path where to store the file
    * @return a [[Future]] with the outcome of the operation
    */
  private def exportFile(operationIndex: Int,
                          totalOperations: Int,
                          totalSize: Long,
                          song: SongData,
                          targetPath: Path): Future[Unit] =
    val request = HttpRequest(uri = s"$ArchiveDownloadURIPrefix${song.fileID}$ArchiveDownloadURISuffix")
    val progressSize = config.progressSize
    val currentSize = songSize(song)
    val fileName = targetPath.getFileName.toString
    for
      response <- archiveService.sendRequest(request)
      _ <- response.entity.dataBytes
        .via(new CopyProgressNotificationStage(messageBus, totalOperations, operationIndex, totalSize,
          currentSize, fileName, progressSize))
        .runWith(FileIO.toPath(targetPath))
    yield ()
