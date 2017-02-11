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

package de.oliver_heger.linedj.archiveunion

import akka.actor.{Actor, ActorRef, Props, Terminated}
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.{CloseRequest, CloseSupport, FileReaderActor}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved}
import de.oliver_heger.linedj.utils.ChildActorFactory

object MediaUnionActor {

  private class MediaUnionActorImpl(metaDataUnionActor: ActorRef)
    extends MediaUnionActor(metaDataUnionActor) with ChildActorFactory with CloseSupport

  /**
    * Returns a ''Props'' object for creating instances of this actor class.
    *
    * @param metaDataUnionActor the actor managing the union of meta data
    * @return creation ''Props'' for actor instances
    */
  def apply(metaDataUnionActor: ActorRef): Props =
    Props(classOf[MediaUnionActorImpl], metaDataUnionActor)

  /**
    * Generates a ''MediumFiles'' object as response for a request which
    * cannot be resolved.
    *
    * @param req the request
    * @return the ''MediumFiles'' to answer this request
    */
  private def undefinedMediumFiles(req: GetMediumFiles): MediumFiles =
    MediumFiles(req.mediumID, Set.empty, existing = false)
}

/**
  * An actor responsible for constructing a union of all media contributed to
  * the media archive.
  *
  * The union media archive can deal with media from different sources. This
  * actor manages all media currently available. The ID is that the archive
  * consists of multiple components (represented by actors). On startup (or
  * when a new scan operation is triggered), all components construct a data
  * object with information about the media they can contribute. This object is
  * then sent to this actor which aggregates all available media information
  * and provides access to it.
  *
  * @param metaDataUnionActor the actor managing the union of meta data
  */
class MediaUnionActor(metaDataUnionActor: ActorRef) extends Actor {
  this: ChildActorFactory with CloseSupport =>

  import MediaUnionActor._

  /** The map with the currently available media. */
  private var availableMedia = AvailableMedia(Map.empty)

  /** A mapping for archive component IDs to controller actors. */
  private var controllerMap = Map.empty[String, ActorRef]

  override def receive: Receive = {
    case GetAvailableMedia =>
      sender ! availableMedia

    case AddMedia(media, compID, optCtrlActor) =>
      availableMedia = AvailableMedia(availableMedia.media ++ media)
      val controller = optCtrlActor getOrElse sender()
      controllerMap += compID -> controller
      context watch controller

    case filesReq: GetMediumFiles =>
      forwardToController(filesReq.mediumID, filesReq)(undefinedMediumFiles)

    case fileReq: MediumFileRequest =>
      forwardToController(fileReq.mediumID, fileReq)(undefinedMediumFileResponse)

    case ScanAllMedia =>
      metaDataUnionActor ! ScanAllMedia
      controllerMap.values foreach (_ ! ScanAllMedia)

    case Terminated(actor) =>
      val optMapping = controllerMap.find(t => t._2 == actor)
      optMapping foreach { m =>
        metaDataUnionActor ! ArchiveComponentRemoved(m._1)
        availableMedia = removeMediaFrom(availableMedia, m._1)
        controllerMap -= m._1
      }

    case msg: ArchiveComponentRemoved =>
      availableMedia = removeMediaFrom(availableMedia, msg.archiveCompID)
      metaDataUnionActor forward msg

    case CloseRequest =>
      onCloseRequest(self, metaDataUnionActor :: controllerMap.values.toList, sender(), this)

    case CloseComplete =>
      onCloseComplete()
  }

  /**
    * Handles a request which has to be forwarded to a controller actor. The
    * controller actor responsible for the medium ID is obtained, and the
    * request is forwarded to it. If no controller can be resolved, an error
    * message is produced using the specified function and sent back to the
    * sender.
    *
    * @param mid     the medium ID
    * @param request the request to be forwarded
    * @param errMsg  function to generate the error message
    * @tparam T the type of the request
    */
  private def forwardToController[T](mid: MediumID, request: T)(errMsg: T => Any): Unit = {
    controllerMap.get(mid.archiveComponentID) match {
      case Some(ctrl) =>
        ctrl forward request
      case None =>
        sender ! errMsg(request)
    }
  }

  /**
    * Generates a response for a medium file request which cannot be resolved.
    *
    * @param req the request
    * @return the response for this request
    */
  private def undefinedMediumFileResponse(req: MediumFileRequest): MediumFileResponse = {
    val readerActor = context.actorOf(Props[FileReaderActor])
    MediumFileResponse(req, readerActor, -1)
  }

  /**
    * Updates the specified media object by removing all media owned by the
    * provided archive component.
    *
    * @param media       the ''AvailableMedia'' object
    * @param componentID the archive component ID
    * @return an instance with data from this component removed
    */
  private def removeMediaFrom(media: AvailableMedia, componentID: String): AvailableMedia =
    AvailableMedia(media.media filterNot (t => t._1.archiveComponentID == componentID))
}
