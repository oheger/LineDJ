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

import de.oliver_heger.linedj.archive.media.ScanSinkActor._
import de.oliver_heger.linedj.shared.archive.media.MediumInfo
import org.apache.pekko.actor.{Actor, ActorRef}
import scalaz.State

import scala.concurrent.Promise

object ScanSinkActor:

  /**
    * The initialization message. This message is sent from the stream to the
    * sink actor at the very beginning, before stream elements are passed.
    */
  case object Init

  /**
    * A message representing the ACK signal that a stream element has been
    * processed. This is used to implement back-pressure.
    */
  case object Ack

  /**
    * A message indicating that the processing of scan results is now complete.
    */
  case object ScanResultsComplete

  /**
    * A message indicating that the processing of media information is now
    * complete.
    */
  case object MediaInfoComplete

  /**
    * A message indicating that the stream failed. This message also causes the
    * actor to report stream completion (with an exception) and to stop itself.
    *
    * @param cause the error that occurred
    */
  case class StreamFailure(cause: Throwable)

  /**
    * A message with combined results created during stream processing.
    *
    * As soon as completed result objects are available, they are passed to the
    * media manager actor using this message. The manager has to confirm this
    * message with an [[Ack]] response; this is the signal that it is ready to
    * process further results.
    *
    * @param results the results currently available
    * @param seqNo   the sequence number of the current scan operation
    */
  private[media] case class CombinedResults(results: Iterable[CombinedMediaScanResult],
                                            seqNo: Int)


/**
  * An actor serving as the sink for the stream that scans a media archive.
  *
  * The stream reads the content of media and their description files in
  * parallel. As soon as results are available, they are sent to this actor.
  * This actor is responsible for bringing the pieces together again and send
  * combined results to the media manager actor. Back-pressure signals are
  * taken into account to prevent the system from being overwhelmed from
  * incoming result objects.
  *
  * The given promise is completed when both sub streams are done. This is the
  * signal that stream processing is complete. This actor then terminates
  * itself.
  *
  * @param mediaManager      reference to the media manager actor
  * @param streamDone        the promise to signal the completion of stream
  *                          processing
  * @param maxBufferSize     the maximum size of internal result buffers
  * @param seqNo             a sequence number; this is added to results
  * @param sinkUpdateService the service to update the sink state
  */
class ScanSinkActor(mediaManager: ActorRef, streamDone: Promise[Unit], maxBufferSize: Int,
                    seqNo: Int, private[media] val sinkUpdateService: ScanSinkUpdateService)
  extends Actor:
  /** The current state of the sink. */
  private var sinkState = ScanSinkUpdateServiceImpl.InitialState

  /**
    * Creates a new instance of ''ScanSinkActor'' with the most relevant
    * parameters. This constructor is used for production while the other one
    * is for testing purposes.
    *
    * @param mediaManager  reference to the media manager actor
    * @param streamDone    the promise to signal the completion of stream
    *                      processing
    * @param maxBufferSize the maximum size of internal result buffers
    * @param seqNo         a sequence number; this is added to results
    * @return the new instance
    */
  def this(mediaManager: ActorRef, streamDone: Promise[Unit], maxBufferSize: Int, seqNo: Int) =
    this(mediaManager, streamDone, maxBufferSize, seqNo, ScanSinkUpdateServiceImpl)

  override def receive: Receive =
    case Init =>
      sender() ! Ack

    case res: EnhancedMediaScanResult =>
      switchState(sinkUpdateService.handleNewScanResult(res, sender(), maxBufferSize))

    case info: MediumInfo =>
      switchState(sinkUpdateService.handleNewMediumInfo(info, sender(), maxBufferSize))

    case Ack if sender() == mediaManager =>
      switchState(sinkUpdateService.handleResultAck(maxBufferSize))

    case ScanResultsComplete =>
      switchState(sinkUpdateService.handleScanResultsDone(maxBufferSize))

    case MediaInfoComplete =>
      switchState(sinkUpdateService.handleMediaInfoDone(maxBufferSize))

    case StreamFailure(ex) =>
      streamDone.failure(ex)
      context stop self

  /**
    * Uses the specified ''State'' to update this actor's internal state and
    * performs the necessary actions caused by this transition.
    *
    * @param state the ''State'' describing the transition
    */
  private def switchState(state: State[ScanSinkState, SinkTransitionMessages]): Unit =
    val (next, msg) = state(sinkState)
    msg.actorsToAck foreach (_ ! Ack)
    if msg.results.nonEmpty then
      mediaManager ! CombinedResults(msg.results, seqNo)
    sinkState = next

    if msg.processingDone then
      streamDone.success(())
      context stop self
