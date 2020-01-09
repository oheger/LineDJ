/*
 * Copyright 2015-2019 The Developers Team.
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
import java.security.Key

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.model.HttpResponse
import akka.routing.SmallestMailboxPool
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig.AuthConfigureFunc
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, OAuthStorageConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.crypt.Secret
import de.oliver_heger.linedj.archivehttp.impl._
import de.oliver_heger.linedj.archivehttp.impl.crypt.{CryptHttpRequestActor, UriResolverActor}
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadManagementActor
import de.oliver_heger.linedj.archivehttp.impl.io.oauth._
import de.oliver_heger.linedj.archivehttp.impl.io._
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.io.parser.ParserTypes.Failure
import de.oliver_heger.linedj.io.parser.{JSONParser, ParserImpl, ParserStage}
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.metadata.{GetMetaDataFileInfo, MetaDataFileInfo}
import de.oliver_heger.linedj.shared.archive.union.{UpdateOperationCompleted, UpdateOperationStarts}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

object HttpArchiveManagementActor extends HttpAuthFactory {
  /** The size of the cache for request actors with multi-host support. */
  val MultiHostCacheSize = 32

  private class HttpArchiveManagementActorImpl(processingService: ContentProcessingUpdateService,
                                               config: HttpArchiveConfig,
                                               pathGenerator: TempPathGenerator,
                                               unionMediaManager: ActorRef,
                                               unionMetaDataManager: ActorRef,
                                               monitoringActor: ActorRef,
                                               removeActor: ActorRef,
                                               optCryptKey: Option[Key])
    extends HttpArchiveManagementActor(processingService, config, pathGenerator,
      unionMediaManager, unionMetaDataManager, monitoringActor, removeActor, optCryptKey)
      with ChildActorFactory

  /**
    * Returns creation ''Props'' for this actor class.
    *
    * @param config               the configuration for the HTTP archive
    * @param pathGenerator        the generator for paths for temp files
    * @param unionMediaManager    the union media manager actor
    * @param unionMetaDataManager the union meta data manager actor
    * @param monitoringActor      the download monitoring actor
    * @param removeActor          the actor for removing temp files
    * @param optCryptKey          an option for a key for decryption
    * @return ''Props'' for creating a new actor instance
    */
  def apply(config: HttpArchiveConfig, pathGenerator: TempPathGenerator,
            unionMediaManager: ActorRef, unionMetaDataManager: ActorRef,
            monitoringActor: ActorRef, removeActor: ActorRef, optCryptKey: Option[Key] = None): Props =
    Props(classOf[HttpArchiveManagementActorImpl], ContentProcessingUpdateServiceImpl, config,
      pathGenerator, unionMediaManager, unionMetaDataManager, monitoringActor, removeActor, optCryptKey)

  /** The object for parsing medium descriptions in JSON. */
  private val parser = new HttpMediumDescParser(ParserImpl, JSONParser.jsonParser(ParserImpl))

  /** The number of parallel processor actors for meta data. */
  private val MetaDataParallelism = 4

  /** The number of parallel processor actors for medium info files. */
  private val InfoParallelism = 2

  /**
    * Returns a function to configure authentication that implements the basic
    * auth scheme.
    *
    * @param credentials the credentials to be applied
    * @return the basic auth configure function
    */
  override def basicAuthConfigureFunc(credentials: UserCredentials): Future[AuthConfigureFunc] =
    Future.successful {
      (requestActor, factory) =>
        factory.createChildActor(Props(classOf[HttpBasicAuthRequestActor], credentials, requestActor))
    }

  /**
    * Returns a ''Future'' with a function to configure authentication based on
    * OAuth 2.0. This function tries to load all relevant OAuth-specific
    * information as specified by the passed in configuration. This is done
    * asynchronously and may fail. The resulting function can then extend a
    * plain HTTP request actor to send a correct access token in the
    * ''Authorization'' header.
    *
    * @param storageConfig the OAuth storage configuration
    * @param ec            the execution context
    * @param mat           the object to materialize streams
    * @return a ''Future'' with the OAuth configure function
    */
  override def oauthConfigureFunc(storageConfig: OAuthStorageConfig)
                                 (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[AuthConfigureFunc] =
    for {config <- OAuthStorageServiceImpl.loadConfig(storageConfig)
         secret <- OAuthStorageServiceImpl.loadClientSecret(storageConfig)
         tokens <- OAuthStorageServiceImpl.loadTokens(storageConfig)
         } yield oauthConfigureFunc(config, secret, tokens)

  /**
    * Internal helper function for creating a configure function for OAuth 2
    * authentication. This function assumes that the provider-specific
    * information has already been loaded.
    *
    * @param oauthConfig  the OAuth configuration
    * @param clientSecret the client secret
    * @param tokens       the initial token information
    * @return the OAuth configure function
    */
  private def oauthConfigureFunc(oauthConfig: OAuthConfig, clientSecret: Secret, tokens: OAuthTokenData):
  AuthConfigureFunc = (requestActor, factory) => {
    val idpActorProps = HttpRequestActor(oauthConfig.tokenEndpoint, HttpArchiveConfig.DefaultRequestQueueSize)
    val idpActor = factory.createChildActor(idpActorProps)
    factory.createChildActor(OAuthTokenActor(requestActor, idpActor, oauthConfig, clientSecret, tokens,
      OAuthTokenRetrieverServiceImpl))
  }

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

  /**
    * Maps the specified exception to a state object for the current archive.
    * Some exceptions have to be treated in a special way.
    *
    * @param ex the exception
    * @return the corresponding ''HttpArchiveState''
    */
  private def stateFromException(ex: Throwable): HttpArchiveState =
    ex match {
      case FailedRequestException(_, _, Some(response), _) =>
        HttpArchiveStateFailedRequest(response.status)
      case _ =>
        HttpArchiveStateServerError(ex)
    }
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
  * The actor can deal with plain and encrypted HTTP archives. In the latter
  * case, both file names and file content are encrypted. The key for
  * decryption is passed to the constructor. If it is defined, the actor
  * switches to decryption mode.
  *
  * @param processingService    the content processing update service
  * @param config               the configuration for the HTTP archive
  * @param pathGenerator        the generator for paths for temp files
  * @param unionMediaManager    the union media manager actor
  * @param unionMetaDataManager the union meta data manager actor
  * @param monitoringActor      the download monitoring actor
  * @param removeActor          the actor for removing temp files
  * @param optCryptKey          an option with the decryption key
  */
class HttpArchiveManagementActor(processingService: ContentProcessingUpdateService,
                                 config: HttpArchiveConfig, pathGenerator: TempPathGenerator,
                                 unionMediaManager: ActorRef, unionMetaDataManager: ActorRef,
                                 monitoringActor: ActorRef, removeActor: ActorRef,
                                 optCryptKey: Option[Key]) extends Actor
  with ActorLogging {
  this: ChildActorFactory =>

  import HttpArchiveManagementActor._

  /** Object for materializing streams. */
  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  /** The actor for sending HTTP requests. */
  private var requestActor: ActorRef = _

  /** The archive content processor actor. */
  private var archiveContentProcessor: ActorRef = _

  /** The medium info processor actor. */
  private var mediumInfoProcessor: ActorRef = _

  /** The meta data processor actor. */
  private var metaDataProcessor: ActorRef = _

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

  override def preStart(): Unit = {
    requestActor = createRequestActor()
    archiveContentProcessor = createChildActor(Props[HttpArchiveContentProcessorActor])
    mediumInfoProcessor = createChildActor(SmallestMailboxPool(InfoParallelism)
      .props(Props[MediumInfoResponseProcessingActor]))
    metaDataProcessor = createChildActor(SmallestMailboxPool(MetaDataParallelism)
      .props(Props[MetaDataResponseProcessingActor]))
    downloadManagementActor = createChildActor(HttpDownloadManagementActor(config = config,
      pathGenerator = pathGenerator, monitoringActor = monitoringActor,
      removeActor = removeActor, requestActor = requestActor))
    propagationActor = createChildActor(Props(classOf[ContentPropagationActor], unionMediaManager,
      unionMetaDataManager, config.archiveURI.toString()))
    updateArchiveState(HttpArchiveStateDisconnected)
  }

  override def receive: Receive = {
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
      unionMetaDataManager ! UpdateOperationCompleted(None)

    case req: MediumFileRequest =>
      downloadManagementActor forward req

    case HttpArchiveStateRequest =>
      archiveStateResponse match {
        case Some(response) =>
          sender ! response
        case None =>
          pendingStateClients += sender()
      }

    case alive: DownloadActorAlive =>
      monitoringActor ! alive

    case GetMetaDataFileInfo =>
      // here just a dummy response is returned for this archive type
      sender ! MetaDataFileInfo(Map.empty, Set.empty, None)

    case CloseRequest =>
      archiveContentProcessor ! CancelStreams
      mediumInfoProcessor ! CancelStreams
      metaDataProcessor ! CancelStreams
      sender ! CloseAck(self)
  }

  /**
    * Starts processing of the managed HTTP archive.
    */
  private def startArchiveProcessing(): Unit = {
    if (updateState(processingService.processingStarts())) {
      unionMetaDataManager ! UpdateOperationStarts(None)
      archiveStateResponse = None
      val currentSeqNo = processingState.seqNo
      loadArchiveContent() map { resp => createProcessArchiveRequest(resp, currentSeqNo)
      } onComplete {
        case Success(req) =>
          archiveContentProcessor ! req
        case scala.util.Failure(ex) =>
          log.error(ex, "Could not load content document for archive " +
            config.archiveURI)
          self ! HttpArchiveProcessingComplete(stateFromException(ex))
      }
    }
  }

  /**
    * Creates a request to process an HTTP archive from the given response for
    * the archive's content document.
    *
    * @param resp     the response from the archive
    * @param curSeqNo the current sequence number for the message
    * @return the processing request message
    */
  private def createProcessArchiveRequest(resp: HttpResponse, curSeqNo: Int):
  ProcessHttpArchiveRequest = {
    val parseStage = new ParserStage[HttpMediumDesc](parseHttpMediumDesc)
    val sink = Sink.actorRefWithAck(self, HttpArchiveProcessingInit,
      HttpArchiveMediumAck, HttpArchiveProcessingComplete(HttpArchiveStateConnected))
    ProcessHttpArchiveRequest(clientFlow = null, requestActor = requestActor, archiveConfig = config,
      settingsProcessorActor = mediumInfoProcessor, metaDataProcessorActor = metaDataProcessor,
      sink = sink, mediaSource = resp.entity.dataBytes.via(parseStage),
      seqNo = curSeqNo, metaDataParallelism = MetaDataParallelism,
      infoParallelism = InfoParallelism)
  }

  /**
    * Loads the content document from the managed archive using the request
    * actor created on construction time.
    *
    * @return a ''Future'' with the result of the operation
    */
  private def loadArchiveContent(): Future[HttpResponse] = {
    implicit val requestTimeout: Timeout = config.processorTimeout
    log.info("Requesting content of archive {}.", config.archiveURI)
    config.protocol.downloadMediaFile(requestActor, config.archiveURI) map (_.response)
  }

  /**
    * Performs a state update using the specified update function.
    *
    * @param update the update function
    * @tparam A the type of the data produced by this update
    * @return the data produced by this update
    */
  private def updateState[A](update: ContentProcessingUpdateServiceImpl.StateUpdate[A]): A = {
    val (next, data) = update(processingState)
    processingState = next
    if (next.contentInArchive) {
      updateArchiveState(HttpArchiveStateConnected)
    }
    data
  }

  /**
    * Performs a state update using the specified update function and executes
    * the actions triggered by this transition.
    *
    * @param update the update function
    */
  private def updateStateWithTransitions(update: ContentProcessingUpdateServiceImpl.
  StateUpdate[ProcessingStateTransitionData]): Unit = {
    val transitionData = updateState(update)
    transitionData.propagateMsg foreach (propagationActor ! _)
    transitionData.actorToAck foreach (_ ! HttpArchiveMediumAck)
  }

  /**
    * Updates the current archive state. If there are clients waiting for a
    * state notification, they are notified now.
    *
    * @param state the new state
    */
  private def updateArchiveState(state: HttpArchiveState): Unit = {
    log.debug("Next archive state: {}.", state)
    val response = HttpArchiveStateResponse(config.archiveName, state)
    archiveStateResponse = Some(response)

    pendingStateClients foreach (_ ! response)
    pendingStateClients = Set.empty
  }

  /**
    * Creates the actor to be used for HTTP requests. This actor depends on the
    * encrypted flag of the current archive.
    *
    * @return the actor to execute HTTP requests
    */
  private def createRequestActor(): ActorRef = {
    val requestActorProps = if (config.protocol.requiresMultiHostSupport)
      HttpMultiHostRequestActor(MultiHostCacheSize, config.requestQueueSize)
    else HttpRequestActor(config)
    val plainRequestActor = createChildActor(requestActorProps)

    val cookieActor = if (config.needsCookieManagement) createChildActor(HttpCookieManagementActor(plainRequestActor))
    else plainRequestActor
    val decoratedRequestActor = config.authFunc(cookieActor, this)
    optCryptKey match {
      case Some(key) =>
        val archiveBasePath = UriHelper.extractParent(config.archiveURI.path.toString())
        log.info("Creating request actor for encrypted archive, base path is {}.", archiveBasePath)
        val resolverActor =
          createChildActor(Props(classOf[UriResolverActor], decoratedRequestActor, config.protocol, key,
            archiveBasePath, config.cryptUriCacheSize))
        createChildActor(Props(classOf[CryptHttpRequestActor], resolverActor, decoratedRequestActor, key,
          config.processorTimeout))
      case None => decoratedRequestActor
    }
  }
}
