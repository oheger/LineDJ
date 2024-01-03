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

package de.oliver_heger.linedj.io.stream

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString
import scalaz.State
import scalaz.State._

/**
  * A data class representing the current state of the
  * [[StreamPullReadService]].
  *
  * The state has to keep track on data arrived from the stream and pending
  * requests for data. If both is available, data can be sent to the requesting
  * client. If all data has been consumed, an ACK can be sent to the stream, so
  * that the next portion of data can be produced.
  *
  * @param dataAvailable  the chunk of data that is currently available
  * @param dataToSend     a chunk of data that can be sent to the client
  * @param ackPending     an actor to which an ACK message needs to be sent
  * @param requestClient  the client currently requesting data
  * @param requestSize    the size of the current request
  * @param errorClient    an actor which sent an illegal request
  * @param streamComplete flag whether the stream is already done
  */
case class StreamPullState(dataAvailable: Option[ByteString],
                           dataToSend: Option[ByteString],
                           ackPending: Option[ActorRef],
                           requestClient: Option[ActorRef],
                           requestSize: Int,
                           errorClient: Option[ActorRef],
                           streamComplete: Boolean)

/**
  * A data class representing notifications to be sent after an update of the
  * current [[StreamPullState]].
  *
  * The class stores information about actors that need to be sent messages and
  * the concrete messages to be sent.
  *
  * @param dataReceiver the actor receiving data
  * @param data         the current chunk of data
  * @param ack          the actor receiving an ACK message
  * @param error        an actor to which an error message must be sent
  */
case class StreamPullNotifications(dataReceiver: Option[ActorRef],
                                   data: ByteString,
                                   ack: Option[ActorRef],
                                   error: Option[ActorRef]):
  /**
    * Returns a flag whether the end of the stream is reached. This function
    * returns a meaningful result only if a data receiver is defined. In this
    * case, it allows determining the message to be sent to this receiver:
    * either a chunk of data or the message that the stream is now complete.
    *
    * @return '''true''' if the end of the stream has been reached; '''false'''
    *         otherwise
    */
  def isEndOfStream: Boolean = data.isEmpty

  /**
    * Sends a message to the data receiver if it is defined. Depending on the
    * current state, either a chunk of data or an end-of-stream message is
    * sent; the concrete messages are under the control of the caller.
    *
    * @param sender      the sender of this message
    * @param f           a function to generate the data message
    * @param endOfStream the end-of-stream message
    * @tparam A the type of the data message
    */
  def sendData[A](sender: ActorRef, f: ByteString => A)(endOfStream: => Any): Unit =
    dataReceiver foreach { rec =>
      val msg = if isEndOfStream then endOfStream else f(data)
      rec.tell(msg, sender)
    }

  /**
    * Sends an ACK message to the stream actor if it is defined. The concrete
    * message that is sent is under the control of the caller.
    *
    * @param sender the sender of this message
    * @param ackMsg the message to be send as an ACK signal
    */
  def sendAck(sender: ActorRef, ackMsg: => Any): Unit =
    ack foreach (_.tell(ackMsg, sender))

  /**
    * Sends an error message to an illegal client actor if one is defined.
    *
    * @param sender the sender of this message
    * @param errMsg the error message to be sent
    */
  def sendError(sender: ActorRef, errMsg: => Any): Unit =
    error foreach (_.tell(errMsg, sender))

/**
  * A trait defining an interface for a service that bridges between the
  * push-based stream model and a request-response-based programming model.
  *
  * This service is useful if data is to be requested from a stream using
  * single requests. Each request yields a chunk of data. Backpressure is
  * applied if no more requests come in.
  *
  * In some parts of the system, there is such a paradigm-change in data
  * processing; data comes from a stream - e.g. when reading from a file -, but
  * it is processed by a component that requests data on its own when it is
  * ready to process it.
  */
trait StreamPullReadService:
  /** The type for updates of the internal state. */
  type StateUpdate[A] = State[StreamPullState, A]

  /**
    * Updates the state for a request for another chunk of data. The passed in
    * data size is an upper limit; the data chunk passed to the receiver might
    * have at most this size.
    *
    * @param client the client requesting the data
    * @param size   the desired data size
    * @return the updated state
    */
  def dataRequested(client: ActorRef, size: Int): StateUpdate[Unit]

  /**
    * Updates the state if a chunk of data is received from the stream. If
    * there is already a request for data, the request can now be served.
    *
    * @param data   the chunk of data from the stream
    * @param sender the actor to send an ACK to
    * @return the updated state
    */
  def nextData(data: ByteString, sender: ActorRef): StateUpdate[Unit]

  /**
    * Updates the state if the end of the stream has been reached. The next
    * message passed to the receiver actor will be an end-of-stream message.
    *
    * @return the updated state
    */
  def endOfStream(): StateUpdate[Unit]

  /**
    * Returns an object with notifications that have to be sent to different
    * receivers and updates the state to reflect that the notifications have
    * been processed. This function should be called after each state change.
    *
    * @return the updated state and an object with notifications to be sent
    */
  def evalNotifications(): StateUpdate[StreamPullNotifications]

  /**
    * Updates the state for a request for another chunk of data and computes
    * the notifications to be sent for this update. This is a combination of
    * the functions ''dataRequested()'' and ''evalNotifications()''.
    *
    * @param client the client requesting the data
    * @param size   the desired data state
    * @return the updated state and the notifications to send
    */
  def handleDataRequest(client: ActorRef, size: Int): StateUpdate[StreamPullNotifications] =
    for
      _ <- dataRequested(client, size)
      not <- evalNotifications()
    yield not

  /**
    * Updates the state for an incoming chunk of data from the stream and
    * computes the notifications to be sent for this update. This is a
    * combination of the functions ''nextData()'' and ''evalNotifications()''.
    *
    * @param data   the chunk of data from the stream
    * @param sender the actor to send an ACK to
    * @return the updated state and the notifications to send
    */
  def handleNextData(data: ByteString, sender: ActorRef): StateUpdate[StreamPullNotifications] =
    for
      _ <- nextData(data, sender)
      not <- evalNotifications()
    yield not

  /**
    * Updates the state for an end-of-stream notifications and computes the
    * notifications to be sent for this update. This is a combination of the
    * functions ''endOfStream()'' and ''evalNotifications()''.
    *
    * @return the updated state and the notifications to send
    */
  def handleEndOfStream(): StateUpdate[StreamPullNotifications] =
    for
      _ <- endOfStream()
      not <- evalNotifications()
    yield not

object StreamPullReadServiceImpl extends StreamPullReadService:
  /** Constant for a state object with initial values. */
  final val InitialState: StreamPullState = StreamPullState(dataAvailable = None, dataToSend = None,
    ackPending = None, requestClient = None, requestSize = 0, errorClient = None, streamComplete = false)

  /** A notifications object that does not trigger any notifications. */
  private val NoopNotifications = StreamPullNotifications(None, ByteString.empty, None, None)

  override def dataRequested(client: ActorRef, size: Int): StateUpdate[Unit] = modify { s =>
    if s.requestClient.isDefined then
      s.copy(errorClient = Some(client))
    else
      streamUpdate(s, s.dataAvailable, Some(client), size, streamComplete = s.streamComplete)
  }

  override def nextData(data: ByteString, sender: ActorRef): StateUpdate[Unit] = modify { s =>
    val optData = Some(data)
    val (dataToSend, dataAvailable) = s.requestClient.fold[(Option[ByteString],
      Option[ByteString])]((None, optData))(_ => serveRequestIfPossible(optData, s.requestSize, streamComplete = false))
    val nextSize = nextRequestSize(dataToSend, s.requestSize)
    s.copy(dataAvailable = dataAvailable, dataToSend = dataToSend, ackPending = Some(sender), requestSize = nextSize)
  }

  override def endOfStream(): StateUpdate[Unit] = modify { s =>
    streamUpdate(s, s.dataAvailable, s.requestClient, s.requestSize, streamComplete = true)
  }

  override def evalNotifications(): StateUpdate[StreamPullNotifications] = State { s =>
    s.dataToSend.fold((resetErrorClient(s), addErrorActor(NoopNotifications, s.errorClient))) { data =>
      val canAck = s.dataAvailable.isEmpty
      val nextState = s.copy(dataToSend = None, requestClient = None, errorClient = None,
        ackPending = if canAck then None else s.ackPending)
      (nextState, StreamPullNotifications(s.requestClient, data, if canAck then s.ackPending else None, s.errorClient))
    }
  }

  /**
    * Checks whether a request for a chunk of data can be served and returns a
    * tuple with the chunk of data to be sent and the remaining data. This
    * function checks whether data is available. If so, the request can be
    * answered, but the data available may be split if it is too large. If no
    * data is available, empty options are returned meaning that the current
    * request cannot be served.
    *
    * @param dataAvailable  the data that is currently available
    * @param requestSize    the requested size of data
    * @param streamComplete flag if the stream is complete
    * @return a tuple with the optional data to send back to the client and
    *         the remaining data available after serving the request
    */
  private def serveRequestIfPossible(dataAvailable: Option[ByteString], requestSize: Int, streamComplete: Boolean):
  (Option[ByteString], Option[ByteString]) =
    dataAvailable match
      case avail@Some(data) if data.length <= requestSize =>
        (avail, None)
      case Some(data) =>
        val (current, remaining) = data.splitAt(requestSize)
        (Some(current), Some(remaining))
      case None if streamComplete =>
        (Some(ByteString.empty), None)
      case None =>
        (None, None)

  /**
    * Calculates the request size for the updated state. If data can be sent to
    * the request client, the request size is reset.
    *
    * @param optDataToSend an ''Option'' with the data to be sent
    * @param currentSize   the current request size
    * @return the updated request size
    */
  private def nextRequestSize(optDataToSend: Option[ByteString], currentSize: Int): Int =
    if optDataToSend.isDefined then 0 else currentSize

  /**
    * Handles data updates received from the stream. This can be a new chunk of
    * data or the end-of-stream message. In the latter case, the data is
    * represented by an empty data chunk.
    *
    * @param optData        optional data currently available from the stream
    * @param optClient      optional client actor to be acknowledged
    * @param requestSize    the size of the data requested
    * @param streamComplete flag if the stream is complete
    * @return the updated state
    */
  private def streamUpdate(s: StreamPullState, optData: Option[ByteString], optClient: Option[ActorRef],
                           requestSize: Int, streamComplete: Boolean): StreamPullState =
    val (dataToSend, dataAvailable) = optClient.fold[(Option[ByteString],
      Option[ByteString])]((None, optData))(_ => serveRequestIfPossible(optData, requestSize, streamComplete))
    val nextSize = nextRequestSize(dataToSend, requestSize)
    s.copy(dataAvailable = dataAvailable, dataToSend = dataToSend, requestClient = optClient, requestSize = nextSize,
      streamComplete = streamComplete)

  /**
    * Returns a state with the error client reset. This is an optimization to
    * prevent that always new state objects are created, which is only
    * necessary in the rare error case.
    *
    * @param state the original state
    * @return the state with the error client reset
    */
  private def resetErrorClient(state: StreamPullState): StreamPullState =
    if state.errorClient.isDefined then state.copy(errorClient = None)
    else state

  /**
    * Returns an updated notifications object that contains the given error
    * actor if it is defined; otherwise, the same notifications instance is
    * returned. This is an optimization to prevent unnecessary instance
    * creation in the default case that there is no error.
    *
    * @param notifications the original notifications object
    * @param optError      the optional error actor
    * @return the updated notifications object
    */
  private def addErrorActor(notifications: StreamPullNotifications, optError: Option[ActorRef]):
  StreamPullNotifications =
    optError.fold(notifications)(_ => notifications.copy(error = optError))
