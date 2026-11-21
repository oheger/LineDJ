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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.player.engine.radio.control.RadioControlActor
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
  * them via WebSockets to clients of the player server.
  *
  * A subset of radio events is supported. They are transformed into
  * [[RadioModel.RadioMessage]] objects and propagated via the flow that is
  * needed for the implementation of WebSockets.
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
    * @param flow          the flow to generate WebSocket messages
    */
  private case class MessageFlowData(eventListener: ActorRef[RadioEvent],
                                     flow: Flow[Message, Message, Any])

  /** A counter for generating unique actor names. */
  private val instanceCounter = new AtomicInteger

  /**
    * Creates a new actor instance that is registered as event listener at the
    * given radio player. This actor manages a [[Flow]] of WebSocket messages.
    * It transforms incoming events and passes them to the message flow.
    *
    * @param player        the radio player
    * @param sourceMap     a map from radio sources to their IDs
    * @param playbackState the current state of the radio player
    * @param system        the actor system
    * @return a ''Future'' with the flow for WebSocket messages
    */
  def newMessageFlow(player: RadioPlayer,
                     sourceMap: Map[RadioSource, String],
                     playbackState: RadioControlActor.CurrentPlaybackState)
                    (implicit system: classic.ActorSystem): Future[Flow[Message, Message, Any]] =
    implicit val typedSystem: ActorSystem[_] = system.toTyped
    implicit val ec: ExecutionContext = typedSystem.executionContext
    implicit val timeout: Timeout = Timeout(5.seconds)
    val messageActor = player.config.playerConfig.actorFactory.createTypedActor(
      messageBehavior(sourceMap, playbackState),
      s"messageActor${instanceCounter.incrementAndGet()}"
    )
    val futFlowData: Future[MessageFlowData] = messageActor.ask(ref => GetMessageFlow(ref))
    futFlowData map { data =>
      player.addEventListener(data.eventListener)
      data.flow
    }

  /**
    * Creates the behavior for a new actor instance that can propagate events
    * as messages to a WebSocket message flow.
    *
    * @param sourceMap     a map from radio sources to their IDs
    * @param playbackState the current state of the radio player
    * @return the behavior for the new actor instance
    */
  private def messageBehavior(sourceMap: Map[RadioSource, String],
                              playbackState: RadioControlActor.CurrentPlaybackState): Behavior[MessageActorCommand] =
    Behaviors.setup { context =>
      context.log.info("Created new instance of MessageActor.")

      val eventListener = context.messageAdapter[RadioEvent] { event =>
        HandleEvent(event)
      }
      val (flow, eventSource) = createFlow(context)
      stateMessages(playbackState, sourceMap).foreach(message => eventSource ! message)

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
    * Tries to generate a message whose payload is the ID of the given radio
    * source. Returns _None_ if the ID cannot be resolved.
    *
    * @param source      the radio source
    * @param messageType the type of the message to generate
    * @param sourceMap   a map from radio sources to their IDs
    * @return an [[Option]] with the generated message
    */
  private def idMessage(source: RadioSource,
                        messageType: String,
                        sourceMap: Map[RadioSource, String]): Option[RadioModel.RadioMessage] =
    sourceMap.get(source).map(id => RadioModel.RadioMessage(messageType, id))

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
    event match
      case RadioSourceChangedEvent(source, _) =>
        idMessage(source, RadioModel.MessageTypeSourceChanged, sourceMap)
      case RadioSourceSelectedEvent(source, _) =>
        idMessage(source, RadioModel.MessageTypeSourceSelected, sourceMap)
      case RadioSourceReplacementStartEvent(_, replacementSource, _) =>
        idMessage(replacementSource, RadioModel.MessageTypeReplacementStart, sourceMap)
      case RadioSourceReplacementEndEvent(source, _) =>
        idMessage(source, RadioModel.MessageTypeReplacementEnd, sourceMap)
      case RadioPlaybackStoppedEvent(source, _) =>
        idMessage(source, RadioModel.MessageTypePlaybackStopped, sourceMap)
      case RadioMetadataEvent(_, metadata, _) =>
        val title = metadata match
          case MetadataNotSupported => ""
          case cm: CurrentMetadata => cm.title
        Some(RadioModel.RadioMessage(RadioModel.MessageTypeTitleInfo, title))
      case _ => None

  /**
    * Generates the messages that describe the current state of the radio
    * player. They are sent to a new client before any events are forwarded,
    * so that the client can initialize its state representation.
    *
    * @param state     the current state of the radio player
    * @param sourceMap a map from radio sources to their IDs
    * @return the messages describing the current player state
    */
  private def stateMessages(state: RadioControlActor.CurrentPlaybackState,
                            sourceMap: Map[RadioSource, String]): List[RadioModel.RadioMessage] =
    val selectedMessage = state.selectedSource.flatMap(idMessage(_, RadioModel.MessageTypeSourceChanged, sourceMap))
    val replacementMessage =
      if state.currentSource == state.selectedSource then None
      else state.currentSource.flatMap(idMessage(_, RadioModel.MessageTypeReplacementStart, sourceMap))
    val titleMessage = state.titleInfo.map(info =>
      RadioModel.RadioMessage(RadioModel.MessageTypeTitleInfo, info.title))
    val stoppedMessage =
      if state.playbackActive then None
      else state.currentSource.orElse(state.selectedSource)
        .flatMap(idMessage(_, RadioModel.MessageTypePlaybackStopped, sourceMap))

    List(selectedMessage, replacementMessage, titleMessage, stoppedMessage).flatten

  /**
    * Creates the ''Flow'' for sending WebSocket messages. The function
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
    * Converts the given radio message to a WebSocket message. The radio
    * message is converted to JSON and transformed to a text message.
    *
    * @param message the radio message to be converted
    * @return the WebSocket message
    */
  private def toWebSocketMessage(message: RadioModel.RadioMessage): Message =
    val messageJson = message.toJson.compactPrint
    TextMessage.Strict(messageJson)
