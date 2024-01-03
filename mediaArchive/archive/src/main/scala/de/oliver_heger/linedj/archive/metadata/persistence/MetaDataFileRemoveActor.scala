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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.archive.metadata.persistence.MetaDataFileRemoveActor.{RemoveMetaDataFiles, RemoveMetaDataFilesResult, RemoveRequestData}
import de.oliver_heger.linedj.io.RemoveFileActor
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.apache.pekko.actor.{Actor, ActorRef, OneForOneStrategy, Props, SupervisorStrategy, Terminated}

import java.nio.file.Path

object MetaDataFileRemoveActor:

  /**
    * A message processed by [[MetaDataFileRemoveActor]] telling it to remove
    * the given set of meta data files.
    *
    * @param fileIDs     the set of checksum values for the files to be removed
    * @param pathMapping the mapping from checksum values to paths
    * @param client      the original client that sent this request
    */
  private[persistence] case class RemoveMetaDataFiles(fileIDs: Set[String],
                                                      pathMapping: Map[String, Path],
                                                      client: ActorRef)

  /**
    * A message sent by [[MetaDataFileRemoveActor]] as a result of a remove
    * operation. The message indicates which files could be removed
    * successfully.
    *
    * @param request           the original request
    * @param successfulDeleted a set with the checksum values of the files that
    *                          could be removed successfully
    */
  private[persistence] case class RemoveMetaDataFilesResult(request: RemoveMetaDataFiles,
                                                            successfulDeleted: Set[String])

  /**
    * An internally used data class that stores information about requests to
    * remove meta data files.
    *
    * @param request the actual remove request
    * @param client  the sender of this request
    */
  private case class RemoveRequestData(request: RemoveMetaDataFiles, client: ActorRef)

  private class MetaDataFileRemoveActorImpl extends MetaDataFileRemoveActor
    with ChildActorFactory

  /**
    * Creates a ''Props'' object for creating an instance of this actor class.
    *
    * @return a ''Props'' object for creating a new actor
    */
  def apply(): Props = Props[MetaDataFileRemoveActorImpl]()

/**
  * An actor class which supports removing a set of meta data files.
  *
  * This actor is used internally to remove a set of meta data files, e.g. the
  * obsolete files no longer assigned to an active medium. The actor reacts on
  * messages specifying the files to be removed (as checksum values) and the
  * map with checksum values to concrete ''Path'' objects. Each file is removed
  * one by one using a ''RemoveFileActor''. At the end of the operation, a
  * message is returned listing successful and failed operations.
  */
class MetaDataFileRemoveActor extends Actor:
  this: ChildActorFactory =>

  /**
    * Stores the current child actor which does the actual removing. The child
    * is created on demand. It has to be recreated for failed remove
    * operations.
    */
  private var removeActorCache: Option[ActorRef] = None

  /**
    * A list with the requests to be processed. A list is required if there
    * are multiple requests concurrently.
    */
  private var requests = List.empty[RemoveRequestData]

  /**
    * A set with the file IDs of the current request that could be deleted
    * successfully.
    */
  private var deletedFiles = Set.empty[String]

  /**
    * A list with the files to be deleted for the currently processed
    * request.
    */
  private var deleteList = List.empty[String]

  /**
    * Stores the path that is currently deleted. This is used to find out
    * whether a ''FileRemoved'' message is for the correct path.
    */
  private var pathInProgress: Path = _

  /**
    * A supervisor strategy which ensures that the child remove actor is
    * stopped when an error occurs.
    */
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy():
    case _ => Stop

  override def receive: Receive =
    case req: RemoveMetaDataFiles =>
      requests = requests :+ RemoveRequestData(req, sender())
      if deleteList.isEmpty then
        processNextRequest()

    case RemoveFileActor.FileRemoved(path) if path == pathInProgress =>
      deletedFiles += deleteList.head
      fileProcessed()
      processNextFile()

    case Terminated(_) =>
      removeActorCache = None
      fileProcessed()
      processNextFile()

  /**
    * Processes the next file to be deleted.
    */
  private def processNextFile(): Unit =
    deleteList match
      case h :: _ =>
        val (removeActor, cache) = fetchRemoveActor(removeActorCache)
        pathInProgress = requests.head.request.pathMapping(h)
        removeActor ! RemoveFileActor.RemoveFile(pathInProgress)
        removeActorCache = cache
      case Nil =>
        requests.head.client ! RemoveMetaDataFilesResult(requests.head.request,
          deletedFiles)
        requests = requests.tail
        processNextRequest()

  /**
    * Updates the actor's state after a file has been removed.
    */
  private def fileProcessed(): Unit =
    pathInProgress = null
    deleteList = deleteList.tail

  /**
    * Processes the next request if there is any in the queue.
    */
  private def processNextRequest(): Unit =
    requests.headOption foreach { d =>
      deleteList = fetchFilesToBeRemoved(d.request)
      deletedFiles = Set.empty
      processNextFile()
    }

  /**
    * Obtains a list of files to be deleted from the given request. This
    * function also filters out invalid files that are not contained in the
    * path mapping.
    *
    * @param req the request
    * @return a list with files to be deleted
    */
  private def fetchFilesToBeRemoved(req: RemoveMetaDataFiles): List[String] =
  req.fileIDs.filter(req.pathMapping.contains).toList

  /**
    * Returns a reference to the child actor for remove operations and an
    * updated state for the cached actor reference. The child actor is
    * created if necessary, and death watch is established.
    *
    * @param childRef the cached child actor reference
    * @return the child actor and the updated reference cache
    */
  private def fetchRemoveActor(childRef: Option[ActorRef]): (ActorRef, Option[ActorRef]) =
  childRef match
    case Some(r) => (r, childRef)
    case None =>
      val child = createChildActor(Props[RemoveFileActor]())
      context watch child
      (child, Some(child))
