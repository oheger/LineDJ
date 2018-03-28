/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import java.nio.file.{Files, Path, Paths}

import akka.actor.ActorLogging
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitch, KillSwitches}
import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport}
import de.oliver_heger.linedj.io.{DirectoryStreamSource, FileData}
import de.oliver_heger.linedj.shared.archive.media.MediumID

import scala.concurrent.Future

/**
  * Companion object.
  */
object MediaScannerActor {
  /** The file separator character. */
  private val FileSeparator = System.getProperty("file.separator")

  /** Constant for the extension for medium description files. */
  private val SettingsExtension = ".settings"

  /**
    * Determines the path prefix of a medium description file. This is the
    * directory which contains the description file as string.
    *
    * @param descFile the path to the description file
    * @return the prefix for this description file
    */
  private def descriptionPrefix(descFile: Path): String =
    descFile.getParent.toString

  /**
    * Checks whether the specified file is a medium settings file.
    *
    * @param file the file to be checked
    * @return a flag whether this is a settings file
    */
  private def isSettingsFile(file: FileData): Boolean =
    file.path.toString endsWith SettingsExtension

  /**
    * Creates a ''MediaScanResult'' object from the sequence of
    * ''FileData'' objects obtained during the scan operation.
    *
    * @param root        the root path
    * @param fileData    the files detected during the scan operation
    * @param archiveName the name of the archive component
    * @return the ''MediaScanResult''
    */
  private def createScanResult(root: Path, fileData: Seq[FileData], archiveName: String):
  MediaScanResult = {
    val (descriptions, files) = fileData partition isSettingsFile
    MediaScanResult(root, createResultMap(descriptions.map(p => Paths get p.path).toList,
      files.toList, root.toString, archiveName))
  }

  /**
    * Creates the map with result data for a scan operation. As the scanner only
    * returns a list with all media files and a list with all medium description
    * files, a transformation has to take place in order to create the map with
    * all results.
    *
    * @param mediumDescriptions the list with the medium description files
    * @param mediaFiles         the list with all files
    * @param mediumURI          the URI for the medium
    * @param archiveName the name of the media archive
    * @return the result map
    */
  private def createResultMap(mediumDescriptions: List[Path], mediaFiles: List[FileData],
                              mediumURI: String, archiveName: String):
  Map[MediumID, List[FileData]] = {
    val sortedDescriptions = mediumDescriptions sortWith (descriptionPrefix(_) >
      descriptionPrefix(_))
    val start = (mediaFiles, Map.empty[MediumID, List[FileData]])
    val end = sortedDescriptions.foldLeft(start) { (state, path) =>
      val partition = findFilesForDescription(path, state._1)
      (partition._2, state._2 +
        (MediumID.fromDescriptionPath(path, archiveName) -> partition._1))
    }

    if (end._1.isEmpty) end._2
    else {
      end._2 + (MediumID(mediumURI, None, archiveName) -> end._1)
    }
  }

  /**
    * Finds all files which belong to the given medium description file. All
    * such files are contained in the first list of the returned tuple. The
    * second list contains the remaining files.
    *
    * @param desc       the path to the description file
    * @param mediaFiles the list with all media files
    * @return a partition with the files for this description and the remaining
    *         files
    */
  private def findFilesForDescription(desc: Path, mediaFiles: List[FileData]): (List[FileData],
    List[FileData]) = {
    val prefix = descriptionPrefix(desc)
    val len = prefix.length
    mediaFiles partition (f => belongsToMedium(prefix, len, f.path))
  }

  /**
    * Checks whether the specified path belongs to a specific medium. The prefix
    * URI for this medium is specified. This method checks whether the path
    * starts with this prefix, but is a real sub directory. (Files in the same
    * directory in which the medium description file is located are not
    * considered to belong to this medium.)
    *
    * @param prefix    the prefix for the medium
    * @param prefixLen the length of the prefix
    * @param path      the path to be checked
    * @return a flag whether this path belongs to this medium
    */
  private def belongsToMedium(prefix: String, prefixLen: Int, path: String): Boolean =
    path.startsWith(prefix) && path.lastIndexOf(FileSeparator) > prefixLen

  /**
    * A message received by ''MediaScannerActor'' telling it to scan a
    * specific directory for media files. When the scan is done, an object of
    * type [[MediaScanResult]] is sent back.
    *
    * @param path  the path to be scanned
    * @param seqNo the sequence number for this request
    */
  case class ScanPath(path: Path, seqNo: Int)

  /**
    * A message sent by [[MediaScannerActor]] as result for a scan request.
    * The message contains a [[MediaScanResult]] with all the files that have
    * been found during the scan operation.
    *
    * @param request the original request
    * @param result  the actual scan result
    */
  case class ScanPathResult(request: ScanPath, result: MediaScanResult)

}

/**
  * An actor implementation which parses a directory structure for media
  * directories and files.
  *
  * This actor implementation uses a [[DirectoryStreamSource]] to scan a folder
  * structure. From all encountered files (that are accepted by the exclusion
  * filter) a [[MediaScanResult]] is generated and sent back to the caller.
  *
  * All ongoing scan operations can be canceled by sending the actor a
  * ''CancelStreams'' message. The actor does not send a response on this
  * message, but for all ongoing scan operations result messages are generated
  * (with the files encountered until the operation was canceled).
  *
  * @param archiveName the name of the media archive
  * @param exclusions the set of file extensions to exclude
  */
class MediaScannerActor(archiveName: String, exclusions: Set[String])
  extends AbstractStreamProcessingActor with ActorLogging with CancelableStreamSupport {

  import MediaScannerActor._

  override def customReceive: Receive = {
    case req: ScanPath =>
      handleScanRequest(req)
  }

  /**
    * Processes a scan request.
    *
    * @param req the request to be handled
    */
  private def handleScanRequest(req: ScanPath): Unit = {
    val source = createSource(req.path)
    val (ks, futStream) = runStream(source)
    processStreamResult(futStream map { s =>
      ScanPathResult(req, createScanResult(req.path, s, archiveName))
    }, ks) { f =>
      log.error(f.exception, "Ignoring media path " + req.path)
      ScanPathResult(req, MediaScanResult(req.path, Map.empty))
    }
    log.info("Started scan operation for " + req.path)
  }

  /**
    * Creates the source for traversing the specified root file structure.
    *
    * @param path the root of the file structure to be scanned
    * @return the source for scanning this structure
    */
  private[media] def createSource(path: Path): Source[FileData, Any] =
    DirectoryStreamSource.newBFSSource[FileData](path,
      filter = DirectoryStreamSource.excludeExtensionsFilter(exclusions) ||
        DirectoryStreamSource.AcceptSubdirectoriesFilter) { (p, d) =>
      FileData(p.toString, if (d) -1 else Files.size(p))
    }

  /**
    * Executes a stream with the provided source and returns a sequence of
    * ''FileData'' objects for all the files encountered and an object to
    * cancel the stream.
    *
    * @param source the source
    * @return a tuple with with a kill switch and the future result of stream
    *         processing
    */
  private[media] def runStream(source: Source[FileData, Any]):
  (KillSwitch, Future[Seq[FileData]]) = {
    val sink: Sink[FileData, Future[List[FileData]]] =
      Sink.fold(List.empty[FileData])((lst, e) => e :: lst)
    source.filterNot(_.size < 0)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(sink)(Keep.both)
      .run()
  }
}
