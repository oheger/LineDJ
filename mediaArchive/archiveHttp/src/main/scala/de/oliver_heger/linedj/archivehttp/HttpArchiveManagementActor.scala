/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp

import java.nio.charset.StandardCharsets

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.parser.ParserTypes.Failure
import de.oliver_heger.linedj.archivecommon.parser.{JSONParser, ParserImpl, ParserStage}
import de.oliver_heger.linedj.archivecommon.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.impl._
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo, ScanAllMedia}
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved, MediaContribution, MetaDataProcessingSuccess}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.Future
import scala.util.{Success, Try}

object HttpArchiveManagementActor {

  private class HttpArchiveManagementActorImpl(config: HttpArchiveConfig,
                                               unionMediaManager: ActorRef,
                                               unionMetaDataManager: ActorRef)
    extends HttpArchiveManagementActor(config, unionMediaManager, unionMetaDataManager)
      with ChildActorFactory with HttpFlowFactory

  /**
    * Returns creation ''Props'' for this actor class.
    *
    * @param config               the configuration for the HTTP archive
    * @param unionMediaManager    the union media manager actor
    * @param unionMetaDataManager the union meta data manager actor
    * @return ''Props'' for creating a new actor instance
    */
  def apply(config: HttpArchiveConfig, unionMediaManager: ActorRef,
            unionMetaDataManager: ActorRef): Props =
    Props(classOf[HttpArchiveManagementActorImpl], config, unionMediaManager,
      unionMetaDataManager)

  /** The object for parsing medium descriptions in JSON. */
  private val parser = new HttpMediumDescParser(ParserImpl, JSONParser.jsonParser(ParserImpl))

  /**
    * A function for parsing JSON to a sequence of ''HttpMediumDesc'' objects.
    *
    * @param chunk       the chunk of data to be processed
    * @param lastFailure the last failure
    * @param lastChunk   flag whether this is the last chunk
    * @return a tuple with extracted results and the next failure
    */
  private def parseHttpMediumDesc(chunk: ByteString, lastFailure: Option[Failure],
                                  lastChunk: Boolean):
  (Iterable[HttpMediumDesc], Option[Failure]) =
    parser.processChunk(chunk.decodeString(StandardCharsets.UTF_8), null, lastChunk, lastFailure)
}

/**
  * An actor class responsible for integrating an HTTP media archive.
  *
  * This actor is the entry point into the classes that support loading data
  * from HTTP archives. An instance is created with a configuration object
  * describing the HTTP archive to integrate. When sent a scan request it
  * loads the archive's content description and the data files for the
  * single media available. The data is collected and then propagated to a
  * union media archive.
  *
  * @param config               the configuration for the HTTP archive
  * @param unionMediaManager    the union media manager actor
  * @param unionMetaDataManager the union meta data manager actor
  */
class HttpArchiveManagementActor(config: HttpArchiveConfig, unionMediaManager: ActorRef,
                                 unionMetaDataManager: ActorRef) extends Actor
  with ActorLogging {
  this: ChildActorFactory with HttpFlowFactory =>

  import HttpArchiveManagementActor._

  /** An unused RequestData object; needed for stream handling. */
  private val UnusedReqData = RequestData(null, null)

  /** Object for materializing streams. */
  private implicit val materializer = ActorMaterializer()

  /** The archive content processor actor. */
  private var archiveContentProcessor: ActorRef = _

  /** The medium info processor actor. */
  private var mediumInfoProcessor: ActorRef = _

  /** The meta data processor actor. */
  private var metaDataProcessor: ActorRef = _

  /** The flow for sending HTTP requests. */
  private var httpFlow:
    Flow[(HttpRequest, RequestData), (Try[HttpResponse], RequestData), Any] = _

  /** A list with information of media discovered during a scan operation. */
  private var mediaInfo = List.empty[MediumInfo]

  /**
    * A list with information about files per medium constructed during a
    * scan operation.
    */
  private var fileInfo = List.empty[(MediumID, Iterable[FileData])]

  /** A set with meta data objects aggregated during a scan operation. */
  private var metaData = Set.empty[MetaDataProcessingSuccess]

  /**
    * A sequence number identifying the current scan operation. The sequence
    * number is increased every time is scan is completed. Because all
    * processing results contain the sequence number of the current operation
    * it is possible to find out which results are from an older operation.
    * (This greatly simplifies cancellation of operations: it is no necessary
    * to wait until all involved actors have finished processing, but the
    * sequence number is just changed, and results from older operations are
    * ignored.)
    */
  private var seqNo = 0

  /** A flag whether a scan is currently in progress. */
  private var scanInProgress = false

  /** A flag whether this actor has contributed data to the union archive. */
  private var dataInUnionArchive = false

  import context.dispatcher
  import context.system

  override def preStart(): Unit = {
    archiveContentProcessor = createChildActor(Props[HttpArchiveContentProcessorActor])
    mediumInfoProcessor = createChildActor(Props[MediumInfoResponseProcessingActor])
    metaDataProcessor = createChildActor(Props[MetaDataResponseProcessingActor])
    httpFlow = createHttpFlow[RequestData](config.archiveURI)
  }

  override def receive: Receive = {
    case ScanAllMedia if !scanInProgress =>
      startArchiveProcessing()

    case MediumInfoResponseProcessingResult(info, sn) if sn == seqNo =>
      mediaInfo = info :: mediaInfo

    case MetaDataResponseProcessingResult(mid, meta, sn) if sn == seqNo =>
      fileInfo = (mid, meta.map(m => FileData(m.path, m.metaData.size))) :: fileInfo
      metaData ++= meta

    case HttpArchiveProcessingComplete(sn) if sn == seqNo =>
      val validMedia = completeMedia
      if (validMedia.nonEmpty) {
        unionMediaManager ! createAddMediaMsg(validMedia)
        unionMetaDataManager ! createMediaContributionMsg(validMedia)
        filteredMetaDataResults(validMedia) foreach unionMetaDataManager.!
        dataInUnionArchive = true
      }
      completeScanOperation()

    case CloseRequest =>
      archiveContentProcessor ! CancelStreams
      mediumInfoProcessor ! CancelStreams
      metaDataProcessor ! CancelStreams
      sender ! CloseAck(self)
      completeScanOperation()
  }

  /**
    * Starts processing of the managed HTTP archive.
    */
  private def startArchiveProcessing(): Unit = {
    scanInProgress = true
    if (dataInUnionArchive) {
      unionMediaManager ! ArchiveComponentRemoved(config.archiveURI.toString())
      dataInUnionArchive = false
    }

    loadArchiveContent().map(_._1.get)
      .filter(_.status.isSuccess())
      .map { resp => createProcessArchiveRequest(resp)
      } onComplete {
      case Success(req) =>
        archiveContentProcessor ! req
      case scala.util.Failure(ex) =>
        log.error(ex, "Could not load content document for archive " +
          config.archiveURI)
    }
  }

  /**
    * Creates a request to process an HTTP archive from the given response for
    * the archive's content document.
    *
    * @param resp the response from the archive
    * @return the processing request message
    */
  private def createProcessArchiveRequest(resp: HttpResponse): ProcessHttpArchiveRequest = {
    val parseStage = new ParserStage[HttpMediumDesc](parseHttpMediumDesc)
    ProcessHttpArchiveRequest(clientFlow = httpFlow, archiveConfig = config,
      settingsProcessorActor = mediumInfoProcessor, metaDataProcessorActor = metaDataProcessor,
      archiveActor = self, mediaSource = resp.entity.dataBytes.via(parseStage),
      seqNo = seqNo)
  }

  /**
    * Creates the request for the content document of the HTTP archive.
    *
    * @return the request for the archive content
    */
  private def createArchiveContentRequest(): HttpRequest =
    HttpRequest(uri = config.archiveURI,
      headers = List(Authorization(BasicHttpCredentials(config.credentials.userName,
        config.credentials.password))))

  /**
    * Loads the content document from the managed archive using the
    * ''httpFlow'' created on construction time.
    *
    * @return a ''Future'' with the result of the operation
    */
  private def loadArchiveContent(): Future[(Try[HttpResponse], RequestData)] = {
    val contentRequest = createArchiveContentRequest()
    log.info("Requesting content of archive {}.", contentRequest.uri)
    Source.single((contentRequest, UnusedReqData))
      .via(httpFlow)
      .runWith(Sink.last[(Try[HttpResponse], RequestData)])
  }

  /**
    * Resets internal flags after a scan operation has completed.
    */
  private def completeScanOperation(): Unit = {
    mediaInfo = List.empty
    fileInfo = List.empty
    metaData = Set.empty
    scanInProgress = false
    seqNo += 1
  }

  /**
    * Returns a set with the media for which complete information is available
    * after a scan operation. Only those media are passed to the union archive.
    *
    * @return a set with the IDs of complete media
    */
  private def completeMedia: Set[MediumID] =
    fileInfo.map(_._1).toSet.intersect(mediaInfo.map(_.mediumID).toSet)

  /**
    * Creates an ''AddMedia'' message for scan results.
    *
    * @param completeMedia the set with complete media
    * @return the ''AddMedia'' message
    */
  private def createAddMediaMsg(completeMedia: Set[MediumID]): AddMedia =
    AddMedia(mediaInfo.filter(completeMedia contains _.mediumID)
      .map(i => (i.mediumID, i)).toMap, config.archiveURI.toString(), None)

  /**
    * Creates a ''MediaContribution'' message for scan results.
    *
    * @param completeMedia the set with complete media
    * @return the ''MediaContribution'' message
    */
  private def createMediaContributionMsg(completeMedia: Set[MediumID]): MediaContribution =
    MediaContribution(fileInfo.filter(completeMedia contains _._1).toMap)

  /**
    * Returns a set with meta data results for the complete media only. These
    * are the results to be sent to the union archive.
    *
    * @param validMedia the set with complete media
    * @return the set with results to be sent to the union archive
    */
  private def filteredMetaDataResults(validMedia: Set[MediumID]): Set[MetaDataProcessingSuccess] =
    metaData filter (validMedia contains _.mediumID)
}
