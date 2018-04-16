/*
 * Copyright 2015-2018 The Developers Team.
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

import java.nio.file.Path

import akka.actor.ActorRef
import de.oliver_heger.linedj.archive.media
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved}
import scalaz.State
import scalaz.State._

/**
  * An enumeration representing the state of a request to remove the data of
  * the local archive from the union archive.
  *
  * Before new data generated during a scan operation can be sent to the union
  * archive, the existing data has to be removed. This is achieved by sending a
  * corresponding request to the union archive. With this enumeration the
  * status of this request can be tracked.
  */
private object UnionArchiveRemoveState extends Enumeration {
  /** Type alias for the remove state. */
  type UnionArchiveRemoveState = Value

  val Initial, Pending, Removed = Value
}

import de.oliver_heger.linedj.archive.media.UnionArchiveRemoveState._

/**
  * A class representing the state of a media scan operation.
  *
  * @param scanInProgress     flag whether a scan is in progress
  * @param removeState        the state of the remove request from the union
  *                           archive
  * @param startAnnounced     flag whether an event about a new scan operation
  *                           has been sent
  * @param availableMediaSent flag whether the available media have been
  *                           sent to the meta data manager
  * @param seqNo              the sequence number for the current scan operation
  * @param fileData           aggregated data for media files
  * @param mediaData          aggregated data about medium information
  * @param ackPending         reference to an actor that requires an ACK
  * @param ackMetaManager     flag whether an ACK from the meta data manager
  *                           actor has been received
  * @param currentResults     current results to be sent to the meta data
  *                           manager
  * @param currentMediaData   current media to be sent to the union archive
  */
private case class MediaScanState(scanInProgress: Boolean,
                                  removeState: UnionArchiveRemoveState,
                                  startAnnounced: Boolean,
                                  availableMediaSent: Boolean,
                                  seqNo: Int,
                                  fileData: Map[MediumID, Map[String, FileData]],
                                  mediaData: Map[MediumID, MediumInfo],
                                  ackPending: Option[ActorRef],
                                  ackMetaManager: Boolean,
                                  currentResults: List[EnhancedMediaScanResult],
                                  currentMediaData: Map[MediumID, MediumInfo])

/**
  * A case class defining messages to different components that need to be
  * sent after a state transition.
  *
  * When starting a scan operation or when new results arrive it is typically
  * necessary to send messages to other actors involved in the operation. This
  * class determines which messages need to be sent to which actor.
  *
  * @param unionArchiveMessage optional message to the union archive
  * @param metaManagerMessage  optional message to the meta data manager
  * @param ack                 optional actor to receive an ACK message
  */
private case class ScanStateTransitionMessages(unionArchiveMessage: Option[Any] = None,
                                               metaManagerMessage: Option[Any] = None,
                                               ack: Option[ActorRef] = None)

/**
  * Interface of a service that updates the state while scanning the directory
  * structure of a media archive.
  *
  * There are multiple components involved in a media scan operation that have
  * to be coordinated. A stream produces results representing the media found
  * in the directory structure; such results must be acknowledged.
  * Communication with the union archive is necessary to propagate media
  * information. Incoming results have to be aggregated to construct the
  * content of the local media archive. This service offers functions that
  * update the state of the scan operation when specific life-cycle events
  * occur.
  */
private trait MediaScanStateUpdateService {
  /**
    * Type alias for a state update. The update operation yields an updated
    * ''State'' and additional data of the specified type.
    */
  type StateUpdate[A] = State[MediaScanState, A]

  /**
    * Updates the state to start another scan operation for the specified
    * root path. If no scan is currently in progress, a scan operation is
    * started now. As additional result, a message to be sent to the media
    * scanner actor to initiate a new scan stream is returned.
    *
    * @param root the root path to be scanned
    * @return the updated ''State'' and an option with a message for the
    *         scanner actor
    */
  def triggerStartScan(root: Path): StateUpdate[Option[MediaScannerActor.ScanPath]]

  /**
    * Updates the state at the beginning of a new scan operation and returns an
    * object with messages to be sent to the actors involved. If no scan is in
    * progress, no state change is triggered, and an undefined instance of
    * transition messages is returned. Otherwise, the messages depend on the
    * removal state of the union archive: If the union archive already contains
    * data from this archive component, a removal has to be triggered first
    * before further results can be produced.
    *
    * @param archiveName the name of this archive component
    * @return the updated ''State'' and messages to actors involved
    */
  def startScanMessages(archiveName: String): StateUpdate[ScanStateTransitionMessages]

  /**
    * Updates the state after the confirmation from the unit archive arrived
    * that the data for this archive component was removed.
    *
    * @return the updated ''State''
    */
  def removedFromUnionArchive(): StateUpdate[Unit]

  /**
    * Updates the state after an ACK message from the meta data actor has been
    * received.
    *
    * @return the updated ''State''
    */
  def ackFromMetaManager(): StateUpdate[Unit]

  /**
    * Updates the state for newly received results. The results are added to
    * the current meta data state and also stored in a way that they can be
    * propagated to the union archive and the meta data manager.
    *
    * @param results the object with results
    * @param sender  the sending actor
    * @return the updated ''State''
    */
  def resultsReceived(results: ScanSinkActor.CombinedResults, sender: ActorRef): StateUpdate[Unit]

  /**
    * Updates the state for an ACK for the latest results and returns an
    * ''Option'' with the actor reference that should receive the ACK. If an
    * ACK is pending and if all preconditions are fulfilled, the actor
    * reference to send an ACK to is returned.
    *
    * @return the updated ''State'' and an actor to ACK
    */
  def actorToAck(): StateUpdate[Option[ActorRef]]

  /**
    * Updates the state for a message to be sent to the meta data manager
    * actor. This function checks whether in the current state a message needs
    * to be sent to the meta data manager. If so, the state is updated, and the
    * message is returned.
    *
    * @return the updated ''State'' and an option with the message
    */
  def metaDataMessage(): StateUpdate[Option[Any]]

  /**
    * Updates the state for a message to be sent to the union archive actor.
    * This function checks whether the current state contains media data which
    * needs to be sent to the union archive. If so, the state is updated to
    * reset this data, and the corresponding message is returned.
    *
    * @param archiveName the name of this archive component
    * @return the updated ''State'' and an option with the message
    */
  def unionArchiveMessage(archiveName: String): StateUpdate[Option[Any]]

  /**
    * Updates the state after a notification that the scan is now complete has
    * been received. This means that no new results will be processed any more.
    * However, it can be the case that there are still current results that
    * need to be propagated to the union archive or the meta data manager.
    *
    * @return the updated ''State''
    */
  def scanComplete(): StateUpdate[Unit]

  /**
    * Updates the state after the scan operation has been canceled. In this
    * scenario, some fields of the state have to be reset manually because
    * results will no longer be processed and ACK messages might be dropped.
    *
    * @return the updated ''State''
    */
  def scanCanceled(): StateUpdate[Unit]

  /**
    * Updates the state when removing of data from the union archive was
    * confirmed and returns an object with messages to be sent now.
    *
    * @param archiveName the name of this archive component
    * @return the updated ''State'' and messages to be sent
    */
  def handleRemovedFromUnionArchive(archiveName: String):
  StateUpdate[ScanStateTransitionMessages] = for {
    _ <- removedFromUnionArchive()
    msg <- fetchTransitionMessages(archiveName)
  } yield msg

  /**
    * Updates the state when new results arrive and returns an object with
    * messages to be sent now.
    *
    * @param results     the object with results
    * @param sender      the sending actor
    * @param archiveName the name of the archive component
    * @return the updated ''State'' and messages to be sent
    */
  def handleResultsReceived(results: ScanSinkActor.CombinedResults, sender: ActorRef,
                            archiveName: String): StateUpdate[ScanStateTransitionMessages] = for {
    _ <- resultsReceived(results, sender)
    msg <- fetchTransitionMessages(archiveName)
  } yield msg

  /**
    * Updates the state when an ACK from the meta data manager actor arrives
    * and returns an object with messages to be sent now.
    *
    * @param archiveName the name of the archive component
    * @return the updated ''State'' and messages to be sent
    */
  def handleAckFromMetaManager(archiveName: String):
  StateUpdate[ScanStateTransitionMessages] = for {
    _ <- ackFromMetaManager()
    msg <- fetchTransitionMessages(archiveName)
  } yield msg

  /**
    * Generates a ''ScanStateTransitionMessages'' object from the current
    * state. This functionality is needed by multiple functions that return
    * composed results.
    *
    * @param archiveName the name of the archive component
    * @return the updated ''State'' and messages to be sent
    */
  private def fetchTransitionMessages(archiveName: String):
  StateUpdate[ScanStateTransitionMessages] = for {
    unionMsg <- unionArchiveMessage(archiveName)
    metaMsg <- metaDataMessage()
    ack <- actorToAck()
  } yield ScanStateTransitionMessages(unionMsg, metaMsg, ack)
}

/**
  * The default implementation of the ''MediaScanStateUpdateService'' trait.
  */
private object MediaScanStateUpdateServiceImpl extends MediaScanStateUpdateService {
  /**
    * Constant for the initial scan state. When starting up a new media manager
    * actor this state is used.
    */
  val InitialState: MediaScanState =
    MediaScanState(scanInProgress = false,
      removeState = Removed,
      startAnnounced = false,
      seqNo = 0,
      fileData = Map.empty,
      mediaData = Map.empty,
      ackPending = None,
      ackMetaManager = true,
      currentResults = Nil,
      currentMediaData = Map.empty,
      availableMediaSent = true)

  /** Constant for empty transition messages. */
  private val NoTransitionMessages = ScanStateTransitionMessages()

  override def triggerStartScan(root: Path):
  StateUpdate[Option[MediaScannerActor.ScanPath]] = State { s =>
    if (s.scanInProgress) (s, None)
    else {
      val next = s.copy(scanInProgress = true, fileData = Map.empty, mediaData = Map.empty,
        removeState = initRemoveState(s))
      (next, Some(MediaScannerActor.ScanPath(root, s.seqNo)))
    }
  }

  override def startScanMessages(archiveName: String):
  StateUpdate[ScanStateTransitionMessages] = State { s =>
    if (!s.scanInProgress) (s, NoTransitionMessages)
    else s.removeState match {
      case Removed if !s.startAnnounced =>
        val next = s.copy(startAnnounced = true, ackMetaManager = false)
        val messages = ScanStateTransitionMessages(metaManagerMessage = Some(MediaScanStarts))
        (next, messages)
      case Initial =>
        val next = s.copy(removeState = Pending)
        val messages = ScanStateTransitionMessages(unionArchiveMessage =
          Some(ArchiveComponentRemoved(archiveName)))
        (next, messages)
      case _ => (s, NoTransitionMessages)
    }
  }

  override def removedFromUnionArchive(): StateUpdate[Unit] = modify { s =>
    if (s.removeState == Pending) s.copy(removeState = Removed)
    else s
  }

  override def ackFromMetaManager(): StateUpdate[Unit] = modify { s =>
    s.copy(ackMetaManager = true)
  }

  override def resultsReceived(results: ScanSinkActor.CombinedResults, sender: ActorRef):
  StateUpdate[Unit] = modify { s =>
    if (s.ackPending.isDefined || results.seqNo != s.seqNo) s
    else s.copy(fileData = updateFileDataForResults(s.fileData, results.results),
      mediaData = updateMediaDataForResults(s.mediaData, results.results),
      currentResults = extractCurrentResults(results.results),
      currentMediaData = extractCurrentMediaInfo(results.results),
      ackPending = Some(sender))
  }

  override def actorToAck(): StateUpdate[Option[ActorRef]] = State { s =>
    if (ackBlocked(s)) (s, None)
    else (s.copy(ackPending = None), s.ackPending)
  }

  override def metaDataMessage(): StateUpdate[Option[Any]] = State { s =>
    if (!s.availableMediaSent)
      (s.copy(availableMediaSent = true), Some(AvailableMedia(s.mediaData)))
    else if (metaDataMessageBlocked(s)) (s, None)
    else generateMetaDataMessage(s)
  }

  override def unionArchiveMessage(archiveName: String): StateUpdate[Option[Any]] = State { s =>
    if (s.currentMediaData.nonEmpty)
      (s.copy(currentMediaData = Map.empty),
        Some(AddMedia(s.currentMediaData, archiveName, None)))
    else (s, None)
  }

  override def scanComplete(): StateUpdate[Unit] = modify { s =>
    s.copy(scanInProgress = false, seqNo = s.seqNo + 1)
  }

  override def scanCanceled(): StateUpdate[Unit] = modify { s =>
    s.copy(removeState = Initial, currentMediaData = Map.empty, currentResults = List.empty)
  }

  /**
    * Returns the initial remove state for a new scan operation based on the
    * given state. The state depends whether this is the first scan or not.
    *
    * @param s the current state
    * @return the initial remove state
    */
  private def initRemoveState(s: MediaScanState): media.UnionArchiveRemoveState.Value =
    if (s.seqNo == 0) Removed else Initial

  /**
    * Creates the reverse mapping of the given URI mapping. This is needed to
    * create mappings specific to a given medium.
    *
    * @param uriMapping the original URI mapping
    * @return the reverse URI mapping
    */
  private def revertMapping(uriMapping: Map[String, FileData]): Map[FileData, String] =
    uriMapping.foldLeft(Map.empty[FileData, String])((m, e) => m + (e._2 -> e._1))

  /**
    * Creates a URI mapping specific to the given medium from the (global)
    * mapping contained in the given ''EnhancedMediaScanResult''.
    *
    * @param esr            the ''EnhancedMediaScanResult''
    * @param mid            the ID of the medium affected
    * @param reverseMapping the reverse URI mapping
    * @return the URI mapping specific to the given medium
    */
  private def uriMappingForMedium(esr: EnhancedMediaScanResult, mid: MediumID,
                                  reverseMapping: Map[FileData, String]): Map[String, FileData] =
    esr.scanResult.mediaFiles(mid).foldLeft(Map.empty[String, FileData]) { (m, f) =>
      m + (reverseMapping(f) -> f)
    }

  /**
    * Updates the data with media and their files for the given enhanced scan
    * result. The mapping from URIs to media files contained in the scan result
    * is added to the given map. As the URI mapping of the scan result is not
    * specific to single media, this is a bit more complex.
    *
    * @param data the current file data
    * @param esr  the ''EnhancedMediaScanResult'' to be added
    * @return the updated map with file data
    */
  private def updateFileDataForResult(data: Map[MediumID, Map[String, FileData]],
                                      esr: EnhancedMediaScanResult): Map[MediumID, Map[String,
    FileData]] = {
    lazy val reverseMapping = revertMapping(esr.fileUriMapping)
    if (esr.scanResult.mediaFiles.size == 1)
      data + (esr.scanResult.mediaFiles.keys.head -> esr.fileUriMapping)
    else
      esr.scanResult.mediaFiles.keys.foldLeft(data) { (map, mid) =>
        map + (mid -> uriMappingForMedium(esr, mid, reverseMapping))
      }
  }

  /**
    * Updates the data with media and their files for the given sequence of
    * result objects.
    *
    * @param data    the current file data
    * @param results the sequence with result objects
    * @return the updated map with file data
    */
  private def updateFileDataForResults(data: Map[MediumID, Map[String, FileData]],
                                       results: Iterable[CombinedMediaScanResult]):
  Map[MediumID, Map[String, FileData]] =
    results.foldLeft(data) { (map, res) =>
      updateFileDataForResult(map, res.result)
    }

  /**
    * Updates the data with media information for the given sequence of result
    * objects.
    *
    * @param data    the current media information
    * @param results the sequence with result objects
    * @return the updated map with media information
    */
  private def updateMediaDataForResults(data: Map[MediumID, MediumInfo],
                                        results: Iterable[CombinedMediaScanResult]):
  Map[MediumID, MediumInfo] =
    results.foldLeft(data) { (map, res) =>
      map ++ res.info
    }

  /**
    * Extracts the list of current scan results from the given sequence of
    * combined results.
    *
    * @param results the sequence of combined results
    * @return the list with current scan results
    */
  private def extractCurrentResults(results: Iterable[CombinedMediaScanResult]):
  List[EnhancedMediaScanResult] =
    results.map(_.result).toList

  /**
    * Extracts the current map with media information from the given sequence
    * of combined results.
    *
    * @param results the sequence of combined results
    * @return the map with current media information
    */
  private def extractCurrentMediaInfo(results: Iterable[CombinedMediaScanResult]):
  Map[MediumID, MediumInfo] =
    results.foldLeft(Map.empty[MediumID, MediumInfo])(_ ++ _.info)

  /**
    * Checks whether there are conditions preventing that an ACK can be sent
    * upstream.
    *
    * @param s the state
    * @return '''true''' if no ACK can be sent; '''false''' otherwise
    */
  private def ackBlocked(s: MediaScanState): Boolean =
    s.ackPending.isEmpty || s.currentResults.nonEmpty || s.currentMediaData.nonEmpty

  /**
    * Checks whether there are conditions in the given state preventing that
    * a message to the meta data actor can be sent.
    *
    * @param s the state
    * @return '''true''' if no message can be sent; '''false''' otherwise
    */
  private def metaDataMessageBlocked(s: MediaScanState): Boolean =
    !s.ackMetaManager || s.removeState != Removed

  /**
    * Obtains the message to be sent to the meta data actor in the specified
    * state and updates the state accordingly.
    *
    * @param s the state
    * @return the updated ''State'' and an option with the message
    */
  private def generateMetaDataMessage(s: MediaScanState): (MediaScanState, Option[Any]) =
    if (!s.startAnnounced)
      (s.copy(startAnnounced = true), Some(MediaScanStarts))
    else s.currentResults match {
      case h :: t =>
        (s.copy(ackMetaManager = false, currentResults = t), Some(h))
      case _ => (s, None)
    }
}
