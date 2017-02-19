/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import akka.actor.Actor
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}

/**
 * Companion object.
 */
object MediumInfoParserActor {
  /** The name of an unknown medium. */
  private val UnknownMedium = "unknown"

  /**
   * Template for a default settings object to be returned in case of a
   * parsing error.
   */
  private[media] lazy val DummyMediumSettingsData = MediumInfo(name = UnknownMedium,
    description = "", mediumID = MediumID.UndefinedMediumID, orderMode = "", orderParams = "",
    checksum = "")

  /**
   * A message processed by ''MediumInfoParserActor'' which tells it to parse a
   * medium description. The actor will delegate the received data to its
   * associated parser and send a [[MediumInfo]] object as answer.
   * @param descriptionData the data of the description in binary form
   * @param mediumID the medium ID
   */
  case class ParseMediumInfo(descriptionData: Array[Byte], mediumID: MediumID)

  /**
   * Returns a ''MediumInfo'' object for an undefined medium. This object can
   * be used if no information about a medium is available (e.g. if no or an
   * invalid description file is encountered).
   * @return a ''MediumInfo'' object for an undefined medium
   */
  def undefinedMediumInfo: MediumInfo = DummyMediumSettingsData
}

/**
 * A specialized actor implementation which wraps a [[MediumInfoParser]].
 *
 * This actor class is used to parse medium description files in parallel. At
 * construction time a ''MediumInfoParser'' is passed. This actor accepts a
 * message for parsing a medium description file. The file content is delegated
 * to the associated parser. The result is sent back as a
 * [[MediumInfo]] object.
 *
 * @param parser the parser for medium description data
 */
class MediumInfoParserActor(parser: MediumInfoParser) extends Actor {

  import MediumInfoParserActor._

  override def receive: Receive = {
    case ParseMediumInfo(desc, mediumID) =>
      sender ! parser.parseMediumInfo(desc, mediumID).getOrElse(DummyMediumSettingsData.copy
        (mediumID = mediumID))
  }
}
