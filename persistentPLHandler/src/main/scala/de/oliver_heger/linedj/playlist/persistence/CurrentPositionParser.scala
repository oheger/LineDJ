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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.playlist.persistence.PersistentPlaylistModel.*
import spray.json.*

import scala.util.Try

/**
  * A parser for a file with information about the current state of a managed
  * playlist.
  *
  * Whenever there is a change in the current position of the playlist, the
  * ''playlist persistence handler'' stores this information in JSON file which
  * contains the following properties:
  *  - ''index'': The index of the current file to be played.
  *  - ''position'': The position (in bytes) of the current file to be played.
  *    This is needed if playback is stopped in the middle of a song.
  *  - ''time'': The time (in milliseconds) where playback has stopped for the
  *    current song. Like ''position'', this is used to keep track on an
  *    interrupted playback.
  *
  * This object is able to parse files containing this information. 
  * If the file is missing or cannot be interpreted, playback of an
  * available playlist should nevertheless start. In this case, default values
  * are set for all properties (starting with the first song with 0 position
  * and time offset).
  */
private object CurrentPositionParser:
  /** A position that is used if the position file cannot be read. */
  final val DummyPosition = CurrentPlaylistPosition(0, 0, 0)

  /**
    * Parses a string with information about the current position in the
    * playlist. Returns a ''CurrentPlaylistPosition'' object with the
    * information that has been extracted. Note that an object is returned in
    * any case, even if the passed in string has an invalid format. Then a 
    * dummy [[CurrentPlaylistPosition]] object is constructed.
    *
    * @param positionData a string with data about the current position
    * @return an object with current position data
    */
  def parsePosition(positionData: String): CurrentPlaylistPosition =
    Try {
      positionData.parseJson.convertTo[CurrentPlaylistPosition]
    }.getOrElse(DummyPosition)
