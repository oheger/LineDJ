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

package de.oliver_heger.linedj.archive.media

import de.oliver_heger.linedj.archive.media.MediumAggregateStage.FileDataFactory
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import scalaz.State

import java.nio.file.{Files, Path}
import scala.collection.immutable.Seq

object MediumAggregateStage:
  /** The name of the file with the description of a medium. */
  private val InfoFileName = "medium.json"

  /**
    * An internally used data class that stores the aggregated file information
    * for a single medium.
    *
    * @param path  the root path of this medium
    * @param id    the ID of the medium
    * @param files the aggregated files on this medium
    */
  private case class MediumAggregateData(path: Path, id: MediumID, files: List[FileData]):
    /**
      * Checks whether the specified file is potentially in scope of the
      * medium represented by this instance. A result of '''false''' means that
      * this medium is now complete.
      *
      * @param file the file in question
      * @return a flag whether this file is in scope of this medium
      */
    def isInScope(file: Path): Boolean = file startsWith path

    /**
      * Checks whether the specified file is owned by this medium. Provided
      * that ''isInScope()'' returned '''true''' for the file, with this method
      * it can be checked that the file actually belongs to this medium.
      *
      * @param file the file in question
      * @return a flag whether this file is owned by this medium
      */
    def owns(file: Path): Boolean = file.getParent != path

    /**
      * Adds the specified file to the list of files that belong to this
      * medium.
      *
      * @param file the file to be added
      * @return an updated instance that contains this file
      */
    def addFile(file: FileData): MediumAggregateData = copy(files = file :: files)

  /**
    * Type alias for the state managed by this stage. The state consists of
    * information about the media that are currently processed. Usually, there
    * will be only a single current medium and the artificial medium for songs
    * not assigned to a specific medium. But in case of nested media, there may
    * be more.
    */
  private type MediaState = List[MediumAggregateData]

  /**
    * Type alias for a function that converts ''Path'' objects to corresponding
    * ''FileData'' instances.
    */
  type FileDataFactory = Path => FileData

  /**
    * Generates a ''MediumID'' based on the path to its ''settings'' file. The
    * URI components of the ID are computed using the given converter, so that
    * they are relative to the root path of the archive.
    *
    * @param settingsPath the path to the settings file
    * @param archiveName  the name of the archive
    * @param converter    the ''PathUriConverter''
    * @return the ''MediumID'' for this settings file
    */
  def mediumIDFromSettingsPath(settingsPath: Path, archiveName: String, converter: PathUriConverter): MediumID =
    val mediumUri = converter.pathToUri(settingsPath.getParent).uri
    val settingsUri = converter.pathToUri(settingsPath).uri
    MediumID(mediumUri, Some(settingsUri), archiveName)

  /**
    * Converts the specified path to a ''FileData'' object.
    *
    * @param p the path to be converted
    * @return the resulting ''FileData'' object
    */
  private def toFileData(p: Path): FileData =
    val fileSize = Files size p
    FileData(p, fileSize)

  /**
    * Checks whether the specified path represents a medium description file.
    *
    * @param p the path to be checked
    * @return a flag whether this path is a medium description file
    */
  private def isInfoFile(p: Path): Boolean =
    p.getFileName.toString == InfoFileName

  /**
    * A function to calculate the next state of this stage after a path is
    * processed. Here the logic of aggregating files to media and determining
    * when a medium is complete is contained. In addition to the next state,
    * an optional ''MediaScanResult'' is returned that contains the data of all
    * media that are now complete.
    *
    * @param file            the current file to be processed
    * @param root            the root path of the current media archive
    * @param archiveName     the name of the media archive
    * @param converter       the ''PathUriConverter''
    * @param fileDataFactory the function to create ''FileData'' objects
    * @return the object to calculate the next state
    */
  private def nextState(file: Path, root: Path, archiveName: String, converter: PathUriConverter,
                        fileDataFactory: FileDataFactory): State[MediaState, Option[MediaScanResult]] =
    State { s =>
      if isInfoFile(file) then
        val mid: MediumID = mediumIDFromSettingsPath(file, archiveName, converter)
        val newAgg = MediumAggregateData(file.getParent, mid, Nil)
        val (completed, active) = s.span(!_.isInScope(file))
        (newAgg :: active, createScanResultForCompleteMedia(completed, root))
      else
        val (next, completed) = processFile(s, file, fileDataFactory)
        (next, createScanResultForCompleteMedia(completed, root))
    }

  /**
    * Processes a file from the iteration over the media archive, updates the
    * aggregation state accordingly, and determines the media that are now
    * complete.
    *
    * @param converter the function to create ''FileData'' objects
    * @param state     the current state of processing
    * @param file      the file to be processed
    * @return the updated state and a sequence of completed media
    */
  private def processFile(state: MediaState, file: Path, converter: FileDataFactory):
  (MediaState, List[MediumAggregateData]) =
    val medium = state.head
    if state.size == 1 then
      (List(medium addFile converter(file)), Nil)
    else if medium isInScope file then
      if medium owns file then
        (medium.addFile(converter(file)) :: state.tail, Nil)
      else
        val (tempState, complete) = processFile(state.tail, file, converter)
        (medium :: tempState, complete)
    else
      val (nextState, complete) = processFile(state.tail, file, converter)
      (nextState, medium :: complete)

  /**
    * Converts a sequence of completed media to an optional scan result. All
    * completed media are added to the map contained in the scan result object.
    * If there are no completed media, result is ''None''.
    *
    * @param media the sequence with completed media
    * @param root  the root path of the current media archive
    * @return an optional ''MediaScanResult''
    */
  private def createScanResultForCompleteMedia(media: Seq[MediumAggregateData], root: Path):
  Option[MediaScanResult] =
    if media.isEmpty then None
    else
      val fileMap = media.filter(_.files.nonEmpty)
        .foldLeft(Map.empty[MediumID, List[FileData]]) { (map, medium) =>
          map + (medium.id -> medium.files)
        }
      Some(MediaScanResult(root, fileMap))

/**
  * A specialized flow stage used during scanning of media files that
  * aggregates the files encountered to the media they belong to.
  *
  * This stage assumes that the file structure to be processed is traversed in
  * DFS order. It determines which file belongs to which medium and passes
  * [[MediaScanResult]] objects downstream as soon as a medium has been
  * processed completely. This makes it possible to pass data to the union
  * archive while the scan operation is ongoing.
  *
  * Note that if no files are encountered, the stage yields a single
  * ''MediaScanResult'' object with no content. That way there is always some
  * feedback about a scan operation.
  *
  * @param root            the root directory that is currently scanned
  * @param archiveName     the name of the archive (for generating medium IDs)
  * @param converter       the ''PathUriConverter''
  * @param fileDataFactory a function to convert a path to a ''FileData''
  *                        object
  */
private class MediumAggregateStage(val root: Path,
                                   val archiveName: String,
                                   val converter: PathUriConverter,
                                   val fileDataFactory: FileDataFactory = MediumAggregateStage.toFileData)
  extends GraphStage[FlowShape[Path, MediaScanResult]]:
  val in: Inlet[Path] = Inlet[Path]("MediumAggregateStage.in")
  val out: Outlet[MediaScanResult] = Outlet[MediaScanResult]("MediumAggregateStage.out")

  override val shape: FlowShape[Path, MediaScanResult] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):

      import MediumAggregateStage._

      var state: MediaState = List(MediumAggregateData(root, MediumID(root.toString, None, archiveName), Nil))

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val file = grab(in)
          val func = nextState(file, root, archiveName, converter, fileDataFactory)
          val (next, outcome) = func(state)
          state = next
          outcome match {
            case Some(result) =>
              push(out, result)
            case None =>
              pull(in)
          }
        }

        override def onUpstreamFinish(): Unit = {
          createScanResultForCompleteMedia(state, root) foreach (emit(out, _))
          complete(out)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
