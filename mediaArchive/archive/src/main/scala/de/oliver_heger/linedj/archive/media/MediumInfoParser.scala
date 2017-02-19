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

import java.io.ByteArrayInputStream

import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.slf4j.LoggerFactory

/**
 * Companion object for ''MediumInfoParser''.
 */
private object MediumInfoParser {
  /** Constant for the XML element defining the name of a medium. */
  private val ElemName = "name"

  /** Constant for the XML element defining the medium description. */
  private val ElemDesc = "description"

  /** Constant for the XML element defining the playlist standard order. */
  private val ElemOrder = "order"

  /** Constant for the XML element defining the standard order mode. */
  private val ElemOrderMode = "mode"

  /** Constant for the XML element defining additional ordering parameters. */
  private val ElemOrderParams = "params"

  /**
   * Parses the content of a medium description file and returns a ''NodeSeq''
   * that can be evaluated.
   * @param data the array with the content of the medium description
   * @return the parsed XML node sequence
   */
  private def parseMediumDescription(data: Array[Byte]): xml.NodeSeq = {
    val stream = new ByteArrayInputStream(data)
    xml.XML.load(stream)
  }

  /**
   * Extracts information about a medium from the given XML document and
   * returns it as a ''MediumSettingsData'' object.
   * @param mediumID the ID to the affected medium
   * @param elem the XML structure to be processed
   * @return the resulting ''MediumSettingsData'' object
   */
  private def extractMediumInfo(mediumID: MediumID, elem: xml.NodeSeq): MediumInfo = {
    val elemOrder = elem \ ElemOrder
    MediumInfo(name = (elem \ ElemName).text,
      description = (elem \ ElemDesc).text,
      orderMode = (elemOrder \ ElemOrderMode).text,
      orderParams = (elemOrder \ ElemOrderParams \ "_").toString(),
      mediumID = mediumID,
      checksum = "")
  }
}

/**
 * An internally used helper class for parsing XML files with meta data about
 * media.
 *
 * An instance of this class can parse an XML document with media information
 * (using the typical format). The XML is passed in as a byte array because
 * this is the result produced by a file loader actor.
 */
private class MediumInfoParser {

  import MediumInfoParser._

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Parses the given medium information provided in binary form and converts
   * it to a ''MediumInfo'' object if possible. In case of a failure, result is
   * ''None''.
   * @param data the data to be parsed
   * @param mediumID the ID of the medium
   * @return an option with the resulting ''MediumInfo'' object
   */
  def parseMediumInfo(data: Array[Byte], mediumID: MediumID): Option[MediumInfo] = {
    try {
      Some(extractMediumInfo(mediumID, parseMediumDescription(data)))
    } catch {
      case ex: Exception =>
        log.error("Error when parsing medium description for " + mediumID, ex)
        None
    }
  }
}
