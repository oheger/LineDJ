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

package de.oliver_heger.linedj.archivehttp

import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.impl.*
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadManagementActor
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media.*
import de.oliver_heger.linedj.shared.archive.metadata.{GetMetadataFileInfo, MetadataFileInfo}
import de.oliver_heger.linedj.shared.archive.union.{UpdateOperationCompleted, UpdateOperationStarts}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import org.apache.pekko.routing.SmallestMailboxPool
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString

import scala.concurrent.Future
import scala.util.Success

object HttpArchiveManagementActor:
  /** The size of the cache for request actors with multi-host support. */
  val MultiHostCacheSize = 32

  private class HttpArchiveManagementActorImpl(processingService: ContentProcessingUpdateService,
                                               config: HttpArchiveConfig,
                                               pathGenerator: TempPathGenerator,
                                               unionMediaManager: ActorRef,
                                               unionMetaDataManager: ActorRef,
                                               monitoringActor: ActorRef,
                                               removeActor: ActorRef)
    extends HttpArchiveManagementActor(processingService, config, pathGenerator,
      unionMediaManager, unionMetaDataManager, monitoringActor, removeActor)
      with ChildActorFactory

  /**
    * Returns creation ''Props'' for this actor class.
    *
    * @param config               the configuration for the HTTP archive
    * @param pathGenerator        the generator for paths for temp files
    * @param unionMediaManager    the union media manager actor
    * @param unionMetadataManager the union metadata manager actor
    * @param monitoringActor      the download monitoring actor
    * @param removeActor          the actor for removing temp files
    * @return ''Props'' for creating a new actor instance
    */
  def apply(config: HttpArchiveConfig, pathGenerator: TempPathGenerator,
            unionMediaManager: ActorRef, unionMetadataManager: ActorRef,
            monitoringActor: ActorRef, removeActor: ActorRef): Props =
    Props(classOf[HttpArchiveManagementActorImpl], ContentProcessingUpdateServiceImpl, config,
      pathGenerator, unionMediaManager, unionMetadataManager, monitoringActor, removeActor)

  /** The number of parallel processor actors for metadata. */
  private val MetadataParallelism = 4

  /** The number of parallel processor actors for medium info files. */
  private val InfoParallelism = 2

  /**
    * Maps the specified exception to a state object for the current archive.
    * Some exceptions have to be treated in a special way.
    *
    * @param ex the exception
    * @return the corresponding ''HttpArchiveState''
    */
  private def stateFromException(ex: Throwable): HttpArchiveState =
    ex match
      case FailedResponseException(response) =>
        HttpArchiveStateFailedRequest(response.status)
      case _ =>
        HttpArchiveStateServerError(ex)

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
  * The actor can deal with plain and encrypted HTTP archives. In the latter
  * case, both file names and file content are encrypted. The key for
  * decryption is passed to the constructor. If it is defined, the actor
  * switches to decryption mode.
  *
  * @param processingService    the content processing update service
  * @param config               the configuration for the HTTP archive
  * @param pathGenerator        the generator for paths for temp files
  * @param unionMediaManager    the union media manager actor
  * @param unionMetadataManager the union metadata manager actor
  * @param monitoringActor      the download monitoring actor
  * @param removeActor          the actor for removing temp files
  */
class HttpArchiveManagementActor(processingService: ContentProcessingUpdateService,
                                 config: HttpArchiveConfig, pathGenerator: TempPathGenerator,
                                 unionMediaManager: ActorRef, unionMetadataManager: ActorRef,
                                 monitoringActor: ActorRef, removeActor: ActorRef) extends Actor
  with ActorLogging:
  this: ChildActorFactory =>

  import HttpArchiveManagementActor.*

  /** The archive content processor actor. */
  private var archiveContentProcessor: ActorRef = _

  /** The medium info processor actor. */
  private var mediumInfoProcessor: ActorRef = _

  /** The metadata processor actor. */
  private var metadataProcessor: ActorRef = _

  /** The download management actor. */
  private var downloadManagementActor: ActorRef = _

  /** The actor that propagates result to the union archive. */
  private var propagationActor: ActorRef = _

  /** The archive processing state. */
  private var processingState = ContentProcessingUpdateServiceImpl.InitialState

  /**
    * Stores a response for a request to the archive's current state.
    */
  private var archiveStateResponse: Option[HttpArchiveStateResponse] = None

  /**
    * A set with clients that asked for the archive's state, but could not be
    * served so far because a load operation was in progress.
    */
  private var pendingStateClients = Set.empty[ActorRef]

  import context.dispatcher

  override def preStart(): Unit =
    archiveContentProcessor = createChildActor(Props[HttpArchiveContentProcessorActor]())
    mediumInfoProcessor = createChildActor(SmallestMailboxPool(InfoParallelism)
      .props(Props[MediumInfoResponseProcessingActor]()))
    metadataProcessor = createChildActor(SmallestMailboxPool(MetadataParallelism)
      .props(Props[MetadataResponseProcessingActor]()))
    downloadManagementActor = createChildActor(HttpDownloadManagementActor(config = config,
      pathGenerator = pathGenerator, monitoringActor = monitoringActor,
      removeActor = removeActor))
    propagationActor = createChildActor(Props(classOf[ContentPropagationActor], unionMediaManager,
      unionMetadataManager, config.archiveName))
    updateArchiveState(HttpArchiveStateDisconnected)

  override def receive: Receive =
    case ScanAllMedia =>
      startArchiveProcessing()

    case HttpArchiveProcessingInit =>
      sender() ! HttpArchiveMediumAck

    case res: MediumProcessingResult =>
      updateStateWithTransitions(processingService.handleResultAvailable(res, sender(),
        config.propagationBufSize))

    case prop: MediumPropagated =>
      updateStateWithTransitions(processingService.handleResultPropagated(prop.seqNo,
        config.propagationBufSize))

    case HttpArchiveProcessingComplete(nextState) =>
      updateState(processingService.processingDone())
      updateArchiveState(nextState)
      unionMetadataManager ! UpdateOperationCompleted(None)

    case req: MediumFileRequest =>
      downloadManagementActor forward req

    case HttpArchiveStateRequest =>
      archiveStateResponse match
        case Some(response) =>
          sender() ! response
        case None =>
          pendingStateClients += sender()

    case alive: DownloadActorAlive =>
      monitoringActor ! alive

    case GetMetadataFileInfo =>
      // here just a dummy response is returned for this archive type
      sender() ! MetadataFileInfo(Map.empty, Set.empty, None)

    case CloseRequest =>
      archiveContentProcessor ! CancelStreams
      mediumInfoProcessor ! CancelStreams
      metadataProcessor ! CancelStreams
      sender() ! CloseAck(self)

  /**
    * Starts processing of the managed HTTP archive.
    */
  private def startArchiveProcessing(): Unit =
    if updateState(processingService.processingStarts()) then
      unionMetadataManager ! UpdateOperationStarts(None)
      archiveStateResponse = None
      val currentSeqNo = processingState.seqNo
      loadArchiveContent() map { data => createProcessArchiveRequest(data, currentSeqNo)
      } onComplete:
        case Success(req) =>
          archiveContentProcessor ! req
        case scala.util.Failure(ex) =>
          log.error(ex, "Could not load content document for archive " +
            config.archiveBaseUri)
          self ! HttpArchiveProcessingComplete(stateFromException(ex))

  /**
    * Creates a request to process an HTTP archive from the given response for
    * the archive's content document.
    *
    * @param data     the source from the content document
    * @param curSeqNo the current sequence number for the message
    * @return the processing request message
    */
  private def createProcessArchiveRequest(data: Source[ByteString, Any], curSeqNo: Int):
  ProcessHttpArchiveRequest =
    val sink = Sink.actorRefWithBackpressure(self, HttpArchiveProcessingInit,
      HttpArchiveMediumAck, HttpArchiveProcessingComplete(HttpArchiveStateConnected), Status.Failure.apply)
    ProcessHttpArchiveRequest(
      archiveConfig = config,
      settingsProcessorActor = mediumInfoProcessor,
      metadataProcessorActor = metadataProcessor,
      sink = sink,
      mediaSource = HttpMediumDescParser.parseMediumDescriptions(data),
      seqNo = curSeqNo,
      metadataParallelism = MetadataParallelism,
      infoParallelism = InfoParallelism
    )

  /**
    * Loads the content document from the managed archive and returns a
    * ''Future'' with a source of its bytes.
    *
    * @return a ''Future'' with the result of the operation
    */
  private def loadArchiveContent(): Future[Source[ByteString, Any]] =
    config.downloader.downloadMediaFile(config.contentPath)

  /**
    * Performs a state update using the specified update function.
    *
    * @param update the update function
    * @tparam A the type of the data produced by this update
    * @return the data produced by this update
    */
  private def updateState[A](update: ContentProcessingUpdateServiceImpl.StateUpdate[A]): A =
    val (next, data) = update(processingState)
    processingState = next
    if next.contentInArchive then
      updateArchiveState(HttpArchiveStateConnected)
    data

  /**
    * Performs a state update using the specified update function and executes
    * the actions triggered by this transition.
    *
    * @param update the update function
    */
  private def updateStateWithTransitions(update: ContentProcessingUpdateServiceImpl.
  StateUpdate[ProcessingStateTransitionData]): Unit =
    val transitionData = updateState(update)
    transitionData.propagateMsg foreach (propagationActor ! _)
    transitionData.actorToAck foreach (_ ! HttpArchiveMediumAck)

  /**
    * Updates the current archive state. If there are clients waiting for a
    * state notification, they are notified now.
    *
    * @param state the new state
    */
  private def updateArchiveState(state: HttpArchiveState): Unit =
    log.debug("Next archive state: {}.", state)
    val response = HttpArchiveStateResponse(config.archiveName, state)
    archiveStateResponse = Some(response)

    pendingStateClients foreach (_ ! response)
    pendingStateClients = Set.empty
