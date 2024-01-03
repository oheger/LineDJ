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

import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.{CancelStreams, StreamCompleted}
import org.apache.pekko.actor.Actor.emptyBehavior
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem}
import org.apache.pekko.stream.KillSwitch

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object AbstractStreamProcessingActor:

  /**
    * A message telling the stream processing actor to cancel all current
    * streams. All ongoing streams are stopped, and result messages are
    * produced. This cancel message is not directly answered.
    */
  case object CancelStreams

  /**
    * An internally used message that is sent to the actor when a stream has
    * been completed. It contains all information required to send a response
    * message to the original caller.
    *
    * @param client       the caller actor
    * @param result       the result to be sent to the caller
    * @param killSwitchID the ID of the kill switch that was used
    */
  private case class StreamCompleted(client: ActorRef, result: Any, killSwitchID: Int)


/**
  * An abstract base actor trait providing generic functionality for the
  * processing of streams.
  *
  * This base trait mainly handles all currently active streams by extending
  * [[CancelableStreamSupport]]. It also offers functionality to handle the
  * ''Future'' result of a stream. It is sent back to the original caller; in
  * case of a failure, there is a possibility to map the exception to a
  * meaningful error result. In any case, the set of currently active streams
  * is updated.
  *
  * This trait comes with its own ''receive'' function which implements the
  * handling of internal messages and a generic handling of cancellation.
  * Derived classes can implement their own message handling in the
  * ''customReceive'' function.
  */
trait AbstractStreamProcessingActor extends Actor:
  this: CancelableStreamSupport =>
  /**
    * An implicit execution context definition. This allows sub classes to
    * operate on futures without having to add their own declarations.
    */
  protected implicit val ec: ExecutionContext = context.dispatcher

  /**
    * Returns this actor's actor system in implicit scope. This is needed to
    * materialize streams.
    *
    * @return the implicit actor system
    */
  protected implicit def system: ActorSystem = context.system

  /**
    * The message processing function. This implementation returns a function
    * that handles custom messages and internal standard messages as well.
    *
    * @return the message processing function
    */
  override def receive: Receive = customReceive orElse internalReceive

  /**
    * The custom receive function. Here derived classes can provide their own
    * message handling.
    *
    * @return the custom receive method
    */
  protected def customReceive: Receive = emptyBehavior

  /**
    * Generic method for executing a stream and processing its result. This
    * method registers the kill switch and registers a completion handler at
    * the ''Future''. The result is sent to ''self'' wrapped in a
    * [[StreamCompleted]] message. In case of a failed ''Future'', the
    * provided mapping function is applied to produce an error result message.
    *
    * @param result     the ''Future'' of stream processing
    * @param ks         the kill switch
    * @param client     the client actor to receive result messages
    * @param errHandler the error handler function
    * @tparam M the type of the result
    */
  protected def processStreamResult[M](result: Future[M], ks: KillSwitch,
                                       client: ActorRef = sender())
                                      (errHandler: Failure[M] => Any): Unit =
    val killSwitchID = registerKillSwitch(ks)
    result onComplete { triedResult =>
      val result = triedResult match
        case Success(r) => r
        case f@Failure(_) => errHandler(f)
      self ! StreamCompleted(client, result, killSwitchID)
    }

  /**
    * Sends the specified result to the original caller. This method is called
    * for each completed stream. This base implementation just sends the result
    * to the caller. Derived classes could override it to execute some
    * additional logic.
    *
    * @param client the client to receive the response
    * @param result the result message
    */
  protected def propagateResult(client: ActorRef, result: Any): Unit =
    client ! result

  /**
    * A receive function that handles the messages which are directly
    * processed by this base actor class.
    *
    * @return the processing function for internal messages
    */
  private def internalReceive: Receive =
    case StreamCompleted(client, result, ksID) =>
      propagateResult(client, result)
      unregisterKillSwitch(ksID)

    case CancelStreams =>
      cancelCurrentStreams()
