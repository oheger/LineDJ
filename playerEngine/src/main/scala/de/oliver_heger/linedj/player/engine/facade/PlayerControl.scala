/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.facade

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.player.engine.{PlaybackContextFactory, PlayerConfig}
import de.oliver_heger.linedj.player.engine.impl.{LineWriterActor, PlaybackActor}

object PlayerControl {
  /** The default name of the line writer actor. */
  val LineWriterActorName = "lineWriterActor"

  /**
    * Creates the line writer actor for the audio player. This is a bit tricky
    * because this actor is to be deployed on an alternative dispatcher as it
    * executes blocking operations. An alternative dispatcher is only used if
    * one is defined in the configuration.
    *
    * @param config    the ''PlayerConfig''
    * @param actorName an optional actor name (if a different name than the
    *                  standard name is to be used)
    * @return the reference to the line writer actor
    */
  private[facade] def createLineWriterActor(config: PlayerConfig,
                                            actorName: String = LineWriterActorName): ActorRef =
    config.actorCreator(createLineWriterActorProps(config), actorName)

  /**
    * Creates the properties for the line writer actor to be used by this audio
    * player.
    *
    * @param config the ''PlayerConfig''
    * @return creation properties for the line writer actor
    */
  private[facade] def createLineWriterActorProps(config: PlayerConfig): Props =
    config applyBlockingDispatcher Props[LineWriterActor]
}

/**
  * A trait describing the common interface of and providing some basic
  * functionality for concrete audio player implementations.
  *
  * This trait assumes that a concrete player makes use of a
  * ''PlaybackActor''. It already implements some common methods for typical
  * interactions with this actor. There are also some other helper functions
  * that can be used by player implementations.
  *
  * A class extending this trait has to provide access to the ''PlaybackActor''
  * so that it can be accessed by methods in this trait.
  */
trait PlayerControl {
  /** The actor responsible for playback. */
  protected val playbackActor: ActorRef

  /**
    * Adds the specified ''PlaybackContextFactory'' to this audio player.
    * Before audio files can be played, corresponding factories supporting
    * this audio format have to be added.
    *
    * @param factory the ''PlaybackContextFactory'' to be added
    */
  def addPlaybackContextFactory(factory: PlaybackContextFactory): Unit = {
    playbackActor ! PlaybackActor.AddPlaybackContextFactory(factory)
  }

  /**
    * Removes the specified ''PlaybackContextFactory'' from this audio player.
    * Audio files handled by this factory can no longer be played.
    *
    * @param factory the ''PlaybackContextFactory'' to be removed
    */
  def removePlaybackContextFactory(factory: PlaybackContextFactory): Unit = {
    playbackActor ! PlaybackActor.RemovePlaybackContextFactory(factory)
  }

  /**
    * Starts audio playback. Provided that sufficient audio data has been
    * loaded, playback will start.
    */
  def startPlayback(): Unit = {
    playbackActor ! PlaybackActor.StartPlayback
  }

  /**
    * Stops audio playback. Playback is paused and can be continued by calling
    * ''startPlayback()''.
    */
  def stopPlayback(): Unit = {
    playbackActor ! PlaybackActor.StopPlayback
  }

  /**
    * Skips the current audio source. Playback continues (if enabled) with the
    * next source in the playlist (if any).
    */
  def skipCurrentSource(): Unit = {
    playbackActor ! PlaybackActor.SkipSource
  }
}
