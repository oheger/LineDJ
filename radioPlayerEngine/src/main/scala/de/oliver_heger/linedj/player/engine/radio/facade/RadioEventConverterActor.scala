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
import de.oliver_heger.linedj.player.engine.radio._

/**
  * An actor implementation that converts events from the audio player to the
  * events of the [[RadioEvent]] hierarchy.
  *
  * An instance of this implementation is passed to the actors used by the
  * radio player that generate audio player events. The relevant events are
  * then mapped to radio events.
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
  case class ConvertEvent(event: PlayerEvent) extends RadioEventConverterCommand

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
    * @param radioEventListener the listener for radio events
    * @return the behavior to create an instance of this actor
    */
  def apply(radioEventListener: ActorRef[RadioEvent]): Behavior[RadioEventConverterCommand] =
    Behaviors.setup[RadioEventConverterCommand] { context =>
      val listener: ActorRef[PlayerEvent] = context.messageAdapter(event => ConvertEvent(event))

      def handleMessages(): Behavior[RadioEventConverterCommand] =
        Behaviors.receiveMessage {
          case GetPlayerListener(client) =>
            client ! PlayerListenerReference(listener)
            Behaviors.same

          case ConvertEvent(event) =>
            convertEvent(event) foreach radioEventListener.!
            Behaviors.same

          case Stop =>
            Behaviors.stopped
        }

      handleMessages()
    }

  /**
    * Converts relevant player events to their counterparts from the
    * [[RadioEvent]] hierarchy. A return value of ''None'' means that this
    * event can be ignored.
    *
    * @param playerEvent the [[PlayerEvent]] to convert
    * @return an ''Option'' with the converted event
    */
  private def convertEvent(playerEvent: PlayerEvent): Option[RadioEvent] =
    playerEvent match {
      case PlaybackProgressEvent(bytesProcessed, playbackTime, currentSource, time) =>
        Some(RadioPlaybackProgressEvent(convertSource(currentSource), bytesProcessed, playbackTime, time))

      case PlaybackContextCreationFailedEvent(source, time) =>
        Some(RadioPlaybackContextCreationFailedEvent(convertSource(source), time))

      case PlaybackErrorEvent(source, time) =>
        Some(RadioPlaybackErrorEvent(convertSource(source), time))

      case _ => None
    }

  /**
    * Constructs a [[RadioSource]] for the given [[AudioSource]].
    *
    * @param audioSource the audio source
    * @return the corresponding radio source
    */
  private def convertSource(audioSource: AudioSource): RadioSource = RadioSource(uri = audioSource.uri)
}

