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

package de.oliver_heger.linedj.extract.metadata

import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport, FilterInstanceOfStage}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingResult
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorLogging, ActorRef, Props}
import org.apache.pekko.stream.scaladsl.{Broadcast, GraphDSL, Merge, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.{ClosedShape, KillSwitch, KillSwitches}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

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
  extends AbstractStreamProcessingActor with CancelableStreamSupport with ActorLogging:

  /**
    * A list with requests which have to be processed. Requests are processed
    * one by one to make sure that no more parallel processing takes place than
    * specified by the ''asyncCount'' parameter. So requests coming in while a
    * stream is still in progress are queued to be processed later. To be able
    * to send a result message, the requesting actor has to be recoded, too.
    */
  private var pendingRequests = List.empty[(ProcessMediaFiles, ActorRef)]

  /** A flag whether currently a stream is in progress. */
  private var streamInProgress = false

  /** Stores a reference to an actor expecting a CloseAck message. */
  private var closeClient: Option[ActorRef] = None

  override def customReceive: Receive =
    case p: ProcessMediaFiles if closeClient.isEmpty =>
      pendingRequests = (p, sender()) :: pendingRequests
      startStreamIfPossible()

    case CloseRequest =>
      cancelCurrentStreams()
      pendingRequests = Nil
      closeClient = Some(sender())
      if !streamInProgress then
        sender() ! CloseAck(self)

  /**
    * @inheritdoc This implementation sends the result of the last stream
    *             execution and starts the next stream if possible.
    */
  override protected def propagateResult(client: ActorRef, result: Any): Unit =
    println(s"propagateResult to $client: $result")
    super.propagateResult(client, result)
    streamInProgress = false
    startStreamIfPossible()
    closeClient.foreach(_ ! CloseAck(self))

  /**
    * Checks whether there are pending requests and no ongoing stream
    * processing. If so, a new stream is started for a request.
    */
  private def startStreamIfPossible(): Unit =
    if !streamInProgress && pendingRequests.nonEmpty then
      val (request, client) = pendingRequests.head
      pendingRequests = pendingRequests.tail
      processMediaFiles(request, client)

  /**
    * Processes a list of media files from a ''ProcessMediaFiles'' request.
    * For the given files a stream is created and materialized.
    *
    * @param request the processing request
    * @param client the client actor of this request
    */
  private def processMediaFiles(request: ProcessMediaFiles, client: ActorRef): Unit =
    log.info("Processing {} files for medium {}.", request.files.size, request.mediumID)
    val source = Source(request.availableResults.toList ++ request.files)
    val ks = KillSwitches.single[FileData | MetadataProcessingResult]
    val extractStage = new ExtractorStage(
      extractorFunctionProvider,
      extractTimeout,
      request.mediumID,
      request.uriMappingFunc
    )
    val filterFile = FilterInstanceOfStage[FileData]
    val filterResult = FilterInstanceOfStage[MetadataProcessingResult]
    val sink = Sink.foreach(metadataManager.!)
    val g = RunnableGraph.fromGraph(GraphDSL.createGraph(ks, sink, request.resultsSink)(combineMat) { implicit builder =>
      (ks, sinkActor, sinkResults) =>
        import GraphDSL.Implicits.*
        val broadcastProcess = builder.add(Broadcast[FileData | MetadataProcessingResult](2))
        val broadcastSinks = builder.add(Broadcast[MetadataProcessingResult](2))
        val merge = builder.add(Merge[MetadataProcessingResult](2))

        source ~> ks ~> broadcastProcess ~> filterFile ~> extractStage ~> broadcastSinks ~> sinkActor
        broadcastProcess ~> filterResult ~> merge
        broadcastSinks ~> merge ~> sinkResults
        ClosedShape
    })
    val (killSwitch, futStream) = g.run()
    val futResult = futStream.map(_ => ProcessMediaFilesResponse(request, success = true))
    processStreamResult(futResult, killSwitch, client): _ =>
      ProcessMediaFilesResponse(request, success = false)
    streamInProgress = true

  /**
    * The function to combine the materialized values from the graph running
    * the extraction process.
    *
    * @param ks    the kill switch
    * @param sink1 the result future from the first sink
    * @param sink2 the result future from the second sink
    * @return the combined materialized value
    */
  private def combineMat(ks: KillSwitch, sink1: Future[Done], sink2: Future[Any]): (KillSwitch, Future[Done]) =
    val combinedFuture = for
      fut1 <- sink1
      fut2 <- sink2
    yield Done
    (ks, combinedFuture)
