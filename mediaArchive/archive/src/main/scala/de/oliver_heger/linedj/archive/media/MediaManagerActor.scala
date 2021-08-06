/*
 * Copyright 2015-2021 The Developers Team.
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

import java.nio.file.Paths

import akka.actor._
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archivecommon.download.{DownloadMonitoringActor, MediaFileDownloadActor}
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.extract.id3.processor.ID3v2ProcessingStage
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io._
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.metadata.GetMetaDataFileInfo
import de.oliver_heger.linedj.shared.archive.union.{MediaFileUriHandler, RemovedArchiveComponentProcessed}
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import scalaz.State

/**
  * Companion object.
  */
object MediaManagerActor {
  /**
    * Constant for a prototype of a ''MediumFiles'' message for an unknown
    * medium.
    */
  private val UnknownMediumFiles = MediumFiles(null, Set.empty, existing = false)

  /**
    * Constant for a ''FileData'' referring to a non-existing file.
    */
  private val NonExistingFile = FileData(path = null, size = -1)

  private class MediaManagerActorImpl(config: MediaArchiveConfig, metaDataManager: ActorRef,
                                      mediaUnionActor: ActorRef, groupManager: ActorRef)
    extends MediaManagerActor(config, metaDataManager, mediaUnionActor, groupManager) with ChildActorFactory
      with SchedulerSupport with CloseSupport

  /**
    * Creates a ''Props'' object for creating new actor instances of this class.
    * Client code should always use the ''Props'' object returned by this
    * method; it ensures that all dependencies have been resolved.
    *
    * @param config          the configuration object
    * @param metaDataManager a reference to the meta data manager actor
    * @param mediaUnionActor reference to the media union actor
    * @param groupManager    a reference to the group manager actor
    * @return a ''Props'' object for creating actor instances
    */
  def apply(config: MediaArchiveConfig, metaDataManager: ActorRef,
            mediaUnionActor: ActorRef, groupManager: ActorRef): Props =
    Props(classOf[MediaManagerActorImpl], config, metaDataManager, mediaUnionActor, groupManager)

  /**
    * The transformation function to remove meta data from a file to be
    * downloaded.
    *
    * @return the download transformation function
    */
  private def downloadTransformationFunc: MediaFileDownloadActor.DownloadTransformFunc = {
    case s if s matches "(?i)mp3" =>
      new ID3v2ProcessingStage(None)
  }
}

/**
  * A specialized actor implementation for managing the media currently
  * available in the system.
  *
  * This actor can be triggered to scan an arbitrary number of directories for
  * media files. During this scan process medium description files are detected;
  * they are used to identify media and collect their content. (A medium can be
  * on a drive which can be replaced, e.g. a CD-ROM or a USB stick. It is also
  * possible that multiple media are stored under a root directory structure on
  * a hard disk.)
  *
  * After the scan operation is complete, the list with available media can be
  * queried. With this information, client applications can select the audio
  * data to be played. The content of specific media can be queried, and single
  * audio sources can be requested.
  *
  * @param config                 the configuration object
  * @param metaDataManager        a reference to the meta data manager actor
  * @param mediaUnionActor        a reference to the media union actor
  * @param groupManager           a reference to the group manager actor
  * @param scanStateUpdateService the service to update the scan state
  */
class MediaManagerActor(config: MediaArchiveConfig, metaDataManager: ActorRef,
                        mediaUnionActor: ActorRef, groupManager: ActorRef,
                        private[media] val scanStateUpdateService: MediaScanStateUpdateService)
  extends Actor with ActorLogging {
  me: ChildActorFactory with CloseSupport =>

  /**
    * Creates a new instance of ''MediaManagerActor'' with default
    * dependencies. This constructor is used in production, while the other one
    * is for testing purposes.
    *
    * @param config          the configuration object
    * @param metaDataManager a reference to the meta data manager actor
    * @param mediaUnionActor a reference to the media union actor
    * @param groupManager    a reference to the group manager actor
    */
  def this(config: MediaArchiveConfig, metaDataManager: ActorRef,
           mediaUnionActor: ActorRef, groupManager: ActorRef) =
    this(config, metaDataManager, mediaUnionActor, groupManager, MediaScanStateUpdateServiceImpl)

  import MediaManagerActor._

  /** A helper object for parsing medium description files. */
  private val mediumInfoParser = new MediumInfoParser

  /** The actor for parsing media description files. */
  private var mediumInfoParserActor: ActorRef = _

  /** The actor for scanning media directory structures. */
  private var mediaScannerActor: ActorRef = _

  /** The actor that manages download operations. */
  private var downloadManagerActor: ActorRef = _

  /**
    * The current state of the this actor. This state keeps track about ongoing
    * scan operations and about accumulated scan results.
    */
  private var scanState = MediaScanStateUpdateServiceImpl.InitialState

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    mediumInfoParserActor = createChildActor(Props(classOf[MediumInfoParserActor],
      mediumInfoParser, config.infoSizeLimit))
    mediaScannerActor = createChildActor(MediaScannerActor(config.archiveName,
      config.excludedFileExtensions, config.includedFileExtensions,
      config.scanMediaBufferSize, mediumInfoParserActor, config.infoParserTimeout))
    downloadManagerActor = createChildActor(DownloadMonitoringActor(config.downloadConfig))
  }

  override def receive: Receive = {
    case ScanAllMedia =>
      groupManager ! ScanAllMedia

    case StartMediaScan =>
      handleScanRequest()

    case GetMediumFiles(mediumID) =>
      val optResponse = scanState.fileData.get(mediumID) map
        (files => MediumFiles(mediumID, files.keySet, existing = true))
      sender() ! optResponse.getOrElse(UnknownMediumFiles.copy(mediumID = mediumID))

    case request: MediumFileRequest =>
      processFileRequest(request)

    case RemovedArchiveComponentProcessed(compID)
      if config.archiveName == compID =>
      updateStateAndSendMessages(scanStateUpdateService
        .handleRemovedFromUnionArchive(config.archiveName))

    case MetaDataManagerActor.ScanResultProcessed if sender() == metaDataManager =>
      updateStateAndSendMessages(scanStateUpdateService
        .handleAckFromMetaManager(config.archiveName))

    case res: ScanSinkActor.CombinedResults =>
      updateStateAndSendMessages(scanStateUpdateService.handleResultsReceived(res, sender(),
        config.archiveName))

    case MediaScannerActor.PathScanCompleted(request) =>
      updateStateAndSendMessages(scanStateUpdateService.handleScanComplete(request.seqNo,
        config.archiveName))

    case GetMetaDataFileInfo =>
      metaDataManager forward GetMetaDataFileInfo

    case CloseRequest =>
      onCloseRequest(self, List(metaDataManager), sender(), me)
      mediaScannerActor ! AbstractStreamProcessingActor.CancelStreams

    case CloseComplete =>
      updateStateAndSendMessages(scanStateUpdateService.handleScanCanceled())
      onCloseComplete()
  }

  /**
    * Updates the state managed by this actor and returns the additional
    * value produced by this transition.
    *
    * @param state the state monad for the update operation
    * @tparam A the type of the additional result
    * @return the result produced by the state update
    */
  private def updateState[A](state: State[MediaScanState, A]): A = {
    val (next, res) = state(scanState)
    scanState = next
    res
  }

  /**
    * Updates the state managed by this actor and sends the resulting
    * transition messages to the correct actors.
    *
    * @param state the state monad for the update operation
    */
  private def updateStateAndSendMessages(state: State[MediaScanState, ScanStateTransitionMessages]): Unit = {
    val messages = updateState(state)
    messages.unionArchiveMessage foreach mediaUnionActor.!
    messages.metaManagerMessage foreach metaDataManager.!
    messages.ack foreach (_ ! ScanSinkActor.Ack)
  }

  /**
    * Handles a request to start a new scan operation.
    */
  private def handleScanRequest(): Unit = {
    val scanMsg = updateState(scanStateUpdateService.triggerStartScan(config.rootPath, sender()))
    scanMsg foreach { m =>
      mediaScannerActor ! m
      updateStateAndSendMessages(scanStateUpdateService.startScanMessages(config.archiveName))
    }
  }

  /**
    * Processes the request for a medium file.
    *
    * @param request the file request
    */
  private def processFileRequest(request: MediumFileRequest): Unit = {
    val response = fetchFileData(request) match {
      case Some(fileData) =>
        val transFunc = if (request.withMetaData) MediaFileDownloadActor.IdentityTransform
        else downloadTransformationFunc
        val downloadActor = createChildActor(Props(classOf[MediaFileDownloadActor],
          Paths get fileData.path, config.downloadConfig.downloadChunkSize, transFunc))
        downloadManagerActor !
          DownloadMonitoringActor.DownloadOperationStarted(downloadActor, sender())
        MediumFileResponse(request, Some(downloadActor), fileData.size)

      case None =>
        MediumFileResponse(request, None, NonExistingFile.size)
    }

    sender() ! response
  }

  /**
    * Obtains the ''FileData'' object referred to by the given
    * ''MediumFileRequest''. The file is looked up in the data structures managed by
    * this actor. If it cannot be found, result is ''None''.
    *
    * @param request the request identifying the desired file
    * @return an option with the ''FileData''
    */
  private def fetchFileData(request: MediumFileRequest): Option[FileData] = {
    MediaFileUriHandler.resolveUri(request.fileID.mediumID, request.fileID.uri,
      scanState.fileData)
  }

}
