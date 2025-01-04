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

package de.oliver_heger.linedj.archivehttp.impl.download

import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.temp.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import java.nio.file.Path
import scala.collection.immutable.Queue
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object TimeoutAwareHttpDownloadActor:
  /**
    * Creates a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param config               the configuration of the HTTP archive
    * @param downloadManagerActor the download manager actor
    * @param downloadRequest      the request to download a media file
    * @param transformFunc        the transformation function to be applied by
    *                             the wrapped download actor
    * @param pathGenerator        an object to generate paths for temporary files
    * @param removeFileActor      helper actor to remove files
    * @param downloadIndex        the index of this download operation
    * @return ''Props'' to create a new actor instance
    */
  def apply(config: HttpArchiveConfig, downloadManagerActor: ActorRef,
            downloadRequest: MediumFileRequest, transformFunc: MediaFileDownloadActor.DownloadTransformFunc,
            pathGenerator: TempPathGenerator, removeFileActor: ActorRef, downloadIndex: Int): Props =
    Props(classOf[TimeoutAwareHttpDownloadActorImpl], config, downloadManagerActor,
      downloadRequest, transformFunc, pathGenerator, removeFileActor, downloadIndex, None)

  /** A delay that is used when resuming a failed download. */
  private val RetryDelay = 1.second

  private class TimeoutAwareHttpDownloadActorImpl(config: HttpArchiveConfig,
                                                  downloadManagerActor: ActorRef,
                                                  downloadRequest: MediumFileRequest,
                                                  transformFunc: MediaFileDownloadActor.DownloadTransformFunc,
                                                  pathGenerator: TempPathGenerator,
                                                  removeFileActor: ActorRef,
                                                  downloadIndex: Int,
                                                  optTempManager: Option[TempFileActorManager])
    extends TimeoutAwareHttpDownloadActor(config, downloadManagerActor, downloadRequest, transformFunc,
      pathGenerator, removeFileActor, downloadIndex, optTempManager)
      with SchedulerSupport

  /**
    * An internal message indicating an inactivity during a download operation.
    * This message is generated if no requests are received from the client
    * actor for a given time interval. The actor then has to become active
    * itself to prevent the HTTP connection from being closed.
    */
  private case object InactivityTimeout

  /**
    * Checks whether a download result actually contains data. If this is not
    * the case, that means that multiple data requests have been sent in
    * parallel. (This can happen during timeout handling.) Then this result is
    * to be ignored.
    *
    * @param res the ''DownloadDataResult''
    * @return a flag whether data is contained in the result
    */
  private def dataDefined(res: DownloadDataResult): Boolean = res.data.nonEmpty

/**
  * An actor which implements the download actor protocol for downloads from an
  * HTTP archive.
  *
  * This actor class wraps a plain download actor which has been created based
  * on the passed in request to download a media file. Per default, data
  * requested by clients is fetched from this actor.
  *
  * If there are no client requests received for a configurable time,
  * however, the actor reads data on its own and buffers it in-memory to avoid
  * a timeout of the HTTP connection. If the in-memory buffer becomes too
  * large, temporary files are created to hold the data until the client sends
  * requests again.
  *
  * Despite of this mechanism, a server may close the connection if a download
  * takes too long. This actor therefore implements a retry logic, so that a
  * failed download is resumed if the data stored in temporary files is
  * exhausted.
  *
  * @param config               the configuration of the HTTP archive
  * @param downloadManagerActor the download manager actor
  * @param downloadRequest      the request to download a media file
  * @param transformFunc        the transformation function to be applied by
  *                             the wrapped download actor
  * @param pathGenerator        an object to generate paths for temporary files
  * @param removeFileActor      helper actor to remove files
  * @param downloadIndex        the index of this download operation
  * @param optTempManager       an optional manager for temporary files; this is
  *                             used for testing purposes
  */
class TimeoutAwareHttpDownloadActor(config: HttpArchiveConfig,
                                    downloadManagerActor: ActorRef,
                                    downloadRequest: MediumFileRequest,
                                    transformFunc: MediaFileDownloadActor.DownloadTransformFunc,
                                    pathGenerator: TempPathGenerator,
                                    removeFileActor: ActorRef,
                                    downloadIndex: Int,
                                    optTempManager: Option[TempFileActorManager])
  extends Actor with ChildActorFactory with ActorLogging with TempReadOperationHolder:
  this: SchedulerSupport =>

  import TimeoutAwareHttpDownloadActor._

  /** The object that manages temporary files. */
  private[download] var tempFileActorManager: TempFileActorManager = _

  /**
    * The alive notification to be sent to the download manager on receiving a
    * request.
    */
  private var downloadActorAliveMsg: DownloadActorAlive = _

  /**
    * Stores the underlying download actor. It is created based on the file to
    * be downloaded.
    */
  private var downloadFileActor: ActorRef = _

  /**
    * An object for buffering data if the client does not send requests with
    * the desired speed.
    */
  private var downloadBuffer = DownloadBuffer.empty

  /** The actor for writing temporary files. */
  private var writeFileActor: ActorRef = _

  /** Stores the object to cancel the inactivity timeout scheduler. */
  private var cancellable: Option[Cancellable] = None

  /** Stores information about an ongoing request. */
  private var currentRequest: Option[DownloadRequestData] = None

  /**
    * Stores an ''Option'' with the current operation to read data from a
    * temporary file.
    */
  var readOperation: Option[TempReadOperation] = None

  /**
    * Stores the paths of the temporary files that have been written during
    * this download operation.
    */
  private var tempFilesWritten = Queue.empty[Path]

  /** A counter for the bytes that have already been received in total. */
  private var bytesReceived = 0L

  /**
    * A counter for the bytes that have been received from the current download
    * actor. This counter is reset every time a new download actor is created
    * (i.e. when a failed download is resumed). The whole download is
    * considered failed if from a download actor no bytes are received.
    */
  private var bytesReceivedFromActor = 0L

  /**
    * A counter for the number of bytes that need to be read when an inactivity
    * timeout was encountered.
    */
  private var bytesToReadDuringTimeout = 0

  /** The index of temporary files to be written. */
  private var tempFileIndex = 1

  /** Flag whether the end of the download has been reached. */
  private var downloadComplete = false

  override def currentReadOperation: Option[TempReadOperation] = readOperation

  override def getOrCreateCurrentReadOperation(optPath: => Option[Path]): Option[TempReadOperation] =
    readOperation = currentReadOperation orElse:
      optPath map { path =>
        val readActor = createAndWatchChildActor(Props(classOf[MediaFileDownloadActor], path,
          config.downloadReadChunkSize, MediaFileDownloadActor.IdentityTransform))
        TempReadOperation(readActor, path)
      }
    readOperation

  override def resetReadOperation(): Unit =
    readOperation = None

  override def preStart(): Unit =
    downloadActorAliveMsg = DownloadActorAlive(self, MediaFileID(MediumID.UndefinedMediumID, ""))
    tempFileActorManager = optTempManager getOrElse new TempFileActorManager(self, this)
    downloadFileActor = createChildDownloadActor(0)
    context watch downloadFileActor

  override def receive: Receive =
    case req: DownloadData if currentRequest.isEmpty =>
      if !tempFileActorManager.initiateClientRequest(sender(), req) then
        serveRequestFromBuffer(req, sender())
      downloadManagerActor ! downloadActorAliveMsg

    case res: DownloadDataResult if messageFromReaderActor() =>
      tempFileActorManager downloadResultArrived res

    case res: DownloadDataResult if dataDefined(res) =>
      // response from the wrapped download actor
      handleDataFromDownloadActor(res)

    case DownloadComplete if messageFromReaderActor() =>
      // a temp file reader actor is done
      tempFileActorManager.downloadCompletedArrived() foreach handleCompletedReadOperation

    case DownloadComplete =>
      downloadComplete = true
      currentRequest foreach (_.client ! DownloadComplete)
      resetScheduler()

    case InactivityTimeout if !downloadComplete && cancellable.isDefined =>
      log.info("Inactivity timeout. Requesting {} bytes of data.", config.timeoutReadSize)
      bytesToReadDuringTimeout = config.timeoutReadSize
      readDuringTimeout()
      cancellable = None

    case resp: WriteChunkActor.WriteResponse =>
      tempFileActorManager tempFileWritten resp
      tempFilesWritten = tempFilesWritten enqueue resp.request.target

    case Terminated(actor) if actor == downloadFileActor && bytesReceivedFromActor > 0 =>
      handleFailedResumableDownload()

    case Terminated(actor) if actor == downloadFileActor =>
      log.error("Download actor was terminated without sending data. Canceling download operation {}.",
        downloadIndex)
      context stop self

    case Terminated(_) =>
      // A write operation failed => stop this download actor
      log.error("Writing data to a temp file failed! Canceling download operation {}.", downloadIndex)
      context stop self

  /**
    * @inheritdoc This implementation does some cleanup: The wrapped download
    *             actor is stopped, and temporary files that have been written
    *             are removed.
    */
  override def postStop(): Unit =
    val tempPaths = tempFileActorManager.pendingTempPaths
    if tempPaths.nonEmpty then
      removeFileActor ! RemoveTempFilesActor.RemoveTempFiles(tempPaths)
    resetScheduler()
    context stop downloadFileActor
    super.postStop()

  /**
    * Creates a child actor using the specified ''Props'' and initiates death
    * watch. This actor always needs to react if a child dies.
    *
    * @param p the creation properties
    * @return the newly created actor
    */
  private def createAndWatchChildActor(p: Props): ActorRef =
    val child = createChildActor(p)
    context watch child
    child

  /**
    * Creates a new download actor which performs the actual download.
    *
    * @param bytesToSkip the number of bytes to skip from the download
    * @return the new download actor
    */
  private def createChildDownloadActor(bytesToSkip: Long): ActorRef =
    createAndWatchChildActor(HttpDownloadActor(config, downloadRequest, transformFunc, bytesToSkip))

  /**
    * Handles a chunk of data received from the wrapped download actor. The
    * data is added to the buffer, and a pending request - if any - is served.
    * This method also takes care about timeout handling. If a timeout
    * occurred, it may be necessary to request another block of data.
    * Otherwise, a new timeout message needs to be scheduled.
    *
    * @param res the download data result
    */
  private def handleDataFromDownloadActor(res: DownloadDataResult): Unit =
    bytesReceived += res.data.size
    bytesReceivedFromActor += res.data.size
    downloadBuffer = downloadBuffer addChunk res.data
    currentRequest foreach { cd =>
      val (optData, buf) = downloadBuffer fetchData cd.request.size
      assert(optData.isDefined)
      cd.client ! DownloadDataResult(optData.get)
      downloadBuffer = buf
      currentRequest = None
    }
    if downloadBuffer.size >= config.downloadBufferSize then
      writeTempFile()
    bytesToReadDuringTimeout = math.max(0, bytesToReadDuringTimeout - res.data.size)
    if !readDuringTimeout() then
      resetScheduler()
      scheduleForInactivityTimeout(config.downloadMaxInactivity)

  /**
    * Handles a download request that cannot be handled by the temp file
    * manager. Tries to return data from the in-memory buffer or sends the
    * request to the wrapped download actor.
    *
    * @param req    the request
    * @param client the requesting client
    */
  private def serveRequestFromBuffer(req: DownloadData, client: ActorRef): Unit =
    downloadBuffer fetchData req.size match
      case (Some(bs), buf) =>
        client ! DownloadDataResult(bs)
        downloadBuffer = buf
        if shouldResumeFailedDownload() then
          resumeFailedDownload()

      case (None, _) =>
        if downloadComplete then
          client ! DownloadComplete
        else
          downloadFileActor ! req
          currentRequest = Some(DownloadRequestData(req, client))

  /**
    * Writes a temporary file whose content is the current buffer.
    */
  private def writeTempFile(): Unit =
    log.info("Creating new temporary file for download {}.", downloadIndex)
    fetchWriteActor() ! createWriteFileRequest()
    tempFileActorManager.pendingWriteOperation(tempFileIndex)
    tempFileIndex += 1
    downloadBuffer = DownloadBuffer.empty

  /**
    * Creates a request to write a temporary file.
    *
    * @return the request message
    */
  private def createWriteFileRequest(): WriteChunkActor.WriteRequest =
    WriteChunkActor.WriteRequest(
      pathGenerator.generateDownloadPath(config.archiveName, downloadIndex, tempFileIndex),
      Source[ByteString](downloadBuffer.chunks.toList), tempFileIndex)

  /**
    * Handles a completed read operation of a temporary file. If necessary, a
    * pending request has to be served. The file that has been read and its
    * reader actor can now be cleaned up.
    *
    * @param op an object describing the read operation
    */
  private def handleCompletedReadOperation(op: CompletedTempReadOperation): Unit =
    op.pendingRequest foreach { r =>
      serveRequestFromBuffer(r.request, r.client)
    }

    removeFileActor ! RemoveTempFilesActor.RemoveTempFiles(List(op.operation.path))
    context unwatch op.operation.reader
    context stop op.operation.reader

  /**
    * Triggers a read operation after an inactivity timeout has been
    * encountered. Another block of data is requested from the wrapped actor.
    *
    * @return a flag whether there was still data to read
    */
  private def readDuringTimeout(): Boolean =
    if bytesToReadDuringTimeout > 0 then
      val chunkSize = math.min(bytesToReadDuringTimeout, config.downloadReadChunkSize)
      downloadFileActor ! DownloadData(chunkSize)
      true
    else false

  /**
    * Schedules a message to receive a notification when an inactivity timeout
    * occurs. In this case, the actor has to query another chunk from the
    * wrapped download actor. This message is scheduled periodically during a
    * normal download operation. Via this message, a failed download is resumed
    * as well.
    *
    * @param delay the delay after which the timeout message is scheduled
    */
  private def scheduleForInactivityTimeout(delay: FiniteDuration): Unit =
    cancellable = Some(scheduleMessageOnce(delay, self, InactivityTimeout))

  /**
    * Resets a scheduled message if any.
    */
  private def resetScheduler(): Unit =
    cancellable foreach (_.cancel())
    cancellable = None

  /**
    * Returns the write file actor. It is created on demand.
    *
    * @return the write file actor
    */
  private def fetchWriteActor(): ActorRef =
    if writeFileActor == null then
      writeFileActor = createAndWatchChildActor(Props[WriteChunkActor]())
    writeFileActor

  /**
    * Checks whether the sender of the current message is the reader actor for
    * temporary files.
    *
    * @return a flag whether the message is from the reader actor
    */
  private def messageFromReaderActor(): Boolean =
    currentReadOperation.exists(_.reader == sender())

  /**
    * Handles a failed download that can be resumed. This function is called if
    * the current download actor died after it has sent some data. The
    * expectation is then that the server might have closed the connection
    * because the download operation took too long. The operation will be
    * retried either directly (if no temporary files are present) or later,
    * when the in-memory buffer is accessed.
    */
  private def handleFailedResumableDownload(): Unit =
    log.error("Download actor was terminated after sending {} bytes of data in download operation {}.",
      bytesReceivedFromActor, downloadIndex)
    downloadFileActor = createChildDownloadActor(bytesReceived)
    bytesReceivedFromActor = 0
    resetScheduler()
    if tempFileActorManager.pendingTempPaths.isEmpty then
      resumeFailedDownload()

  /**
    * Returns a flag whether the current download has failed and should now be
    * resumed. This is a rather complex condition. If a download has failed,
    * there is no active schedule for inactivity timeouts and bytes from the
    * download have already been received. Also, the download is not resumed
    * until all temporary files have been processed.
    *
    * @return a flag whether to resume the current download now
    */
  private def shouldResumeFailedDownload(): Boolean =
    cancellable.isEmpty && bytesReceived > 0 && !downloadComplete && tempFileActorManager.pendingTempPaths.isEmpty

  /**
    * Triggers the necessary actions to resume a failed download. This
    * basically means that the scheduler for inactivity timeouts is activated
    * again.
    */
  private def resumeFailedDownload(): Unit =
    scheduleForInactivityTimeout(RetryDelay)
    log.info("Resuming failed download {}, skipping {} bytes of data.", downloadIndex, bytesReceived)
