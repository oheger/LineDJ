/*
 * Copyright 2015-2020 The Developers Team.
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

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status, Terminated}
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.{CloseRequest, CloseSupport}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.metadata.{GetFilesMetaData, GetMetaDataFileInfo}
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved, GetArchiveMetaDataFileInfo}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.util.{Failure, Success, Try}

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
    * Generates the mapping from a checksum to the corresponding medium ID.
    *
    * @param media the map with media information
    * @return the checksum mapping
    */
  private def createChecksumMapping(media: Map[MediumID, MediumInfo]): Map[String, MediumID] =
    media map (e => e._2.checksum -> e._1)
}

/**
  * An actor responsible for constructing a union of all media contributed to
  * the media archive.
  *
  * The union media archive can deal with media from different sources. This
  * actor manages all media currently available. The idea is that the archive
  * consists of multiple components (represented by actors). On startup (or
  * when a new scan operation is triggered), all components construct a data
  * object with information about the media they can contribute. This object is
  * then sent to this actor which aggregates all available media information
  * and provides access to it.
  *
  * In order to contribute data to the union media archive, an archive
  * component has to do the following interactions with this actor and
  * support the mentioned messages:
  *  - An [[AddMedia]] message has to be sent to this actor with media data to
  * be added to the union archive. The sending actor becomes the controller
  * actor for this data (unless another actor is specified in the message).
  *  - A [[MediumFileRequest]] for a file is forwarded to the controller actor
  * for the medium the file belongs to. File download then takes place between
  * this actor and the sender of the request.
  *  - A [[DownloadActorAlive]] message is forwarded to the controller actor
  * responsible for the ''MediumID'' referenced in the message.
  *  - A [[ScanAllMedia]] message is forwarded to all current controller
  * actors. In reaction, they should start a new scan and report the results to
  * this union actor. Before sending data which replaces existing one (e.g.
  * when starting a new scan), the controller has to send an
  * [[ArchiveComponentRemoved]] message to make sure that existing data for
  * this component is removed.
  *  - [[GetMetaDataFileInfo]] messages must be handled and answered with a
  * corresponding ''MetaDataFileInfo'' message.
  *  - Close requests are forwarded to all currently available controller
  * actors. On receiving such a request, a controller has to cancel an ongoing
  * scan operation (if any) and then ack the request. Only after all
  * controllers have answered the request, an ack is sent to the original
  * sender.
  *  - If a controller actor dies, all data contributed by this archive
  * component is removed from the union archive.
  *
  * Media typically are assigned a unique checksum (a hash value). When
  * requesting data from this actor (media files or meta data) such a checksum
  * can be specified. If this is done, the checksum has precedence over the
  * ''MediumID'' in the request. This is useful when media can be provided by
  * different archive components; as the checksum is independent from an
  * archive component, the correct medium can be identified, no matter which
  * concrete archive component owns it. To enable this independence of concrete
  * archive components for meta data requests as well, this actor supports
  * messages of type [[GetFilesMetaData]]. These messages are forwarded to the
  * meta data union actor after an attempt was made to map the referenced media
  * based on checksum values. (''GetFilesMetaData'' messages can also be sent
  * directly to the meta data union actor, but in this case the checksum is
  * ignored and media IDs must match directly.)
  *
  * @param metaDataUnionActor the actor managing the union of meta data
  */
class MediaUnionActor(metaDataUnionActor: ActorRef) extends Actor with ActorLogging {
  this: ChildActorFactory with CloseSupport =>

  import MediaUnionActor._

  /** The map with the currently available media. */
  private var availableMedia = AvailableMedia(Map.empty)

  /** A mapping for archive component IDs to controller actors. */
  private var controllerMap = Map.empty[String, ActorRef]

  /** A mapping from media checksum strings to medium IDs. */
  private var optChecksumMap: Option[Map[String, MediumID]] = None

  override def receive: Receive = {
    case GetAvailableMedia =>
      sender ! availableMedia

    case AddMedia(media, compID, optCtrlActor) =>
      availableMedia = AvailableMedia(availableMedia.media ++ media)
      optChecksumMap = None
      log.info(s"Received AddMedia message from component $compID.")
      if (!controllerMap.contains(compID)) {
        val controller = optCtrlActor getOrElse sender()
        controllerMap += compID -> controller
        context watch controller
        log.info("Added controller actor.")
      }

    case fileReq: MediumFileRequest =>
      val mid = resolveMediumID(fileReq.fileID)
      controllerMap.get(mid.archiveComponentID) match {
        case Some(ctrl) =>
          ctrl forward fileReq
        case None =>
          sender ! undefinedMediumFileResponse(fileReq)
      }

    case req@GetFilesMetaData(files, _) =>
      val adaptedReq = MetaDataUnionActor.GetFilesMetaDataWithMapping(request = req,
        idMapping = mapMediaIDsInMetaDataRequest(files))
      metaDataUnionActor forward adaptedReq

    case dal: DownloadActorAlive =>
      log.info("Received download alive message for {}.", dal.fileID)
      val mid = resolveMediumID(dal.fileID)
      controllerMap.get(mid.archiveComponentID) foreach (_ forward dal)

    case ScanAllMedia =>
      metaDataUnionActor ! ScanAllMedia
      controllerMap.values foreach (_ ! ScanAllMedia)

    case GetArchiveMetaDataFileInfo(archiveCompID) =>
      Try(controllerMap(archiveCompID)) match {
        case Success(controller) =>
          controller forward GetMetaDataFileInfo
        case Failure(exception) =>
          sender() ! Status.Failure(exception)
      }

    case Terminated(actor) =>
      val optMapping = controllerMap.find(t => t._2 == actor)
      optMapping foreach { m =>
        log.info(s"Removing data from component ${m._1} because controller actor died.")
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
    * Resolves a medium ID from a ''MediaFileID'' taking the checksum into
    * account. If a checksum is specified, and a medium with this checksum
    * exists, the ID of this medium is returned. Otherwise, the medium ID
    * contained in the file ID is returned directly.
    *
    * @param fileID the ''MediaFileID''
    * @return the ''MediumID'' referenced by this file ID
    */
  private def resolveMediumID(fileID: MediaFileID): MediumID =
    fileID.checksum flatMap checksumMap.get getOrElse fileID.mediumID

  /**
    * Maps the media IDs in the specified sequence of ''MediaFileID'' objects
    * based on the checksum if possible. If a ''MediaFileID'' contains a
    * checksum, this checksum has precedence over the medium ID. So this method
    * checks for each file ID whether a checksum is present and whether it
    * references a different medium. If so, the ''MediaFileID'' is altered;
    * otherwise, it remains as is.
    *
    * @param files the sequence of ''MediaFileID'' objects
    * @return the sequence with adapted file IDs
    */
  private def mapMediaIDsInMetaDataRequest(files: Iterable[MediaFileID]):
  Map[MediaFileID, MediumID] =
    files.foldLeft(Map.empty[MediaFileID, MediumID]) { (m, f) =>
      m + (f -> resolveMediumID(f))
    }

  /**
    * Generates a response for a medium file request which cannot be resolved.
    *
    * @param req the request
    * @return the response for this request
    */
  private def undefinedMediumFileResponse(req: MediumFileRequest): MediumFileResponse =
    MediumFileResponse(req, None, -1)

  /**
    * Updates the specified media object by removing all media owned by the
    * provided archive component.
    *
    * @param media       the ''AvailableMedia'' object
    * @param componentID the archive component ID
    * @return an instance with data from this component removed
    */
  private def removeMediaFrom(media: AvailableMedia, componentID: String): AvailableMedia = {
    optChecksumMap = None
    AvailableMedia(media.media filterNot (t => t._1.archiveComponentID == componentID))
  }

  /**
    * Obtains the mapping from a medium checksum to a medium ID. The map is
    * created on demand. It has to be reset whenever the medium information to
    * be managed by this actor is changed.
    *
    * @return the checksum to medium ID mapping
    */
  private def checksumMap: Map[String, MediumID] = optChecksumMap match {
    case Some(map) => map
    case None =>
      val map = createChecksumMapping(availableMedia.media)
      optChecksumMap = Some(map)
      map
  }
}
