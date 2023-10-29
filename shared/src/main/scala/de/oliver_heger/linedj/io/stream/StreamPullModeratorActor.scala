/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.io.stream.StreamPullModeratorActor.{Ack, Done, Init}
import org.apache.pekko.actor.{Actor, ActorRef, Status}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString

case object StreamPullModeratorActor {

  /**
    * The ACK message to indicate that a chunk of data from the stream has been
    * processed.
    */
  private case object Ack

  /** The message indicating that the stream has been initialized. */
  private case object Init

  /** The message indicating that the stream is complete. */
  private case object Done

}

/**
  * A base class for actors that can be used as a sink for a stream and provide
  * the data from the stream via a request/response model to a client.
  *
  * This actor class manages the state of a [[StreamPullReadService]] and
  * handles state update accordingly. Concrete sub classes need to provide the
  * actual messages that need to be sent to clients and handle the messages for
  * data requests. They also have to setup the source of the stream that is run
  * against this actor sink.
  */
abstract class StreamPullModeratorActor extends Actor {
  override def preStart(): Unit = {
    super.preStart()
    import context.system
    createSource().runWith(Sink.actorRefWithBackpressure(self, Init, Ack, Done, convertStreamError))
  }

  /**
    * The service that does the actual management of the stream pulling state.
    */
  private val streamPullReadService: StreamPullReadService = StreamPullReadServiceImpl

  /** The current pull state managed by this actor. */
  private var pullState = StreamPullReadServiceImpl.InitialState

  /**
    * Returns the message processing function for this actor. Message
    * processing is split: There are custom messages (especially for data
    * requests) that must be handled by a concrete sub class. Messages sent by
    * the stream are handled by this class.
    *
    * @return the message processing function
    */
  override def receive: Receive = customReceive orElse streamReceive

  /**
    * Handles a request for another chunk of data. The request is delegated to
    * the [[StreamPullReadService]]. It causes an update of the current state
    * and potentially some notifications which have to be sent.
    *
    * @param size   the size of the chunk of data that is desired
    * @param client the actor requesting the data
    */
  protected def dataRequested(size: Int, client: ActorRef = sender()): Unit = {
    updateState(streamPullReadService.handleDataRequest(client, size))
  }

  /**
    * Converts an exception received from the stream into a message that is
    * then passed to this actor. The default implementation transforms the
    * exception into an ''akka.actor.Status.Failure'' message; derived classes
    * can implement an alternative transformation. The resulting message can be
    * handled in the custom ''Receive'' function.
    *
    * @param exception the exception from the stream
    * @return the message to represent this exception
    */
  protected def convertStreamError(exception: Throwable): Any = Status.Failure(exception)

  /**
    * Returns the source of the stream that is to be read by this actor. This
    * method is called when this actor starts. The resulting stream is then
    * materialized with this actor as sink.
    *
    * @return the source of the stream to be processed
    */
  protected def createSource(): Source[ByteString, Any]

  /**
    * Returns the custom ''Receive'' function. This function is invoked during
    * message processing for this actor. Derived classes can implement their
    * specific message handling here. At least the message representing a
    * request for the next chunk of data should be handled.
    *
    * @return the custom message processing function
    */
  protected def customReceive: Receive

  /**
    * Generates a message for a chunk of data received from the stream. A
    * derived class can transform the data into whatever representation it
    * needs.
    *
    * @param data the chunk of data from the stream
    * @return the message to represent this chunk of data
    */
  protected def dataMessage(data: ByteString): Any

  /**
    * Returns a message that indicates the end of the stream for the clients of
    * this actor. When all data has been processed this method is called to
    * obtain the final end-of-stream message.
    *
    * @return the custom representation of the end-of-stream message
    */
  protected def endOfStreamMessage: Any

  /**
    * Returns a message to be sent to a client which has sent an illegal
    * concurrent request.
    *
    * @return the message to send as a response for a concurrent request
    */
  protected def concurrentRequestMessage: Any

  /**
    * A receive function to process the messages that are received from the
    * stream. In reaction to these messages, the internal pulling state is
    * updated accordingly.
    *
    * @return the receive function to handle messages from the stream
    */
  private def streamReceive: Receive = {
    case Init =>
      sender() ! Ack

    case bs: ByteString =>
      updateState(streamPullReadService.handleNextData(bs, sender()))

    case Done =>
      updateState(streamPullReadService.handleEndOfStream())
  }

  /**
    * Handles an update of the stream pull state. The update is applied, and
    * the new state is stored. Then all the necessary notifications are sent.
    *
    * @param update the update to be applied
    */
  private def updateState(update: StreamPullReadServiceImpl.StateUpdate[StreamPullNotifications]): Unit = {
    val (nextState, notifications) = update(pullState)
    pullState = nextState
    notifications.sendData(self, dataMessage)(endOfStreamMessage)
    notifications.sendAck(self, Ack)
    notifications.sendError(self, concurrentRequestMessage)
  }
}
