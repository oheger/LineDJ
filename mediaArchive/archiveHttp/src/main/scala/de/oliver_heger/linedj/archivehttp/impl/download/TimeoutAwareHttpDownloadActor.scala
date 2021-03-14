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

package de.oliver_heger.linedj.archivehttp.impl.download

import java.nio.file.Path

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.temp.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}

import scala.collection.immutable.Queue

object TimeoutAwareHttpDownloadActor {
  /**
    * Creates a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param config               the configuration of the HTTP archive
    * @param downloadManagerActor the download manager actor
    * @param downloadFileActor    the actor with the data to be downloaded
    * @param pathGenerator        an object to generate paths for temporary files
    * @param removeFileActor      helper actor to remove files
    * @param downloadIndex        the index of this download operation
    * @return ''Props'' to create a new actor instance
    */
  def apply(config: HttpArchiveConfig, downloadManagerActor: ActorRef,
            downloadFileActor: ActorRef, pathGenerator: TempPathGenerator,
            removeFileActor: ActorRef, downloadIndex: Int): Props =
    Props(classOf[TimeoutAwareHttpDownloadActorImpl], config, downloadManagerActor,
      downloadFileActor, pathGenerator, removeFileActor, downloadIndex, None)

  /**
    * Constant for the minimum delay between two timeout messages. There can
    * be a race condition when a scheduled timeout message is canceled, but
    * the message has already been put in this actor's queue. To avoid this,
    * another timeout message that is received within this interval is simply
    * ignored.
    */
  private val MinimumTimeoutDelay = 3000

  private class TimeoutAwareHttpDownloadActorImpl(config: HttpArchiveConfig,
                                                  downloadManagerActor: ActorRef,
                                                  downloadFileActor: ActorRef,
                                                  pathGenerator: TempPathGenerator,
                                                  removeFileActor: ActorRef,
                                                  downloadIndex: Int,
                                                  optTempManager: Option[TempFileActorManager])
    extends TimeoutAwareHttpDownloadActor(config, downloadManagerActor, downloadFileActor,
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
}

/**
  * An actor which implements the download actor protocol for downloads from an
  * HTTP archive.
  *
  * This actor class wraps a plain download actor which has been initialized
  * with a data source from an HTTP request. Per default, data requested by
  * clients is fetched from this actor.
  *
  * If there are no client requests received for a configuration time,
  * however, the actor reads data on its own and buffers it in-memory to avoid
  * a timeout of the HTTP connection. If the in-memory buffer becomes too
  * large, temporary files are created to hold the data until the client sends
  * requests again.
  *
  * @param config               the configuration of the HTTP archive
  * @param downloadManagerActor the download manager actor
  * @param downloadFileActor    the actor with the data to be downloaded
  * @param pathGenerator        an object to generate paths for temporary files
  * @param removeFileActor      helper actor to remove files
  * @param downloadIndex        the index of this download operation
  * @param optTempManager       an optional manager for temporary files; this is
  *                             used for testing purposes
  */
class TimeoutAwareHttpDownloadActor(config: HttpArchiveConfig, downloadManagerActor: ActorRef,
                                    downloadFileActor: ActorRef, pathGenerator: TempPathGenerator,
                                    removeFileActor: ActorRef, downloadIndex: Int,
                                    optTempManager: Option[TempFileActorManager])
  extends Actor with ChildActorFactory with ActorLogging {
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
    * Stores the paths of the temporary files that have been written during
    * this download operation.
    */
  private var tempFilesWritten = Queue.empty[Path]

  /** Timestamp when the last timeout message was received. */
  private var lastTimeoutMessage = 0L

  /**
    * A counter for the number of bytes that need to be read when an inactivity
    * timeout was encountered.
    */
  private var bytesToReadDuringTimeout = 0

  /** The index of temporary files to be written. */
  private var tempFileIndex = 1

  /** Flag whether the end of the download has been reached. */
  private var downloadComplete = false

  override def preStart(): Unit = {
    downloadActorAliveMsg = DownloadActorAlive(self, MediaFileID(MediumID.UndefinedMediumID, ""))
    tempFileActorManager = optTempManager getOrElse new TempFileActorManager(self,
      config.downloadReadChunkSize, this)
    context watch downloadFileActor
    scheduleForInactivityTimeout()
  }

  override def receive: Receive = {
    case req: DownloadData if currentRequest.isEmpty =>
      if (!tempFileActorManager.initiateClientRequest(sender(), req)) {
        serveRequestFromBuffer(req, sender())
      }
      downloadManagerActor ! downloadActorAliveMsg

    case res: DownloadDataResult if sender() == downloadFileActor && dataDefined(res) =>
      handleDataFromDownloadActor(res)

    case res: DownloadDataResult if sender() != downloadFileActor =>
      // response from a temp file reader actor
      tempFileActorManager downloadResultArrived res

    case DownloadComplete if sender() == downloadFileActor =>
      downloadComplete = true
      currentRequest foreach (_.client ! DownloadComplete)
      resetScheduler()

    case DownloadComplete if sender() != downloadFileActor =>
      // a temp file reader actor is done
      tempFileActorManager.downloadCompletedArrived() foreach handleCompletedReadOperation

    case InactivityTimeout if !downloadComplete &&
      System.currentTimeMillis() - lastTimeoutMessage > MinimumTimeoutDelay =>
      log.info("Inactivity timeout. Requesting {} bytes of data.", config.timeoutReadSize)
      bytesToReadDuringTimeout = config.timeoutReadSize
      readDuringTimeout()
      lastTimeoutMessage = System.currentTimeMillis()
      cancellable = None

    case resp: WriteChunkActor.WriteResponse =>
      tempFileActorManager tempFileWritten resp
      tempFilesWritten = tempFilesWritten enqueue resp.request.target

    case Terminated(_) =>
      // A write operation or the wrapped actor failed => stop this download actor
      log.warning("Child actor or download actor stopped! Canceling download operation {}.",
        downloadIndex)
      context stop self
  }

  /**
    * @inheritdoc This implementation does some cleanup: The wrapped download
    *             actor is stopped, and temporary files that have been written
    *             are removed.
    */
  override def postStop(): Unit = {
    val tempPaths = tempFileActorManager.pendingTempPaths
    if (tempPaths.nonEmpty) {
      removeFileActor ! RemoveTempFilesActor.RemoveTempFiles(tempPaths)
    }
    resetScheduler()
    context stop downloadFileActor
    super.postStop()
  }

  /**
    * @inheritdoc This implementation creates a child actor and starts
    *             watching it. If the actor crashes, the current download
    *             operation will fail. (This actor then stops itself.)
    */
  override def createChildActor(p: Props): ActorRef = {
    val child = super.createChildActor(p)
    context watch child
    child
  }

  /**
    * Handles a chunk of data received from the wrapped download actor. The
    * data is added to the buffer, and a pending request - if any - is served.
    * This method also takes care about timeout handling. If a timeout
    * occurred, it may be necessary to request another block of data.
    * Otherwise, a new timeout message needs to be scheduled.
    *
    * @param res the download data result
    */
  private def handleDataFromDownloadActor(res: DownloadDataResult): Unit = {
    downloadBuffer = downloadBuffer addChunk res.data
    currentRequest foreach { cd =>
      val (optData, buf) = downloadBuffer fetchData cd.request.size
      assert(optData.isDefined)
      cd.client ! DownloadDataResult(optData.get)
      downloadBuffer = buf
      currentRequest = None
    }
    if (downloadBuffer.size >= config.downloadBufferSize) {
      writeTempFile()
    }
    bytesToReadDuringTimeout = math.max(0, bytesToReadDuringTimeout - res.data.size)
    if (!readDuringTimeout()) {
      resetScheduler()
      scheduleForInactivityTimeout()
    }
  }

  /**
    * Handles a download request that cannot be handled by the temp file
    * manager. Tries to return data from the in-memory buffer or sends the
    * request to the wrapped download actor.
    *
    * @param req    the request
    * @param client the requesting client
    */
  private def serveRequestFromBuffer(req: DownloadData, client: ActorRef): Unit = {
    downloadBuffer fetchData req.size match {
      case (Some(bs), buf) =>
        client ! DownloadDataResult(bs)
        downloadBuffer = buf
      case (None, _) =>
        if (downloadComplete) {
          client ! DownloadComplete
        } else {
          downloadFileActor ! req
          currentRequest = Some(DownloadRequestData(req, client))
        }
    }
  }

  /**
    * Writes a temporary file whose content is the current buffer.
    */
  private def writeTempFile(): Unit = {
    log.info("Creating new temporary file for download {}.", downloadIndex)
    fetchWriteActor() ! createWriteFileRequest()
    tempFileActorManager.pendingWriteOperation(tempFileIndex)
    tempFileIndex += 1
    downloadBuffer = DownloadBuffer.empty
  }

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
  private def handleCompletedReadOperation(op: CompletedTempReadOperation): Unit = {
    op.pendingRequest foreach { r =>
      serveRequestFromBuffer(r.request, r.client)
    }

    removeFileActor ! RemoveTempFilesActor.RemoveTempFiles(List(op.operation.path))
    context unwatch op.operation.reader
    context stop op.operation.reader
  }

  /**
    * Triggers a read operation after an inactivity timeout has been
    * encountered. Another block of data is requested from the wrapped actor.
    *
    * @return a flag whether there was still data to read
    */
  private def readDuringTimeout(): Boolean =
    if (bytesToReadDuringTimeout > 0) {
      val chunkSize = math.min(bytesToReadDuringTimeout, config.downloadReadChunkSize)
      downloadFileActor ! DownloadData(chunkSize)
      true
    } else false

  /**
    * Schedules a message to receive a notification when an inactivity timeout
    * occurs. In this case, the actor has to query another chunk from the
    * wrapped download actor.
    */
  private def scheduleForInactivityTimeout(): Unit = {
    cancellable = Some(scheduleMessageOnce(config.downloadMaxInactivity, self,
      InactivityTimeout))
  }

  /**
    * Resets a scheduled message if any.
    */
  private def resetScheduler(): Unit = {
    cancellable foreach (_.cancel())
    cancellable = None
  }

  /**
    * Returns the write file actor. It is created on demand.
    *
    * @return the write file actor
    */
  private def fetchWriteActor(): ActorRef = {
    if (writeFileActor == null) {
      writeFileActor = createChildActor(Props[WriteChunkActor])
    }
    writeFileActor
  }
}
