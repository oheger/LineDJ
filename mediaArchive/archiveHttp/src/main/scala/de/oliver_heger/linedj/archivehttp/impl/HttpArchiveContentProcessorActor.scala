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

package de.oliver_heger.linedj.archivehttp.impl

import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport}
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ActorLogging, ActorRef}
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source, Zip}
import org.apache.pekko.util.ByteString

import scala.concurrent.Future
import scala.reflect.ClassTag

object HttpArchiveContentProcessorActor:
  /** Sequence number used for undefined results. */
  private val UndefinedSeqNo = -1

  /**
    * Creates a special undefined medium info result for the given medium ID.
    *
    * @param mid the medium ID
    * @return the undefined medium info result for this medium ID
    */
  private def createUndefinedInfoResult(mid: MediumID): MediumInfoResponseProcessingResult =
    MediumInfoResponseProcessingResult(MediumInfo("", "", mid, "", ""), UndefinedSeqNo)

  /**
    * Creates a special undefined metadata result for the given medium ID.
    *
    * @param mid the medium ID
    * @return the undefined metadata result for this medium ID
    */
  private def createUndefinedMetaResult(mid: MediumID): MetadataResponseProcessingResult =
    MetadataResponseProcessingResult(metadata = Nil, mediumID = mid, seqNo = UndefinedSeqNo)

  /**
    * Tests whether a medium description is fully defined. All paths must be
    * not null.
    *
    * @param desc the description to check
    * @return a flag whether the description is fully defined
    */
  private def isFullyDefined(desc: HttpMediumDesc): Boolean =
    desc.metaDataPath != null && desc.mediumDescriptionPath != null

  /**
    * Checks whether the specified result object is valid. This function,
    * together with the ''createUndefinedXXXResult()'' functions, is used to
    * identify and filter out results created from partial processing results
    * with errors.
    *
    * @param result the medium processing result to be checked
    * @return a flag whether this result is valid
    */
  private def isValidResult(result: MediumProcessingResult): Boolean =
    result.mediumInfo.name.nonEmpty && result.metadata.nonEmpty

/**
  * An actor class that processes the content of an HTTP archive.
  *
  * This actor class processes the content document of an HTTP archive. For
  * each referenced medium it downloads the settings and the metadata files.
  * The responses of these download requests are sent to special processor
  * actors which extract the relevant information and pass it back to this
  * actor. All processing results are then sent to the manager actor which is
  * responsible for the HTTP archive currently processed.
  *
  * The download and processing of files from the archive is done in a single
  * stream. This actor configures the stream and materializes it. When the
  * stream has been fully processed the manager actor is notified.
  */
class HttpArchiveContentProcessorActor extends AbstractStreamProcessingActor with ActorLogging
  with CancelableStreamSupport:

  import HttpArchiveContentProcessorActor._

  override def customReceive: Receive =
    case req: ProcessHttpArchiveRequest =>
      val (killSwitch, futStream) = materializeStream(req)
      processStreamResult(futStream, killSwitch)(identity)

  /**
    * Sends the specified result to the original caller. This method is called
    * for each completed stream. This base implementation just sends the result
    * to the caller. Derived classes could override it to execute some
    * additional logic.
    *
    * @param client the client to receive the response
    * @param result the result message
    */
  override protected def propagateResult(client: ActorRef, result: Any): Unit =
    log.info("Stream processing completed.")

  /**
    * Creates the stream for processing the specified archive request.
    * This stream loads all settings and metadata files of the media contained
    * in this HTTP archive, combines the results and passes the resulting
    * [[MediumProcessingResult]] objects to the specified sinks. Note that a
    * second dummy sink is added to the stream; this is needed to obtain a
    * future to determine when stream processing is complete. This method also
    * returns a ''KillSwitch'' to cancel stream processing on an external
    * request.
    *
    * @param req the request to process the archive
    * @return a ''Future'' when the stream is done and a ''KillSwitch''
    */
  private def materializeStream(req: ProcessHttpArchiveRequest):
  (KillSwitch, Future[Any]) =
    val ks = KillSwitches.single[HttpMediumDesc]
    val filterDesc = Flow[HttpMediumDesc].filter(isFullyDefined)
    val infoReq = requestMappingFlow(req)(createMediumInfoRequest)
    val metaReq = requestMappingFlow(req)(createMetaDataRequest)
    val processInfo = processFlow(req, req.infoParallelism)(createUndefinedInfoResult)
    val processMeta = processFlow(req, req.metadataParallelism)(createUndefinedMetaResult)
    val combine = new ProcessingResultCombiningStage
    val filterUndef = Flow[MediumProcessingResult].filter(isValidResult)
    val sinkDone = Sink.ignore
    val g = RunnableGraph.fromGraph(GraphDSL.createGraph(ks, sinkDone)((_, _)) { implicit builder =>
      (ks, sink) =>
        import GraphDSL.Implicits._
        val broadcast = builder.add(Broadcast[HttpMediumDesc](2))
        val merge = builder.add(
          Zip[MediumInfoResponseProcessingResult, MetadataResponseProcessingResult]())
        val broadcastSink = builder.add(Broadcast[MediumProcessingResult](2))

        req.mediaSource ~> ks ~> filterDesc ~> broadcast
        broadcast ~> infoReq ~> processInfo ~> merge.in0
        broadcast ~> metaReq ~> processMeta ~> merge.in1
        merge.out ~> combine ~> filterUndef ~> broadcastSink ~> req.sink
        broadcastSink ~> sink
        ClosedShape
    })
    g.run()

  /**
    * Returns a flow that produces a tuple to be passed to a request flow based
    * on a medium description and a mapping function. The mapping function
    * produces a concrete request from the ''HttpMediumDesc'', represented by
    * parameters that can be passed directly to a media downloader.
    *
    * @param req the request to process the archive
    * @param f   the request mapping function
    * @return a tuple of a request and a context data object
    */
  private def requestMappingFlow(req: ProcessHttpArchiveRequest)
                                (f: (ProcessHttpArchiveRequest, HttpMediumDesc) => (Uri.Path, String, RequestData)):
  Flow[HttpMediumDesc, (Uri.Path, String, RequestData), NotUsed] =
    Flow[HttpMediumDesc].map(f(req, _))

  /**
    * Creates a flow stage that invokes a processing actor to obtain a partial
    * result. This is basically an ask invocation of an actor mapped to the
    * expected result type. If the future for the invocation fails, a special
    * undefined result is returned that is created by the function provided.
    * This seems to be necessary, otherwise the zip stage combines wrong
    * elements. It is possible to define the degree of parallelism if there is
    * a pool of processor actors.
    *
    * @param req         the request to process the archive
    * @param parallelism the degree of parallelism
    * @param fUndef      the undefined result to return in case of an error
    * @param tag         the class tag
    * @tparam T the type of the result
    * @return the processing flow stage
    */
  private def processFlow[T](req: ProcessHttpArchiveRequest, parallelism: Int)
                            (fUndef: MediumID => T)
                            (implicit tag: ClassTag[T])
  : Flow[(Uri.Path, String, RequestData), T, NotUsed] =
    Flow[(Uri.Path, String, RequestData)].mapAsync(parallelism) { t =>
      (for data <- req.archiveConfig.downloader.downloadMediaFile(t._1, t._2)
            procResp <- processHttpResponse(req, (data, t._3)).mapTo[T]
            yield procResp) fallbackTo Future:
        val mid = createMediumID(req, t._3.mediumDesc)
        fUndef(mid)
    }

  /**
    * Generates request data for the medium info file of the specified medium
    * description.
    *
    * @param req the request to process the archive
    * @param md  the description of the medium affected
    * @return request data for the medium info file
    */
  private def createMediumInfoRequest(req: ProcessHttpArchiveRequest, md: HttpMediumDesc):
  (Uri.Path, String, RequestData) =
    (req.archiveConfig.mediaPath, md.mediumDescriptionPath, RequestData(md, req.settingsProcessorActor))

  /**
    * Generates request data for the metadata file of the specified medium
    * description.
    *
    * @param req the request to process the archive
    * @param md  the description of the medium affected
    * @return request data for the metadata file
    */
  private def createMetaDataRequest(req: ProcessHttpArchiveRequest, md: HttpMediumDesc):
  (Uri.Path, String, RequestData) =
    (req.archiveConfig.metadataPath, md.metaDataPath, RequestData(md, req.metadataProcessorActor))

  /**
    * Processes a response received from the HTTP archive. The response now has
    * to be send to the correct processing actor.
    *
    * @param req the request to process the archive
    * @param t   the tuple with data received from the download
    * @return a future for the message expected from the processor actor
    */
  private def processHttpResponse(req: ProcessHttpArchiveRequest,
                                  t: (Source[ByteString, Any], RequestData)): Future[Any] =
    val mediumID = createMediumID(req, t._2.mediumDesc)
    val msg = ProcessResponse(mediumID, t._2.mediumDesc, t._1, req.archiveConfig, req.seqNo)
    t._2.processorActor.ask(msg)(req.archiveConfig.processorTimeout)

  /**
    * Creates a medium ID from the given medium description.
    *
    * @param req the processing request
    * @param md  the medium description
    * @return the ''MediumID''
    */
  private def createMediumID(req: ProcessHttpArchiveRequest, md: HttpMediumDesc): MediumID =
    val pos = md.mediumDescriptionPath.lastIndexOf('/')
    val mediumURI = md.mediumDescriptionPath.substring(0, pos)
    MediumID(mediumURI, Some(md.mediumDescriptionPath), req.archiveConfig.archiveName)
