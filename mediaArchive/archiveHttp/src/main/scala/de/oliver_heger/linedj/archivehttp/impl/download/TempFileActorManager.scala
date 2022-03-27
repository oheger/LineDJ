/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor
import de.oliver_heger.linedj.archivehttp.impl.download.TempFileActorManager.TempFileData
import de.oliver_heger.linedj.shared.archive.media.{DownloadData, DownloadDataResult}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.collection.SortedSet
import scala.collection.immutable.TreeSet

object TempFileActorManager {

  /**
    * An internally used data class to store information about a temporary file
    * to be processed.
    *
    * @param path  the path of the file
    * @param seqNo the sequence number of the download file
    */
  private case class TempFileData(path: Path, seqNo: Int) extends Ordered[TempFileData] {
    /**
      * @inheritdoc This implementation compares objects by their sequence
      *             number. This is used to ensure that temporary files are
      *             processed in correct order.
      */
    override def compare(that: TempFileData): Int = seqNo - that.seqNo
  }

}

/**
  * An internally used helper class that manages information about temporary
  * files created during a download operation and the actors that are used to
  * read them.
  *
  * If a client of a download operation is too slow, data downloaded from an
  * HTTP archive has to be buffered. This is done first in memory, and then -
  * if the buffer becomes too large - data is flushed to the local hard disk.
  * To read this data again and provide it to the requesting client, actors
  * for reading files are used.
  *
  * This class keeps track on the paths to temporary files that have been
  * written during the current download operation. It offers methods to access
  * data and to update the state when new temporary files are added.
  *
  * @param downloadActor the actor controlling the current download
  * @param readChunkSize the chunk size for read file operations
  * @param actorFactory  the factory for creating child actors
  */
private class TempFileActorManager(val downloadActor: ActorRef,
                                   val readChunkSize: Int,
                                   val actorFactory: ChildActorFactory) {
  /** A set with write operations that are currently pending. */
  private var pendingWrites: SortedSet[Int] = TreeSet.empty

  /** Stores the current download data request. */
  private var currentRequest: Option[DownloadRequestData] = None

  /** Stores the currently processed reader actor. */
  private var currentReader: Option[TempReadOperation] = None

  /** Stores the temporary files to be processed by this object. */
  private var temporaryFiles: SortedSet[TempFileData] = TreeSet.empty

  /**
    * Returns an ''Iterable'' with the paths of temporary files that have been
    * created, but not yet been read.
    *
    * @return an ''Iterable'' with paths to files that have not been read
    */
  def pendingTempPaths: Iterable[Path] = {
    val currentPath = currentReader.map(r => List(r.path)) getOrElse List.empty[Path]
    val waitingPaths = temporaryFiles.map(_.path).toList
    currentPath ::: waitingPaths
  }

  /**
    * Handles a request from a client for a block of data if this is
    * currently possible. If necessary, a new chunk writer actor is created
    * for one of the managed temporary files. The current reader actor is
    * sent a read request. The return value indicates whether this object
    * could handle the request.
    *
    * @param client  the requesting client
    * @param request the request to be processed
    * @return a flag whether this request can be handled
    */
  def initiateClientRequest(client: ActorRef,
                            request: DownloadData): Boolean = {
    if (currentRequest.isDefined) true
    else {
      currentReader = obtainCurrentReaderActor()
      currentReader match {
        case Some(TempReadOperation(actor, _)) =>
          currentRequest = Some(DownloadRequestData(request, client))
          sendDownloadRequest(actor, request)
          true
        case None =>
          if (pendingWrites.nonEmpty) {
            currentRequest = Some(DownloadRequestData(request, client))
            true
          } else false
      }
    }
  }

  /**
    * Notifies this object that a temporary file is going to be written. This
    * method is called when the in-memory buffer exceeds its limit, and data
    * has to be flushed to disk. This also means that the next data chunk has
    * to be written from a file.
    *
    * @param seqNo the sequence number of the temporary file
    */
  def pendingWriteOperation(seqNo: Int): Unit = {
    pendingWrites = pendingWrites.union(Set(seqNo))
  }

  /**
    * Notifies this object that a write operation for a temporary file has
    * completed.
    *
    * @param fileData information about the write operation
    */
  def tempFileWritten(fileData: WriteChunkActor.WriteResponse): Unit = {
    temporaryFiles = temporaryFiles.union(Set(TempFileData(fileData.request.target, fileData.request.seqNo)))

    currentRequest foreach { req =>
      currentReader = obtainCurrentReaderActor()
      currentReader foreach (op => sendDownloadRequest(op.reader, req.request))
    }
  }

  /**
    * Notifies this object that a request for data to a child reader actor
    * returned a result. The result object has to be passed to the current
    * client actor.
    *
    * @param result the result of the read operation
    */
  def downloadResultArrived(result: DownloadDataResult): Unit = {
    currentRequest foreach (_.client ! result)
    currentRequest = None
  }

  /**
    * Notifies this object that a file read operation is now complete. If
    * possible, the current client request is served by starting with the
    * next temporary file. The return value indicates whether a pending request
    * could be handled by this object; in this case, it is ''None''.
    * Otherwise, result is a defined ''Option'' with a
    * [[DownloadRequestData]] object, and the caller must take care to serve
    * this request.
    *
    * @return ''None'' if the request could be handled; a defined ''Option'' if
    *         no more data is available, and the caller has to handle a
    *         currently pending request
    */
  def downloadCompletedArrived(): Option[CompletedTempReadOperation] =
    currentReader map { op =>
      CompletedTempReadOperation(op, obtainPendingRequest())
    }

  /**
    * Returns an option with the currently pending request. This is needed to
    * find out whether further processing can be done when a temporary file
    * has been read completely.
    *
    * @return an ''Option'' with the currently pending request
    */
  private def obtainPendingRequest(): Option[DownloadRequestData] =
    currentRequest match {
      case Some(req) =>
        currentReader = None
        currentRequest = None
        if (initiateClientRequest(req.client, req.request)) None
        else Some(req)
      case None => None
    }

  /**
    * Obtains a reference to the current reader actor if possible. If a reader
    * is already present, it is returned. Otherwise, the method tries to create
    * one if possible.
    *
    * @return an ''Option'' for the current reader actor
    */
  private def obtainCurrentReaderActor(): Option[TempReadOperation] =
    currentReader orElse {
      temporaryFiles.headOption flatMap matchTempFileIndex map { t =>
        temporaryFiles = temporaryFiles.diff(Set(t))
        pendingWrites = pendingWrites.diff(Set(t.seqNo))
        val actor = actorFactory.createChildActor(Props(classOf[MediaFileDownloadActor],
          t.path, readChunkSize, MediaFileDownloadActor.IdentityTransform))
        TempReadOperation(actor, t.path)
      }
    }

  /**
    * Sends a download request to a download actor for one of the managed
    * files.
    *
    * @param actor   the target actor
    * @param request the download request
    */
  private def sendDownloadRequest(actor: ActorRef, request: DownloadData): Unit = {
    actor.tell(request, downloadActor)
  }

  /**
    * Checks whether the sequence number of the first temporary file matches
    * the lowest index of the first expected temporary file. This is needed to
    * make sure that temporary files are read in correct order. Otherwise, it
    * could happen that a write operation for a file takes extraordinary long,
    * and thus the next file is available before.
    *
    * @param t the data object for the temporary file
    * @return an ''Option'' for the file to be used
    */
  private def matchTempFileIndex(t: TempFileData): Option[TempFileData] = {
    val firstPendingWriteIdx = pendingWrites.headOption.getOrElse(Integer.MAX_VALUE)
    if (firstPendingWriteIdx == t.seqNo) Some(t)
    else None
  }
}
