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

import java.nio.file.Path
import java.util.Locale

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, KillSwitch, KillSwitches}
import akka.util.Timeout
import de.oliver_heger.linedj.io.DirectoryStreamSource
import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.Promise
import scala.concurrent.duration._

/**
  * Companion object.
  */
object MediaScannerActor {
  /** Constant for the extension for medium description files. */
  private val SettingsExtension = ".settings"

  /** The extension for settings files to be used in filter expressions. */
  private val SettingsExtFilter = "SETTINGS"

  /**
    * Returns a ''Props'' object to create an instance of this actor class.
    *
    * @param archiveName      the name of the media archive
    * @param exclusions       a set of file extensions to exclude
    * @param inclusions       a set of file extensions to include
    * @param maxBufSize       the size of internal buffers for aggregated results
    * @param mediumInfoParser the actor for parsing medium info files
    * @return a ''Props'' object to create an instance
    */
  def apply(archiveName: String, exclusions: Set[String], inclusions: Set[String],
            maxBufSize: Int, mediumInfoParser: ActorRef): Props =
    Props(classOf[MediaScannerActorImpl], archiveName, exclusions, inclusions, maxBufSize,
      mediumInfoParser)

  /**
    * The mapping function from a stream failure to a corresponding message.
    *
    * @param ex the exception that occurred during stream processing
    * @return the corresponding error message
    */
  private def mapException(ex: Throwable): Any = ScanSinkActor.StreamFailure(ex)

  /**
    * Checks whether the specified path is a medium settings file.
    *
    * @param file the file to be checked
    * @return a flag whether this is a settings file
    */
  private def isSettingsFile(file: Path): Boolean =
    file.toString endsWith SettingsExtension

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
    * A message sent by ''MediaScannerActor'' after the completion of a scan
    * operation. When this message arrives the caller can be sure that no more
    * scan results will come in.
    *
    * @param request the original ''ScanPath'' request
    */
  case class PathScanCompleted(request: ScanPath)

  /**
    * A message sent by [[MediaScannerActor]] as result for a scan request.
    * The message contains a [[MediaScanResult]] with all the files that have
    * been found during the scan operation.
    *
    * @param request the original request
    * @param result  the actual scan result
    */
  case class ScanPathResult(request: ScanPath, result: MediaScanResult)

  private class MediaScannerActorImpl(archiveName: String, exclusions: Set[String],
                                      inclusions: Set[String], maxBufSize: Int,
                                      mediumInfoParser: ActorRef)
    extends MediaScannerActor(archiveName, exclusions, inclusions, maxBufSize,
      mediumInfoParser) with ChildActorFactory

}

/**
  * An actor implementation which parses a directory structure for media
  * directories and files.
  *
  * This actor implementation uses a [[DirectoryStreamSource]] to scan a folder
  * structure. The files encountered in this structure are aggregated to
  * [[EnhancedMediaScanResult]] objects. Also, medium description files are
  * parsed. From this information combined result objects are created and
  * passed to the calling actor.
  *
  * All ongoing scan operations can be canceled by sending the actor a
  * ''CancelStreams'' message. The actor does not send a response on this
  * message, but for all ongoing scan operations result messages are generated
  * (with the files encountered until the operation was canceled).
  *
  * @param archiveName      the name of the media archive
  * @param exclusions       a set of file extensions to exclude
  * @param inclusions       a set of file extensions to include
  * @param maxBufSize       the size of internal buffers for aggregated results
  * @param mediumInfoParser the actor for parsing medium info files
  */
class MediaScannerActor(archiveName: String, exclusions: Set[String], inclusions: Set[String],
                        maxBufSize: Int, mediumInfoParser: ActorRef)
  extends AbstractStreamProcessingActor with ActorLogging with CancelableStreamSupport {
  this: ChildActorFactory =>

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
    val promiseDone = Promise[Unit]()
    val sinkActor = createChildActor(Props(classOf[ScanSinkActor], sender(), promiseDone,
      maxBufSize, req.seqNo))
    val source = createSource(req.path)
    val ks = runStream(source, req.path, sinkActor)
    processStreamResult(promiseDone.future map { _ =>
      PathScanCompleted(req)
    }, ks) { f =>
      log.error(f.exception, "Error when scanning media path " + req.path)
      PathScanCompleted(req)
    }
    log.info("Started scan operation for {}.", req.path)
  }

  /**
    * Creates the source for traversing the specified root file structure.
    *
    * @param path the root of the file structure to be scanned
    * @return the source for scanning this structure
    */
  private[media] def createSource(path: Path): Source[Path, Any] =
    DirectoryStreamSource.newDFSSource[(Path, Boolean)](path,
      filter = createFilter()) { (p, d) =>
      (p, d)
    }.filterNot(_._2)
      .map(_._1)

  /**
    * Executes a stream with the provided source and returns a sequence of
    * ''FileData'' objects for all the files encountered and an object to
    * cancel the stream.
    *
    * @param source    the source
    * @param root      the root path to be scanned
    * @param sinkActor the actor serving as sink
    * @return a tuple with with a kill switch and the future result of stream
    *         processing
    */
  private[media] def runStream(source: Source[Path, Any], root: Path, sinkActor: ActorRef):
  KillSwitch = {
    implicit val infoParseTimeout: Timeout = Timeout(1.minute)
    val sinkScanResults = Sink.actorRefWithAck(sinkActor, ScanSinkActor.Init,
      ScanSinkActor.Ack, ScanSinkActor.ScanResultsComplete, mapException)
    val sinkInfo = Sink.actorRefWithAck(sinkActor, ScanSinkActor.Init,
      ScanSinkActor.Ack, ScanSinkActor.MediaInfoComplete, mapException)
    val ks = KillSwitches.single[Path]
    val g = RunnableGraph.fromGraph(GraphDSL.create(ks) { implicit builder =>
      ks =>
        import GraphDSL.Implicits._
        val broadcast = builder.add(Broadcast[Path](2))
        val aggregate = new MediumAggregateStage(root, archiveName)
        val enhance = Flow[MediaScanResult].map(ScanResultEnhancer.enhance)
        val filterSettings = Flow[Path].filter(isSettingsFile)
        val parseRequest = Flow[Path].map(parseMediumInfoRequest)
        val parseInfo = Flow[MediumInfoParserActor.ParseMediumInfo].mapAsync(1) {
          r =>
            (mediumInfoParser ? r).mapTo[MediumInfoParserActor.ParseMediumInfoResult]
              .map(_.info)
        }
        source ~> ks ~> broadcast.in
        broadcast ~> aggregate ~> enhance ~> sinkScanResults
        broadcast ~> filterSettings ~> parseRequest ~> parseInfo ~> sinkInfo
        ClosedShape
    })
    g.run()
  }

  /**
    * Generates a request to parse a medium description file based on the path
    * to this file.
    *
    * @param p the path to the settings file
    * @return the request for the medium info parser actor
    */
  private def parseMediumInfoRequest(p: Path): MediumInfoParserActor.ParseMediumInfo =
    MediumInfoParserActor.ParseMediumInfo(p, MediumID.fromDescriptionPath(p, archiveName), 0)

  /**
    * Creates the filter for the directory stream source based on the provided
    * sets for inclusions and exclusions. If both are defined, inclusions take
    * precedence.
    *
    * @return the filter for the directory source
    */
  private def createFilter(): DirectoryStreamSource.PathFilter = {
    val extFilter = if (inclusions.nonEmpty)
      DirectoryStreamSource.includeExtensionsFilter(inclusions +
        SettingsExtFilter.toUpperCase(Locale.ROOT))
    else DirectoryStreamSource.excludeExtensionsFilter(exclusions)
    extFilter || DirectoryStreamSource.AcceptSubdirectoriesFilter
  }
}
