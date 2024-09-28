/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport}
import de.oliver_heger.linedj.io.{CloseRequest, CloseSupport, FileData}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingSuccess, ProcessMetadataFile}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.util.Timeout

import java.nio.file.Path

object MetaDataExtractionActor:

  private class MetaDataExtractionActorImpl(metaDataManager: ActorRef,
                                            extractorFactory: ExtractorActorFactory,
                                            asyncCount: Int,
                                            asyncTimeout: Timeout)
    extends MetaDataExtractionActor(metaDataManager, extractorFactory,
      asyncCount, asyncTimeout) with ChildActorFactory with CloseSupport

  /**
    * Returns a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param metaDataManager  the actor receiving metadata processing results
    * @param extractorFactory the factory for extractor actors
    * @param asyncCount       the number of parallel processing actors
    * @param asyncTimeout     the timeout for processing of a single file
    * @return ''Props'' for the new actor
    */
  def apply(metaDataManager: ActorRef, extractorFactory: ExtractorActorFactory,
            asyncCount: Int, asyncTimeout: Timeout):
  Props =
    Props(classOf[MetaDataExtractionActorImpl], metaDataManager, extractorFactory,
      asyncCount, asyncTimeout)


/**
  * An actor class that manages the extraction of metadata from a set of media
  * files.
  *
  * Instances of this actor class are created during a scan operation for media
  * files. An instance is responsible for the files below one of the root
  * folders to be scanned. It receives ''ProcessMediaFiles'' messages for the
  * several media encountered and the files for which no metadata is available
  * yet. Each message is processed one-by-one by iterating over the files
  * referenced and sending a process request to a
  * [[MetaDataExtractorWrapperActor]] child actor. All processing results are
  * then sent to a metadata receiver actor.
  *
  * For each root path with media files it can be configured how many files
  * should be processed in parallel. The degree of parallelism for the root
  * an instance is responsible for is passed to the constructor. The actor
  * will then create corresponding streams that use the desired async factor.
  *
  * To support multiple types of media files, an [[ExtractorActorFactory]]
  * has to be provided. This factory is used to obtain the actual extractor
  * actors for the file types encountered.
  *
  * A processing operation can be canceled by sending a ''CloseRequest''
  * message to this actor. It delegates this operation to the actual extractor
  * actors and answers with a ''CloseAck'' when shutdown is complete.
  * Afterwards, no further requests are accepted. All actor instances created
  * during a scan operation should be stopped when the scan is done.
  *
  * @param metaDataManager  the actor receiving metadata processing results
  * @param extractorFactory the factory for extractor actors
  * @param asyncCount       the number of parallel processing actors
  * @param asyncTimeout     the timeout for processing of a single file
  */
class MetaDataExtractionActor(metaDataManager: ActorRef,
                              extractorFactory: ExtractorActorFactory, asyncCount: Int,
                              asyncTimeout: Timeout)
  extends AbstractStreamProcessingActor with CancelableStreamSupport:
  this: ChildActorFactory with CloseSupport =>

  /** The extractor wrapper child actor. */
  private var extractorWrapperActor: ActorRef = _

  /**
    * A list with requests which have to be processed. Requests are processed
    * one by one to make sure that no more parallel processing takes place than
    * specified by the ''asyncCount'' parameter. So requests coming in while a
    * stream is still in progress are queued to be processed later.
    */
  private var pendingRequests = List.empty[ProcessMediaFiles]

  /** A flag whether currently a stream is in progress. */
  private var streamInProgress = false

  /** A flag whether this actor has been closed. */
  private var closed = false

  override def preStart(): Unit =
    super.preStart()
    extractorWrapperActor = createChildActor(MetaDataExtractorWrapperActor(extractorFactory))

  override def customReceive: Receive =
    case p: ProcessMediaFiles if !closed =>
      pendingRequests = p :: pendingRequests
      startStreamIfPossible()

    case CloseRequest =>
      onCloseRequest(self, List(extractorWrapperActor), sender(), this, !streamInProgress)
      cancelCurrentStreams()
      pendingRequests = Nil
      closed = true

    case CloseComplete =>
      onCloseComplete()

  /**
    * @inheritdoc This implementation does not send a result, but starts the
    *             next stream if possible.
    */
  override protected def propagateResult(client: ActorRef, result: Any): Unit =
    streamInProgress = false
    startStreamIfPossible()
    if closed then
      onConditionSatisfied()

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
    * Creates the source for a stream that processes the specified media files.
    *
    * @param files the list of files to be processed
    * @return the source to process these files
    */
  private[metadata] def createSource(files: List[FileData]): Source[FileData, NotUsed] =
    Source(files)

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
    implicit val timeout: Timeout = asyncTimeout
    val source = createSource(files)
    val sink = Sink.foreach(metaDataManager.!)
    val (ks, futStream) = source
      .map(fd => processRequest(mediumID, fd, uriMappingFunc))
      .viaMat(KillSwitches.single)(Keep.right)
      .mapAsyncUnordered(asyncCount) { p =>
        (extractorWrapperActor ? p) recover:
          case e => p.resultTemplate.toError(e)
      }
      .toMat(sink)(Keep.both)
      .run()
    processStreamResult(futStream, ks)(identity)
    streamInProgress = true

  /**
    * Creates a request to process the specified file.
    *
    * @param mediumID       the medium ID
    * @param fd             the data for the file to be processed
    * @param uriMappingFunc the function to generate URIs for files
    * @return the request to process this file
    */
  private def processRequest(mediumID: MediumID, fd: FileData,
                             uriMappingFunc: Path => MediaFileUri): ProcessMetadataFile =
    ProcessMetadataFile(fd, MetadataProcessingSuccess(mediumID, uriMappingFunc(fd.path), MediaMetaData()))
