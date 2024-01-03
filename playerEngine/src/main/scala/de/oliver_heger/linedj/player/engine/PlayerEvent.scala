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

package de.oliver_heger.linedj.player.engine

import java.time.LocalDateTime
import scala.concurrent.duration.FiniteDuration

/**
  * The base trait for all events generated by the audio player engine.
  *
  * Events can be used to keep track of the state of the player engine, e.g.
  * when playback of a new source starts or finishes.
  */
sealed trait PlayerEvent:
  /** The time when this event was generated. */
  val time: LocalDateTime

/**
  * A player event indicating the start of a new audio source.
  *
  * Events of this type are generated by the playback actor when playback of a
  * new source starts.
  *
  * @param source the audio source
  * @param time   the time when this event was generated
  */
case class AudioSourceStartedEvent(source: AudioSource,
                                   override val time: LocalDateTime = LocalDateTime.now())
  extends PlayerEvent

/**
  * A player event indicating the end of an audio source.
  *
  * Events of this type are generated by the playback actor when the end of an
  * audio source is reached.
  *
  * @param source the audio source
  * @param time   the time when this event was generated
  */
case class AudioSourceFinishedEvent(source: AudioSource,
                                    override val time: LocalDateTime = LocalDateTime.now())
  extends PlayerEvent

/**
  * A player event indicating an error if the playback context for the current
  * audio source cannot be created. This typically means that playback is
  * aborted.
  *
  * @param source the affected audio source
  * @param time   the time when this event was generated
  */
case class PlaybackContextCreationFailedEvent(source: AudioSource,
                                              override val time: LocalDateTime = LocalDateTime
                                                .now())
  extends PlayerEvent

/**
  * A player event indicating an error during playback. This typically
  * indicates a problem with the input stream read by the player.
  *
  * @param source the affected audio source
  * @param time   the time when this event was generated
  */
case class PlaybackErrorEvent(source: AudioSource,
                              override val time: LocalDateTime = LocalDateTime
                                .now())
  extends PlayerEvent

/**
  * A player event indicating a progress during playback.
  *
  * Events of this type are generated about every second by the playback actor.
  * They can be used for instance to display the playback time or a progress
  * bar.
  *
  * @param bytesProcessed the number of bytes processed for the current source
  * @param playbackTime   the accumulated playback duration
  * @param currentSource  the ''AudioSource'' that is currently played
  * @param time           the time when this event was generated
  */
case class PlaybackProgressEvent(bytesProcessed: Long,
                                 playbackTime: FiniteDuration,
                                 currentSource: AudioSource,
                                 override val time: LocalDateTime = LocalDateTime.now())
  extends PlayerEvent
