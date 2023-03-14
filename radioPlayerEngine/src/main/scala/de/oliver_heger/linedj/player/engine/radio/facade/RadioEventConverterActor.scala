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

package de.oliver_heger.linedj.player.engine.radio.facade

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.actors.EventManagerActor
import de.oliver_heger.linedj.player.engine.radio._

/**
  * An actor implementation that converts events from the audio player to the
  * events of the [[RadioEvent]] hierarchy.
  *
  * An instance of this implementation is passed to the actors used by the
  * radio player that generate audio player events. The relevant events are
  * then mapped to radio events.
  *
  * To make this mapping possible, an actor instance is initialized with an
  * [[EventManagerActor]] reference. This reference is used to publish the
  * converted events, but also to listen for incoming [[RadioEvent]]s. That way
  * the actor can keep track on the currently played radio source, which is
  * needed to produce correct radio events.
  */
private object RadioEventConverterActor {
  /**
    * The base trait for the commands supported by this actor implementation.
    */
  sealed trait RadioEventConverterCommand

  /**
    * A command that triggers the conversion of a [[PlayerEvent]]. If this
    * event is relevant, the actor generates a corresponding [[RadioEvent]] and
    * passes it to the associated listener.
    *
    * @param event the event to be converted
    */
  case class ConvertPlayerEvent(event: PlayerEvent) extends RadioEventConverterCommand

  /**
    * A command that can be used to query the listener for player events. This
    * listener can then be passed to actors used by the audio player. It
    * handles events by converting them and propagating them to the listener of
    * radio events.
    *
    * @param client the client actor waiting for the response
    */
  case class GetPlayerListener(client: ActorRef[PlayerListenerReference]) extends RadioEventConverterCommand

  /**
    * A command that stops an actor of this class.
    */
  case object Stop extends RadioEventConverterCommand

  /**
    * An internal command for processing a [[RadioEvent]]. This is used to
    * keep track on the currently played source.
    *
    * @param event the [[RadioEvent]]
    */
  private case class HandleRadioEvent(event: RadioEvent) extends RadioEventConverterCommand

  /**
    * A message class sent by this actor as a response of a
    * [[GetPlayerListener]] command. It contains the reference to the actor
    * that acts as listener for player events.
    *
    * @param listener the listener for player events
    */
  case class PlayerListenerReference(listener: ActorRef[PlayerEvent])

  /**
    * Returns a behavior to create an instance of this actor. Events processed
    * by this actor are forwarded to the specified radio event listener.
    *
    * @param eventManager the event manager actor
    * @return the behavior to create an instance of this actor
    */
  def apply(eventManager: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]]):
  Behavior[RadioEventConverterCommand] =
    Behaviors.setup[RadioEventConverterCommand] { context =>
      val playerListener: ActorRef[PlayerEvent] = context.messageAdapter(event => ConvertPlayerEvent(event))
      val radioListener: ActorRef[RadioEvent] = context.messageAdapter(event => HandleRadioEvent(event))
      eventManager ! EventManagerActor.RegisterListener(radioListener)

      def handleMessages(optCurrentSource: Option[RadioSource]): Behavior[RadioEventConverterCommand] =
        Behaviors.receiveMessage {
          case GetPlayerListener(client) =>
            client ! PlayerListenerReference(playerListener)
            Behaviors.same

          case ConvertPlayerEvent(event) =>
            convertEvent(event, optCurrentSource) foreach { event =>
              eventManager ! EventManagerActor.Publish(event)
            }
            Behaviors.same

          case HandleRadioEvent(event) =>
            event match {
              case RadioSourceChangedEvent(source, _) =>
                handleMessages(Some(source))
              case _ => Behaviors.same
            }

          case Stop =>
            Behaviors.stopped
        }

      handleMessages(None)
    }

  /**
    * Converts relevant player events to their counterparts from the
    * [[RadioEvent]] hierarchy. A return value of ''None'' means that this
    * event can be ignored.
    *
    * @param playerEvent      the [[PlayerEvent]] to convert
    * @param optCurrentSource the optional current radio source
    * @return an ''Option'' with the converted event
    */
  private def convertEvent(playerEvent: PlayerEvent, optCurrentSource: Option[RadioSource]): Option[RadioEvent] =
    playerEvent match {
      case PlaybackProgressEvent(bytesProcessed, playbackTime, currentSource, time) =>
        Some(RadioPlaybackProgressEvent(convertSource(currentSource, optCurrentSource), bytesProcessed, playbackTime,
          time))

      case PlaybackContextCreationFailedEvent(source, time) =>
        Some(RadioPlaybackContextCreationFailedEvent(convertSource(source, optCurrentSource), time))

      case PlaybackErrorEvent(source, time) =>
        Some(RadioPlaybackErrorEvent(convertSource(source, optCurrentSource), time))

      case _ => None
    }

  /**
    * Constructs a [[RadioSource]] for the given [[AudioSource]].
    *
    * @param audioSource      the audio source
    * @param optCurrentSource the optional current radio source
    * @return the corresponding radio source
    */
  private def convertSource(audioSource: AudioSource, optCurrentSource: Option[RadioSource]): RadioSource =
    optCurrentSource getOrElse RadioSource(uri = audioSource.uri)
}

