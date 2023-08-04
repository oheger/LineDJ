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

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/**
  * A module that defines the API model and JSON conversions for the radio
  * player API.
  */
object RadioModel:
  /**
    * A data class representing the current playback status of the radio
    * player.
    *
    * @param enabled a flag whether
    */
  final case class PlaybackStatus(enabled: Boolean)

  /**
    * A trait providing JSON converters for the classes of the radio data 
    * model. This trait can be mixed into classes that need to do such
    * conversions.
    */
  trait RadioJsonSupport extends SprayJsonSupport with DefaultJsonProtocol:
    implicit val playbackStatusFormat: RootJsonFormat[PlaybackStatus] = jsonFormat1(PlaybackStatus.apply)
  end RadioJsonSupport
