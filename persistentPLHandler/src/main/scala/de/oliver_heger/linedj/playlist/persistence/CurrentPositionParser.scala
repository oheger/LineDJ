/*
 * Copyright 2015-2018 The Developers Team.
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

import scala.util.matching.Regex

/**
  * A parser for a file with information about the current state of a managed
  * playlist.
  *
  * Whenever there is a change in the current position of the playlist, the
  * ''playlist persistence handler'' stores this information in JSON file which
  * contains the following properties:
  *  - ''index'': The index of the current file to be played.
  *  - ''position'': The position (in bytes) of the current file to be played.
  * This is needed if playback is stopped in the middle of a song.
  *  - ''time'': The time (in milliseconds) where playback has stopped for the
  * current song. Like ''position'', this is used to keep track on an
  * interrupted playback.
  *
  * This object is able to parse files containing this information. Parsing is
  * very lenient; the file does not have to use strict JSON syntax, as long as
  * the properties can be identified.
  *
  * Also, if the file is missing or cannot be interpreted, playback of an
  * available playback should nevertheless start. In this case, default values
  * are set for all properties (starting with the first song with 0 position
  * and time offset).
  */
private object CurrentPositionParser {
  /** Name of the index property. */
  val PropIndex = "index"

  /** Name of the position property. */
  val PropPosition = "position"

  /** Name of the time property. */
  val PropTime = "time"

  /** Expression to parse the playlist index. */
  private val regIndex = regexForProperty(PropIndex)

  /** Expression to parse the position offset. */
  private val regPosition = regexForProperty(PropPosition)

  /** Expression to parse the time offset. */
  private val regTime = regexForProperty(PropTime)

  /**
    * Parses a string with information about the current position in the
    * playlist. Returns a ''CurrentPlaylistPosition'' object with the
    * information that has been extracted. Note that an object is returned in
    * any case, even if the passed in string has an invalid format. All
    * properties which could not be resolved are set to default values.
    *
    * @param positionData a string with data about the current position
    * @return an object with current position data
    */
  def parsePosition(positionData: String): CurrentPlaylistPosition = {
    def parse(reg: Regex): String =
      reg.findFirstMatchIn(positionData).map(_.group(1)) getOrElse "0"

    val index = parse(regIndex).toInt
    val position = parse(regPosition).toLong
    val time = parse(regTime).toLong
    CurrentPlaylistPosition(index, position, time)
  }

  /**
    * Generates a regular expression that can parse the specified JSON
    * property.
    *
    * @param property the name of the property
    * @return the expression to parse this property
    */
  private def regexForProperty(property: String): Regex =
    ("\"" + property + "\"\\s*:\\s*(\\d+)").r
}
