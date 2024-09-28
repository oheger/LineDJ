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

package de.oliver_heger.linedj.archive.media

import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.apache.pekko.actor.ActorRef
import scalaz.State
import scalaz.State._

/**
  * A data class that contains all information about media obtained during a
  * scan operation.
  *
  * This class combines the information that has been generated in different
  * sub streams of the main scan stream.
  *
  * @param result information about media and the files they contain
  * @param info   a map with metadata about media
  */
private case class CombinedMediaScanResult(result: EnhancedMediaScanResult,
                                           info: Map[MediumID, MediumInfo])

/**
  * A data class that contains information about messages that have to be sent
  * after a complex state transition.
  *
  * An instance of this class is returned by functions that trigger a
  * complex state transition yielding multiple results. Rather than using a
  * tuple to pass these results, a dedicated case class is defined for this
  * purpose.
  *
  * @param results        a sequence of results to be passed downstream
  * @param actorsToAck    a sequence of actors to send an ACK
  * @param processingDone flag whether stream processing is now done
  */
private case class SinkTransitionMessages(results: Iterable[CombinedMediaScanResult],
                                          actorsToAck: Iterable[ActorRef],
                                          processingDone: Boolean)

/**
  * A case class representing the state of the sink of the media scan stream.
  *
  * The information stored in an instance is sufficient to combine partial
  * results generated during a scan operation. It is also tracked whether
  * confirmations have been received from downstream or need to be sent to
  * upstream components.
  *
  * @param scanResults    the aggregated list of scan results
  * @param mediaInfo      the medium information that has been parsed
  * @param resultAck      flag whether an ACK from downstream has been received
  * @param ackMediumFiles the sender of the last scan result to send an ACK
  * @param ackMediumInfo  the sender of the last medium info to send an ACK
  * @param resultsDone    flag whether all scan results have been received
  * @param infoDone       flag whether all medium info objects have been received
  */
private case class ScanSinkState(scanResults: List[EnhancedMediaScanResult],
                                 mediaInfo: Map[MediumID, MediumInfo],
                                 resultAck: Boolean,
                                 ackMediumFiles: Option[ActorRef],
                                 ackMediumInfo: Option[ActorRef],
                                 resultsDone: Boolean,
                                 infoDone: Boolean)

/**
  * Interface of a service that updates the state of the sink of the media scan
  * stream.
  *
  * The stream that scans and processes media files of an archive performs some
  * operations in parallel. It generates [[MediaScanResult]] objects for the
  * media encountered and also parses medium description files available. This
  * information has to be combined again and passed to components that further
  * process this data.
  *
  * Because of the management of back-pressure, this is not trivial. The
  * service defines an interface that allows adding new results. If sufficient
  * information is available to produce combined results, such result objects
  * are created (and returned), and the state is updated accordingly.
  * Otherwise, data has to be buffered. Buffers are restricted in size; a
  * result object that has been received is acknowledged only if there is still
  * space in the buffer available or after a confirmation from downstream
  * arrives.
  */
private trait ScanSinkUpdateService:
  /**
    * Updates the state of the sink with a newly arrived
    * ''EnhancedMediaScanResult'' object. Unless there an ACK pending for scan
    * results, the new object is added to the internal list.
    *
    * @param result the new result object
    * @param sender the sender of the scan result
    * @return the updated ''State''
    */
  def addScanResult(result: EnhancedMediaScanResult, sender: ActorRef): State[ScanSinkState, Unit]

  /**
    * Updates the state of the sink with a newly arrived ''MediumInfo''
    * object. Unless there is an ACK pending for medium info, the new object is
    * added to the internal list.
    *
    * @param info   the new ''MediumInfo'' object
    * @param sender the sender of the info object
    * @return the updated ''State''
    */
  def addMediumInfo(info: MediumInfo, sender: ActorRef): State[ScanSinkState, Unit]

  /**
    * Returns a sequence of references to actors to which an ACK message needs
    * to be sent. The state is reset, so that no ACK messages are pending any
    * more. This function should be called after data has been added or final
    * results have been propagated downstream. An ACK can be sent to an actor
    * that has provided a result object unless the internal buffer is full; in
    * this case, new results can be processed only after combined results have
    * been passed downstream.
    *
    * @param maxBufferSize the maximum buffer size
    * @return the updated ''State'' and actors to ACK
    */
  def actorsToAck(maxBufferSize: Int): State[ScanSinkState, Iterable[ActorRef]]

  /**
    * Obtains combined results from the current state. With this function the
    * information obtained in different sub scan streams can be brought
    * together again. It returns ''CombinedMediaScanResult'' objects for the
    * media for which both the files and metadata is known. The state is
    * updated to remove the media affected from the internal buffers; also, it
    * is assumed that this information is sent downstream, so that an ACK is
    * expected. If an ACK is already pending, no combined results can be sent;
    * so in this case, the function returns an empty sequence.
    *
    * @return the updated ''State'' and results to be sent downstream
    */
  def combinedResults(): State[ScanSinkState, Iterable[CombinedMediaScanResult]]

  /**
    * Updates the state of the sink when an ACK message for the last combined
    * result arrives. This means that components downstream are now able to
    * process further results.
    *
    * @return the updated ''State''
    */
  def resultAckReceived(): State[ScanSinkState, Unit]

  /**
    * Updates the state of the sink with the information that all scan
    * results have been received.
    *
    * @return the updated ''State''
    */
  def scanResultsDone(): State[ScanSinkState, Unit]

  /**
    * Updates the state of the sink with the information that all media
    * information have been received.
    *
    * @return the updated ''State''
    */
  def mediaInfoDone(): State[ScanSinkState, Unit]

  /**
    * Determines whether stream processing is now done. This is the case when
    * both sinks are done, no results are pending, and the ACK from downstream
    * has arrived.
    *
    * @return the ''State'' and the processing complete flag
    */
  def processingDone(): State[ScanSinkState, Boolean]

  /**
    * A composite function that updates the current state of the sink for a new
    * scan result and returns an object with new combined results to send
    * downstream and ACK messages.
    *
    * @param result        the new result object
    * @param sender        the sender of the scan result
    * @param maxBufferSize the maximum buffer size
    * @return the updated ''State'' and the transition messages
    */
  def handleNewScanResult(result: EnhancedMediaScanResult, sender: ActorRef, maxBufferSize: Int):
  State[ScanSinkState, SinkTransitionMessages] = for
    _ <- addScanResult(result, sender)
    res <- combinedResults()
    ack <- actorsToAck(maxBufferSize)
  yield SinkTransitionMessages(res, ack, processingDone = false)

  /**
    * A composite function that updates the current state of the sink for a new
    * medium info and returns an object with new combined results to send
    * downstream and ACK messages.
    *
    * @param info          the new medium info object
    * @param sender        the sender of the medium info
    * @param maxBufferSize the maximum buffer size
    * @return the updated ''State'' and the transition messages
    */
  def handleNewMediumInfo(info: MediumInfo, sender: ActorRef, maxBufferSize: Int):
  State[ScanSinkState, SinkTransitionMessages] = for
    _ <- addMediumInfo(info, sender)
    res <- combinedResults()
    ack <- actorsToAck(maxBufferSize)
  yield SinkTransitionMessages(res, ack, processingDone = false)

  /**
    * A composite function that updates the current state of the sink when an
    * ACK from downstream arrives. It may be possible that now new results can
    * be passed downstream; therefore, a corresponding messages object is
    * returned additionally.
    *
    * @param maxBufferSize the maximum buffer size
    * @return the updated ''State'' and the transition messages
    */
  def handleResultAck(maxBufferSize: Int): State[ScanSinkState, SinkTransitionMessages] = for
    _ <- resultAckReceived()
    res <- combinedResults()
    ack <- actorsToAck(maxBufferSize)
    done <- processingDone()
  yield SinkTransitionMessages(res, ack, done)

  /**
    * A composite function that updates the current state of the sink when all
    * scan results have been received and returns transition messages for the
    * steps to execute. It may be the case that now new results are available
    * that need to be passed downstream.
    *
    * @param maxBufferSize the maximum buffer size
    * @return the updated ''State'' and the transition messages
    */
  def handleScanResultsDone(maxBufferSize: Int): State[ScanSinkState, SinkTransitionMessages] =
    for
      _ <- scanResultsDone()
      res <- combinedResults()
      ack <- actorsToAck(maxBufferSize)
      done <- processingDone()
    yield SinkTransitionMessages(res, ack, done)

  /**
    * A composite function that updates the current state of the sink when all
    * media information has been received and returns transition messages for
    * the steps to execute. If stream processing is now complete, it may be the
    * case that new results to be passed downstream become available.
    *
    * @param maxBufferSize the maximum buffer size
    * @return the updated ''State'' and the transition messages
    */
  def handleMediaInfoDone(maxBufferSize: Int): State[ScanSinkState, SinkTransitionMessages] =
    for
      _ <- mediaInfoDone()
      res <- combinedResults()
      ack <- actorsToAck(maxBufferSize)
      done <- processingDone()
    yield SinkTransitionMessages(res, ack, done)

/**
  * The default implementation of the ''ScanSinkUpdateService'' trait.
  */
private object ScanSinkUpdateServiceImpl extends ScanSinkUpdateService:
  /** An initial state of the sink. */
  val InitialState: ScanSinkState = ScanSinkState(List.empty, Map.empty, resultAck = true,
    ackMediumFiles = None, ackMediumInfo = None, resultsDone = false, infoDone = false)

  override def addScanResult(result: EnhancedMediaScanResult, sender: ActorRef):
  State[ScanSinkState, Unit] = modify { s =>
    s.ackMediumFiles match
      case Some(_) => s // ACK pending
      case None =>
        s.copy(scanResults = result :: s.scanResults, ackMediumFiles = Some(sender))
  }

  override def addMediumInfo(info: MediumInfo, sender: ActorRef): State[ScanSinkState, Unit] =
    modify { s =>
      s.ackMediumInfo match
        case Some(_) => s // ACK pending
        case None =>
          s.copy(mediaInfo = s.mediaInfo + (info.mediumID -> info), ackMediumInfo = Some(sender))
    }

  override def actorsToAck(maxBufSize: Int): State[ScanSinkState, Iterable[ActorRef]] =
    State { s =>
      if (s.ackMediumFiles.isEmpty && s.ackMediumInfo.isEmpty) || bufferFilled(s, maxBufSize) then
        (s, Nil)
      else
        val (ackFiles, actors1) = handleAck(s.ackMediumFiles, Nil, s.scanResults, maxBufSize)
        val (ackInfo, actors2) = handleAck(s.ackMediumInfo, actors1, s.mediaInfo, maxBufSize)
        (s.copy(ackMediumFiles = ackFiles, ackMediumInfo = ackInfo), actors2)
    }

  override def combinedResults(): State[ScanSinkState, Iterable[CombinedMediaScanResult]] =
    State { s =>
      if !s.resultAck then (s, Nil)
      else
        val results = if allElementsReceived(s) then s.scanResults
        else findCompleteResults(s.scanResults, s.mediaInfo)
        if results.isEmpty then (s, Nil)
        else
          val media = results.flatMap(_.scanResult.mediaFiles.keys)
          val nextResults = s.scanResults filterNot results.contains
          val nextInfo = media.foldLeft(s.mediaInfo)((m, id) => m - id)
          (s.copy(scanResults = nextResults, mediaInfo = nextInfo, resultAck = false),
            results.map(createCombinedResult(_, s.mediaInfo)))
    }

  override def resultAckReceived(): State[ScanSinkState, Unit] =
    modify { s =>
      if s.resultAck then s else s.copy(resultAck = true)
    }

  override def scanResultsDone(): State[ScanSinkState, Unit] =
    modify { s => s.copy(resultsDone = true) }

  override def mediaInfoDone(): State[ScanSinkState, Unit] =
    modify { s => s.copy(infoDone = true) }

  override def processingDone(): State[ScanSinkState, Boolean] = for
    s <- get[ScanSinkState]
  yield processingDoneState(s)

  /**
    * Checks whether the buffer for result objects is filled. If this is the
    * case, no further results can be processed.
    *
    * @param s          the state
    * @param maxBufSize the maximum buffer size
    * @return '''true''' if the buffer is full; '''false''' otherwise
    */
  private def bufferFilled(s: ScanSinkState, maxBufSize: Int): Boolean =
    s.scanResults.size >= maxBufSize && s.mediaInfo.size >= maxBufSize

  /**
    * Handles the ACK for one of the actors that sent a result object. If an
    * ACK is pending for this actor and this ACK can be sent, the actor
    * reference is reset and added to the result list.
    *
    * @param actor      the optional actor pending for ACK
    * @param result     the list to take the results
    * @param buf        the current buffer for the results affected
    * @param maxBufSize the maximum buffer size
    * @return a tuple with the new actor reference and the updated result list
    */
  private def handleAck(actor: Option[ActorRef], result: List[ActorRef], buf: Iterable[_],
                        maxBufSize: Int): (Option[ActorRef], List[ActorRef]) =
    if actor.isDefined && buf.size < maxBufSize then (None, actor.get :: result)
    else (actor, result)

  /**
    * Checks whether the given state indicates that processing is done.
    *
    * @param s the state
    * @return a flag whether processing is done
    */
  private def processingDoneState(s: ScanSinkState): Boolean =
    allElementsReceived(s) && s.scanResults.isEmpty && s.resultAck

  /**
    * Checks whether all elements from the scan stream have been received.
    *
    * @param s the state
    * @return a flag whether all elements have been received
    */
  private def allElementsReceived(s: ScanSinkState): Boolean =
    s.infoDone && s.resultsDone

  /**
    * Finds all enhanced scan results for which complete medium information is
    * available.
    *
    * @param sr   the list with all enhanced scan results
    * @param info the map with medium information available
    * @return the scan results with complete medium information
    */
  private def findCompleteResults(sr: List[EnhancedMediaScanResult],
                                  info: Map[MediumID, MediumInfo]):
  List[EnhancedMediaScanResult] =
    sr filter { r =>
      r.scanResult.mediaFiles.keys forall info.contains
    }

  /**
    * Creates a combined result object for the specified enhanced scan result.
    * The required medium information is fetched from the given map.
    *
    * @param esr  the enhanced scan result affected
    * @param info the map with all medium information
    * @return the combined result
    */
  private def createCombinedResult(esr: EnhancedMediaScanResult,
                                   info: Map[MediumID, MediumInfo]): CombinedMediaScanResult =
    val infoMap = esr.scanResult.mediaFiles.keys.
      foldLeft(Map.empty[MediumID, MediumInfo])((m, id) =>
        m + (id -> info.getOrElse(id, MediumInfoParserActor.DummyMediumSettingsData)))
    CombinedMediaScanResult(esr, infoMap)
