/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.radio.impl

import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import spray.json.*

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
  * An actor that manages a WebSocket connection to the radio player server
  * in order to receive radio messages.
  *
  * The radio player server sends low-level messages about state changes of
  * the radio player via a WebSocket endpoint. This actor establishes the
  * connection, parses incoming JSON messages, and forwards them to a listener
  * actor. The listener is responsible for the further processing and
  * aggregation of these messages.
  *
  * The actor expects a [[Stop]] message to close the WebSocket connection and
  * clean up its resources.
  */
object RadioMessageActor extends RadioModel.RadioJsonSupport:
  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /**
    * The base trait for commands processed by the [[RadioMessageActor]].
    */
  sealed trait Command

  /**
    * Command that tells the actor to close the WebSocket connection and stop.
    */
  case object Stop extends Command

  /**
    * An internal command that carries a message received via the WebSocket
    * connection.
    *
    * @param message the received message
    */
  private final case class ReceivedMessage(message: RadioModel.RadioMessage) extends Command

  /**
    * A trait abstracting the establishment of a WebSocket connection to the
    * radio player server. This abstraction allows tests to replace the actual
    * connection with a stub.
    */
  trait ConnectionFactory:
    /**
      * Establishes a WebSocket connection to the given URI.
      *
      * @param uri       the URI of the WebSocket endpoint
      * @param onMessage the function to be invoked for each message received
      * @param system    the actor system
      * @return a handle for the established connection
      */
    def connect(uri: Uri, onMessage: RadioModel.RadioMessage => Unit)
               (using system: classic.ActorSystem): Connection

  /**
    * A handle for an established WebSocket connection. It allows closing the
    * connection.
    */
  trait Connection:
    /**
      * Closes the WebSocket connection.
      */
    def close(): Unit

  /**
    * The default [[ConnectionFactory]] implementation based on Pekko HTTP.
    */
  final val defaultConnectionFactory: ConnectionFactory = new PekkoConnectionFactory

  /**
    * A factory trait for creating new instances of the [[RadioMessageActor]].
    */
  trait Factory:
    /**
      * Creates the behavior of a new [[RadioMessageActor]] that connects to
      * the radio player server at the given URI and forwards all received
      * messages to the given listener. The actor is typically spawned as a
      * child of a controller actor.
      *
      * @param serverUri         the URI of the radio player server
      * @param listener          the listener to receive the messages
      * @param connectionFactory the factory for the WebSocket connection
      * @return the behavior for the new actor instance
      */
    def apply(serverUri: String,
              listener: ActorRef[RadioModel.RadioMessage],
              connectionFactory: ConnectionFactory = defaultConnectionFactory): Behavior[Command]

  /**
    * A default [[Factory]] instance for creating a new actor instance.
    */
  final val newInstance: Factory = 
    (serverUri: String, listener: ActorRef[RadioModel.RadioMessage], connectionFactory: ConnectionFactory) =>
      setUpActor(serverUri, listener, connectionFactory)

  /**
    * Returns the behavior for a new actor instance.
    *
    * @param serverUri         the URI of the radio player server
    * @param listener          the listener to receive the messages
    * @param connectionFactory the factory for the WebSocket connection
    * @return the behavior for the new actor instance
    */
  private def setUpActor(serverUri: String,
                         listener: ActorRef[RadioModel.RadioMessage],
                         connectionFactory: ConnectionFactory): Behavior[Command] =
    Behaviors.setup: context =>
      given classic.ActorSystem = context.system.toClassic
      val uri = webSocketUri(serverUri)
      val connection = connectionFactory.connect(uri, message => context.self ! ReceivedMessage(message))
      handleCommand(context, listener, connection)

  /**
    * Returns the behavior to process commands received by the actor.
    *
    * @param context    the actor context
    * @param listener   the listener to receive the messages
    * @param connection the handle for the WebSocket connection
    * @return the behavior to process commands
    */
  private def handleCommand(context: ActorContext[Command],
                            listener: ActorRef[RadioModel.RadioMessage],
                            connection: Connection): Behavior[Command] =
    Behaviors.receiveMessage:
      case Stop =>
        context.log.info("Stopping RadioMessageActor; closing WebSocket connection.")
        connection.close()
        Behaviors.stopped

      case ReceivedMessage(message) =>
        listener ! message
        Behaviors.same

  /**
    * Derives the URI of the WebSocket endpoint from the URI of the radio
    * player server. The scheme of the server URI is replaced by the
    * corresponding WebSocket scheme.
    *
    * @param serverUri the URI of the radio player server
    * @return the URI of the WebSocket endpoint
    */
  private[impl] def webSocketUri(serverUri: String): Uri =
    val wsBase =
      if serverUri.startsWith("https://") then s"wss://${serverUri.substring("https://".length)}"
      else if serverUri.startsWith("http://") then s"ws://${serverUri.substring("http://".length)}"
      else serverUri
    Uri(s"$wsBase${PlayerService.EventsPath}")

  /**
    * The default [[ConnectionFactory]] implementation that uses Pekko HTTP to
    * establish a WebSocket connection.
    */
  private class PekkoConnectionFactory extends ConnectionFactory:
    override def connect(uri: Uri, onMessage: RadioModel.RadioMessage => Unit)
                        (using system: classic.ActorSystem): Connection =
      given ExecutionContext = system.dispatcher
      val sink = Sink.foreach[Message]:
        case text: TextMessage =>
          text.textStream.runFold("")(_ + _).onComplete:
            case Success(json) => parseMessage(json).foreach(onMessage)
            case Failure(exception) => log.error("Could not read web socket message.", exception)
        case _ =>
          log.debug("Ignoring non-text web socket message.")
      
      val source = Source.maybe[Message]
      val flow = Flow.fromSinkAndSourceMat(sink, source)(Keep.right)
      val (response, stopPromise) = Http().singleWebSocketRequest(WebSocketRequest(uri), flow)
      response.onComplete:
        case Success(_) => log.info("Web socket connection to radio player server established: {}", uri)
        case Failure(exception) => log.error(s"Could not establish web socket connection to '$uri'.", exception)
      () => stopPromise.trySuccess(None)

  /**
    * Parses a JSON string received via the WebSocket connection to a
    * [[RadioModel.RadioMessage]].
    *
    * @param json the JSON string
    * @return the parsed message, or ''None'' if parsing failed
    */
  private def parseMessage(json: String): Option[RadioModel.RadioMessage] =
    try Some(json.parseJson.convertTo[RadioModel.RadioMessage])
    catch
      case exception: Exception =>
        log.error(s"Could not parse web socket message: $json", exception)
        None
end RadioMessageActor
