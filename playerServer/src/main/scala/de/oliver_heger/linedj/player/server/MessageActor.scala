/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.util.Timeout
import spray.json.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/**
  * An actor implementation that receives events from the player and forwards
  * them via web sockets to clients of the player server.
  *
  * A subset of radio events is supported. They are transformed into
  * [[RadioModel.RadioMessage]] objects and propagated via the flow that is
  * needed for the implementation of web sockets.
  */
object MessageActor extends RadioModel.RadioJsonSupport:
  /**
    * The base trait for the commands processed by [[MessageActor]].
    */
  private sealed trait MessageActorCommand

  /**
    * A command for requesting the information required to connect the event
    * listener to the message flow.
    *
    * @param replyTo the actor to send the response to
    */
  private case class GetMessageFlow(replyTo: ActorRef[MessageFlowData]) extends MessageActorCommand

  /**
    * A command [[MessageActor]] sends to itself to handle an event that is
    * passed to the event listener actor reference.
    *
    * @param event the [[RadioEvent]] to be handled
    */
  private case class HandleEvent(event: RadioEvent) extends MessageActorCommand

  /**
    * A data class for collecting the information required to set up the
    * message flow.
    *
    * @param eventListener the event listener reference
    * @param flow          the flow to generate web socket messages
    */
  private case class MessageFlowData(eventListener: ActorRef[RadioEvent],
                                     flow: Flow[Message, Message, Any])

  /** A counter for generating unique actor names. */
  private val instanceCounter = new AtomicInteger

  /**
    * Creates a new actor instance that is registered as event listener at the
    * given radio player. This actor manages a [[Flow]] of web socket messages.
    * It transforms incoming events and passes them to the message flow.
    *
    * @param player    the radio player
    * @param sourceMap a map from radio sources to their IDs
    * @param system    the actor system
    * @return a ''Future'' with the flow for web socket messages
    */
  def newMessageFlow(player: RadioPlayer,
                     sourceMap: Map[RadioSource, String])
                    (implicit system: classic.ActorSystem): Future[Flow[Message, Message, Any]] =
    implicit val typedSystem: ActorSystem[_] = system.toTyped
    implicit val ec: ExecutionContext = typedSystem.executionContext
    implicit val timeout: Timeout = Timeout(5.seconds)
    val messageActor = player.config.playerConfig.actorCreator.createActor(messageBehavior(sourceMap),
      s"messageActor${instanceCounter.incrementAndGet()}",
      None)
    val futFlowData: Future[MessageFlowData] = messageActor.ask(ref => GetMessageFlow(ref))
    futFlowData map { data =>
      player.addEventListener(data.eventListener)
      data.flow
    }

  /**
    * Creates the behavior for a new actor instance that can propagate events
    * as messages to a web socket message flow.
    *
    * @param sourceMap a map from radio sources to their IDs
    * @return the behavior for the new actor instance
    */
  private def messageBehavior(sourceMap: Map[RadioSource, String]): Behavior[MessageActorCommand] =
    Behaviors.setup { context =>
      context.log.info("Created new instance of MessageActor.")

      val eventListener = context.messageAdapter[RadioEvent] { event =>
        HandleEvent(event)
      }
      val (flow, eventSource) = createFlow(context)

      def handle(): Behavior[MessageActorCommand] = Behaviors.receiveMessage {
        case GetMessageFlow(replyTo) =>
          replyTo ! MessageFlowData(eventListener, flow)
          Behaviors.same

        case HandleEvent(event) =>
          val optMessage = eventToMessage(event, sourceMap)
          optMessage foreach { message =>
            context.log.debug("Sending radio message '{}' for radio event '{}'.", message, event)
            eventSource ! message
          }
          Behaviors.same
      }

      handle()
    }

  /**
    * Converts the given radio event to a radio message if possible. Supported
    * event types are mapped to messages; for other events, result is ''None''.
    *
    * @param event     the radio event
    * @param sourceMap a map from radio sources to their IDs
    * @return an ''Option'' with the converted message
    */
  private def eventToMessage(event: RadioEvent,
                             sourceMap: Map[RadioSource, String]): Option[RadioModel.RadioMessage] =
    // Tries to generate a message whose payload is the ID of a radio source.
    // Returns None if the ID cannot be resolved.
    def idMessage(source: RadioSource, messageType: String): Option[RadioModel.RadioMessage] =
      sourceMap.get(source).map(id => RadioModel.RadioMessage(messageType, id))

    event match
      case RadioSourceChangedEvent(source, _) =>
        idMessage(source, RadioModel.MessageTypeSourceChanged)
      case RadioSourceSelectedEvent(source, _) =>
        idMessage(source, RadioModel.MessageTypeSourceSelected)
      case RadioSourceReplacementStartEvent(_, replacementSource, _) =>
        idMessage(replacementSource, RadioModel.MessageTypeReplacementStart)
      case RadioSourceReplacementEndEvent(source, _) =>
        idMessage(source, RadioModel.MessageTypeReplacementEnd)
      case RadioPlaybackStoppedEvent(source, _) =>
        idMessage(source, RadioModel.MessageTypePlaybackStopped)
      case RadioMetadataEvent(_, metadata, _) =>
        val title = metadata match
          case MetadataNotSupported => ""
          case cm: CurrentMetadata => cm.title
        Some(RadioModel.RadioMessage(RadioModel.MessageTypeTitleInfo, title))
      case _ => None

  /**
    * Creates the ''Flow'' for sending web socket messages. The function
    * returns a tuple with the actual flow and the actor reference that is
    * used for sending events to the flow.
    *
    * @param context the actor context
    * @return the flow and the actor for sending events
    */
  private def createFlow(context: ActorContext[MessageActorCommand]): (Flow[Message, Message, Any], classic.ActorRef) =
    implicit val classicActorSystem: classic.ActorSystem = context.system.classicSystem
    val sink = Sink.ignore
    val actorRefSource = Source.actorRef[RadioModel.RadioMessage](completionMatcher = PartialFunction.empty,
      failureMatcher = PartialFunction.empty,
      bufferSize = 16,
      overflowStrategy = OverflowStrategy.dropTail)
    val (actorRef, publisher) = actorRefSource.map(toWebSocketMessage)
      .toMat(Sink.asPublisher(fanout = false))(Keep.both).run()
    val flow = Flow.fromSinkAndSource(sink, Source.fromPublisher(publisher))
    (flow, actorRef)

  /**
    * Converts the given radio message to a web socket message. The radio
    * message is converted to JSON and transformed to a text message.
    *
    * @param message the radio message to be converted
    * @return the web socket message
    */
  private def toWebSocketMessage(message: RadioModel.RadioMessage): Message =
    val messageJson = message.toJson.compactPrint
    TextMessage.Strict(messageJson)
