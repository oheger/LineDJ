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

package de.oliver_heger.linedj.player.engine.radio

import de.oliver_heger.linedj.player.engine.AudioSourceFinishedEvent
import de.oliver_heger.linedj.player.engine.radio.CurrentMetadata.extractStreamTitle

import java.time.LocalDateTime
import scala.concurrent.duration.FiniteDuration

/**
  * The base trait for all events generated by the radio player engine.
  *
  * Events can be used to keep track of the state of the radio player engine,
  * e.g. when playback of a new radio source starts or a replacement source
  * is enabled temporarily..
  */
sealed trait RadioEvent {
  /** The time when this event was generated. */
  val time: LocalDateTime
}

/**
  * A player event indicating a change of the current radio source.
  *
  * Events of this type are generated by the radio source actor. A radio source
  * may have a different URI than the resulting audio source passed to the
  * playback actor. Therefore, having a specialized event makes sense. Note
  * that there is only a change event and not a pair of started/finished
  * events. The end of a radio source is typically less important, and it can
  * be determined by the corresponding [[AudioSourceFinishedEvent]].
  *
  * @param source the radio source that becomes the current source
  * @param time   the time when this event was generated
  */
case class RadioSourceChangedEvent(source: RadioSource,
                                   override val time: LocalDateTime = LocalDateTime.now())
  extends RadioEvent

/**
  * A player event indicating that the radio player should switch to a
  * replacement source.
  *
  * Events of this type are generated by the scheduler actor that checks the
  * exclusions defined for radio sources. When the current source enters a
  * forbidden timeslot, a replacement source is selected, and this event is
  * fired. The receiver of the event should then trigger the audio player to
  * actually switch to the replacement source.
  *
  * @param source            the current source selected by the user
  * @param replacementSource the replacement source
  * @param time              the time when this event was generated
  */
case class RadioSourceReplacementStartEvent(source: RadioSource, replacementSource: RadioSource,
                                            override val time: LocalDateTime = LocalDateTime.now())
  extends RadioEvent

/**
  * A player event indicating that no longer a replacement source should be
  * played, but the player should switch again to the original source.
  *
  * The scheduler actor keeps track on a replacement source being played. When
  * the forbidden timeslot of the original source is over it generates an event
  * of this type. The receiver should then trigger the audio player to start
  * playback of the original source, which is the source in this event.
  *
  * @param source the original source to be played
  * @param time   the time when this event was generated
  */
case class RadioSourceReplacementEndEvent(source: RadioSource,
                                          override val time: LocalDateTime = LocalDateTime.now())
  extends RadioEvent

/**
  * A player event indicating that an error occured when playing the current
  * radio source.
  *
  * The playback for this source will be aborted. An event listener may take
  * actions to recover playback.
  *
  * @param source the affected radio source
  * @param time   the time when this event was generated
  */
case class RadioSourceErrorEvent(source: RadioSource,
                                 override val time: LocalDateTime = LocalDateTime.now())
  extends RadioEvent

/**
  * A player event indicating a progress during playback.
  *
  * Events of this type are generated in regular intervals. They can be used
  * for instance to display playback time or other statistics.
  *
  * @param source         the current radio source
  * @param bytesProcessed the number of bytes processed for the current source
  * @param playbackTime   the accumulated playback duration
  * @param time           the time when this event was generated
  */
case class RadioPlaybackProgressEvent(source: RadioSource,
                                      bytesProcessed: Long,
                                      playbackTime: FiniteDuration,
                                      override val time: LocalDateTime = LocalDateTime.now()) extends RadioEvent

/**
  * A player event indicating a failure when creating the playback context for
  * the current radio source. This typically means that the audio format of the
  * radio stream is not supported.
  *
  * @param source the current radio source
  * @param time   the time when this event was generated
  */
case class RadioPlaybackContextCreationFailedEvent(source: RadioSource,
                                                   override val time: LocalDateTime = LocalDateTime.now())
  extends RadioEvent

/**
  * A player event indicating an error during playback. This typically
  * indicates a problem with the input stream read by the radio player.
  *
  * @param source the current radio source
  * @param time   the time when this event was generated
  */
case class RadioPlaybackErrorEvent(source: RadioSource,
                                   override val time: LocalDateTime = LocalDateTime.now()) extends RadioEvent

/**
  * A trait to represent metadata extracted from a radio stream.
  *
  * A subclass of this trait is contained in a [[RadioMetadataEvent]]. The
  * different classes are used to distinguish between actual metadata and the
  * state that a specific stream does not support metadata.
  */
sealed trait RadioMetadata

/**
  * A concrete subtype of radio metadata indicating that the current radio
  * source does not support metadata.
  */
case object MetadataNotSupported extends RadioMetadata

object CurrentMetadata {
  /** Regular expression to extract the StreamTitle field from metadata. */
  private val RegStreamTitle = "StreamTitle='([^']*)'".r

  /**
    * Tries to extract the ''StreamTitle'' field from the given metadata
    * string.
    *
    * @param data the metadata
    * @return an ''Option'' with the extracted ''StreamTitle'' field
    */
  private def extractStreamTitle(data: String): Option[String] =
    RegStreamTitle.findFirstMatchIn(data).map(_.group(1))
}

/**
  * A concrete subtype of radio metadata containing actual metadata about what
  * is currently. This could be for instance information about the current
  * song.
  *
  * @param data the full content of the metadata
  */
case class CurrentMetadata(data: String) extends RadioMetadata {
  /**
    * Tries to parse the information about the current title (i.e. the
    * ''StreamTitle'' field from the metadata. If it is found, the content of
    * this field is returned. Otherwise, this function returns the full
    * metadata content.
    *
    * @return the title part of the metadata
    */
  def title: String = extractStreamTitle(data) getOrElse data
}

/**
  * A player event indicating a change of the metadata for the current radio
  * source. If metadata is supported, this can be used to display the current
  * title and artist in the radio player UI.
  *
  * @param metadata the [[RadioMetadata]]
  * @param time     the time when this event was generated
  */
case class RadioMetadataEvent(metadata: RadioMetadata,
                              override val time: LocalDateTime = LocalDateTime.now()) extends RadioEvent
