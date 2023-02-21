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

package de.oliver_heger.linedj.player.engine.radio.control

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.{actor => classic}
import de.oliver_heger.linedj.player.engine.actors.{LineWriterActor, PlaybackActor, PlaybackContextFactoryActor}
import de.oliver_heger.linedj.player.engine.radio.RadioEvent
import de.oliver_heger.linedj.player.engine.radio.stream.RadioDataSourceActor
import de.oliver_heger.linedj.player.engine.{PlayerConfig, PlayerEvent}

import scala.concurrent.duration._

/**
  * An actor implementation to manage radio sources whose playback caused an
  * error.
  *
  * In case of a playback error, a radio source is marked as excluded. Then it
  * is checked in configurable intervals whether playback is possible again.
  * That way temporary interruptions can be handled.
  *
  * In addition to the main actor controlling the error checks, this module
  * contains a number of helper actors and classes. For each failed radio
  * source, a dedicated actor is created to perform the periodic checks. The
  * checks themselves are rather complex, since every time all relevant actors
  * for audio playback need to be created to test whether a playback context
  * can be created, and audio data is actually processed.
  */
object ErrorStateActor {
  /**
    * An internal data class to hold the actors required for playing audio
    * data.
    *
    * @param sourceActor the actor providing the source to be played
    * @param playActor   the playback actor
    */
  private[control] case class PlaybackActorsFactoryResult(sourceActor: classic.ActorRef,
                                                          playActor: classic.ActorRef)

  /**
    * An internal trait that supports the creation of the actors required for
    * playing audio data. An instance is used to setup the infrastructure to
    * test a radio source.
    */
  private[control] trait PlaybackActorsFactory {
    /**
      * Returns an object with the actors required for playing audio data. This
      * function creates a dummy line writer actor, a source actor, and a
      * playback actor and connects them.
      *
      * @param namePrefix       the prefix for generating actor names
      * @param playerEventActor the event actor for player events
      * @param radioEventActor  the event actor for radio events
      * @param factoryActor     the playback context factory actor
      * @param config           the configuration for the audio player
      * @return the object with the actor references
      */
    def createPlaybackActors(namePrefix: String,
                             playerEventActor: ActorRef[PlayerEvent],
                             radioEventActor: ActorRef[RadioEvent],
                             factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                             config: PlayerConfig): PlaybackActorsFactoryResult
  }

  /**
    * A default implementation of [[PlaybackActorsFactory]]. It uses the
    * actor creator in the provided configuration to create the required actor
    * instances.
    */
  private[control] val playbackActorsFactory = new PlaybackActorsFactory {
    override def createPlaybackActors(namePrefix: String,
                                      playerEventActor: ActorRef[PlayerEvent],
                                      radioEventActor: ActorRef[RadioEvent],
                                      factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                                      config: PlayerConfig): PlaybackActorsFactoryResult = {
      val lineWriter = config.actorCreator.createActor(dummyLineWriterActor(), namePrefix + "LineWriter", None)
      val sourceActor = config.actorCreator.createActor(RadioDataSourceActor(config, radioEventActor),
        namePrefix + "SourceActor")
      val playActor = config.actorCreator.createActor(PlaybackActor(config, sourceActor, lineWriter,
        playerEventActor, factoryActor), namePrefix + "PlaybackActor")
      PlaybackActorsFactoryResult(sourceActor, playActor)
    }
  }

  /**
    * Returns the behavior for a dummy line writer actor. This behavior just
    * simulates writing, so that playback progress events are generated.
    *
    * @return the behavior of a dummy line writer actor
    */
  private def dummyLineWriterActor(): Behavior[LineWriterActor.LineWriterCommand] =
    Behaviors.receiveMessagePartial {
      case LineWriterActor.WriteAudioData(_, _, replyTo) =>
        replyTo ! LineWriterActor.AudioDataWritten(chunkLength = 1024, duration = 1.second)
        Behaviors.same

      case LineWriterActor.DrainLine(_, replayTo) =>
        replayTo ! LineWriterActor.LineDrained
        Behaviors.same
    }
}
