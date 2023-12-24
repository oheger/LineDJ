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

package de.oliver_heger.linedj.player.server.model

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/**
  * A module that defines the API model and JSON conversions for the radio
  * player API.
  */
object RadioModel:
  /**
    * Constant for the message type ''SourceChanged''. This message type
    * indicates that playback of a new source has started, which can be either
    * the current source or a replacement source. The payload is the ID of this
    * new radio source.
    */
  final val MessageTypeSourceChanged = "SourceChanged"

  /**
    * Constant for the message type ''SourceSelected''. This message type
    * indicates that the user has selected another radio source as current
    * source. The payload is the ID of this new radio source.
    */
  final val MessageTypeSourceSelected = "SourceSelected"

  /**
    * Constant for the message type ''ReplacementStart''. The current radio
    * source has been temporarily disabled, and a replacement source is now
    * played. The payload consists of the ID of this replacement source.
    */
  final val MessageTypeReplacementStart = "ReplacementStart"

  /**
    * Constant for the message type ''ReplacementEnd''. Playback switched back
    * to the currently selected radio source. The ID of this source is the
    * payload.
    */
  final val MessageTypeReplacementEnd = "ReplacementEnd"

  /**
    * Constant for the message ''PlaybackStopped''. This message indicates that
    * the user has stopped playback. The payload is the ID of the last source
    * that has been played.
    */
  final val MessageTypePlaybackStopped = "PlaybackStopped"

  /**
    * Constant for the message type ''TitleInfo''. Information about the
    * currently played song is updated. The payload is the updated information.
    */
  final val MessageTypeTitleInfo = "TitleInfo"

  /**
    * A data class representing the current playback status of the radio
    * player.
    *
    * @param enabled a flag whether
    */
  final case class PlaybackStatus(enabled: Boolean)

  /**
    * A data class representing a radio source.
    *
    * @param id            an internal ID assigned to the radio source
    * @param name          the name of the radio source
    * @param ranking       a ranking
    * @param favoriteIndex an index >= 0 if this source is a favorite; 
    *                      otherwise, the value is less than 0
    * @param favoriteName  the name to use for this source if it is a favorite
    */
  final case class RadioSource(id: String,
                               name: String,
                               ranking: Int,
                               favoriteIndex: Int = -1,
                               favoriteName: String = "")

  /**
    * A data class representing the list of [[RadioSource]] objects currently
    * available to the radio player.
    *
    * @param sources the list with existing radio sources
    */
  final case class RadioSources(sources: List[RadioSource])

  /**
    * A data class representing the status of the radio source. It contains the
    * IDs of the currently selected source and a replacement source if defined.
    *
    * @param currentSourceId     the optional ID of the selected radio source
    * @param replacementSourceId the optional ID of the replacement source
    */
  final case class RadioSourceStatus(currentSourceId: Option[String],
                                     replacementSourceId: Option[String])

  /**
    * A data class defining messages sent from the server to interested clients
    * about changes in the state of the radio player. Clients can register for
    * these messages via a WebSockets API.
    *
    * Via this mechanism, a subset of radio events can be received by clients
    * of the player server. The supported messages are simpler than the
    * hierarchy of radio events though - which should also simplify the
    * implementation on client side. A message consists of a type and a payload
    * that depends on the type. Refer to the ''MessageTypeXXX'' constants for
    * the list of supported message types.
    *
    * @param messageType the type of the message as a string constant
    * @param payload     the payload of this message
    */
  final case class RadioMessage(messageType: String,
                                payload: String)

  /**
    * A trait providing JSON converters for the classes of the radio data
    * model. This trait can be mixed into classes that need to do such
    * conversions.
    */
  trait RadioJsonSupport extends SprayJsonSupport with DefaultJsonProtocol:
    implicit val playbackStatusFormat: RootJsonFormat[PlaybackStatus] = jsonFormat1(PlaybackStatus.apply)
    implicit val radioSourceFormat: RootJsonFormat[RadioSource] = jsonFormat5(RadioSource.apply)
    implicit val radioSourcesFormat: RootJsonFormat[RadioSources] = jsonFormat1(RadioSources.apply)
    implicit val radioSourceStatusFormat: RootJsonFormat[RadioSourceStatus] = jsonFormat2(RadioSourceStatus.apply)
    implicit val radioMessageFormat: RootJsonFormat[RadioMessage] = jsonFormat2(RadioMessage.apply)
  end RadioJsonSupport
