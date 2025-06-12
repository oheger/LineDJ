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

package de.oliver_heger.linedj.extract.metadata

import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration

object MetadataExtractorActor:
  /**
    * Returns a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param metadataManager           the actor receiving metadata processing
    *                                  results
    * @param extractorFunctionProvider the provider for extractor functions
    * @param extractTimeout            the timeout for an extract operation (of
    *                                  a single file)
    * @return ''Props'' for the new actor
    */
  def apply(metadataManager: ActorRef,
            extractorFunctionProvider: ExtractorFunctionProvider,
            extractTimeout: FiniteDuration): Props =
    Props(classOf[MetadataExtractorActor], metadataManager, extractorFunctionProvider, extractTimeout)


/**
  * An actor class that manages the extraction of metadata from a set of media
  * files.
  *
  * Instances of this actor class are created during a scan operation for media
  * files. An instance is responsible for the files below one of the root
  * folders to be scanned. It receives ''ProcessMediaFiles'' messages for the
  * several media encountered and the files for which no metadata is available
  * yet. Each message is processed one-by-one by iterating over the files
  * referenced and passing them through an [[ExtractorStage]]. All processing
  * results are then sent to a metadata receiver actor.
  *
  * To support multiple types of media files, an [[ExtractorFunctionProvider]]
  * has to be provided. This factory is used to obtain the actual extractor
  * functions for the file types encountered.
  *
  * A processing operation can be canceled by sending a ''CloseRequest''
  * message to this actor. This stops a currently running stream for processing
  * the media files from the last ''ProcessMediaFiles'' message. The actor
  * sends a ''CloseAck'' message when shutdown is complete. Afterward, no
  * further requests are accepted.
  *
  * @param metadataManager           the actor receiving metadata processing
  *                                  results
  * @param extractorFunctionProvider the provider for extractor functions
  * @param extractTimeout            the timeout for an extract operation (of
  *                                  a single file)
  */
class MetadataExtractorActor(metadataManager: ActorRef,
                             extractorFunctionProvider: ExtractorFunctionProvider,
                             extractTimeout: FiniteDuration)
  extends AbstractStreamProcessingActor with CancelableStreamSupport:

  /**
    * A list with requests which have to be processed. Requests are processed
    * one by one to make sure that no more parallel processing takes place than
    * specified by the ''asyncCount'' parameter. So requests coming in while a
    * stream is still in progress are queued to be processed later.
    */
  private var pendingRequests = List.empty[ProcessMediaFiles]

  /** A flag whether currently a stream is in progress. */
  private var streamInProgress = false

  /** Stores a reference to an actor expecting a CloseAck message. */
  private var closeClient: Option[ActorRef] = None

  override def customReceive: Receive =
    case p: ProcessMediaFiles if closeClient.isEmpty =>
      pendingRequests = p :: pendingRequests
      startStreamIfPossible()

    case CloseRequest =>
      cancelCurrentStreams()
      pendingRequests = Nil
      closeClient = Some(sender())
      if !streamInProgress then
        sender() ! CloseAck(self)

  /**
    * @inheritdoc This implementation does not send a result, but starts the
    *             next stream if possible.
    */
  override protected def propagateResult(client: ActorRef, result: Any): Unit =
    streamInProgress = false
    startStreamIfPossible()
    closeClient.foreach(_ ! CloseAck(self))

  /**
    * Checks whether there are pending requests and no ongoing stream
    * processing. If so, a new stream is started for a request.
    */
  private def startStreamIfPossible(): Unit =
    if !streamInProgress && pendingRequests.nonEmpty then
      val request = pendingRequests.head
      pendingRequests = pendingRequests.tail
      processMediaFiles(request.mediumID, request.files, request.uriMappingFunc)

  /**
    * Processes a list of media files from a ''ProcessMediaFiles'' request.
    * For the given files a stream is created and materialized.
    *
    * @param mediumID       the ID of the medium the files belong to
    * @param files          the list of files to be processed
    * @param uriMappingFunc the function to generate URIs for files
    */
  private def processMediaFiles(mediumID: MediumID, files: List[FileData],
                                uriMappingFunc: Path => MediaFileUri): Unit =
    val source = Source(files)
    val extractStage = new ExtractorStage(extractorFunctionProvider, extractTimeout, mediumID, uriMappingFunc)
    val sink = Sink.foreach(metadataManager.!)
    val (ks, futStream) = source
      .viaMat(KillSwitches.single)(Keep.right)
      .viaMat(extractStage)(Keep.left)
      .toMat(sink)(Keep.both)
      .run()
    processStreamResult(futStream, ks)(identity)
    streamInProgress = true
