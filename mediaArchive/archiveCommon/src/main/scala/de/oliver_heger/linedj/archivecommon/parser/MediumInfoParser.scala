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

package de.oliver_heger.linedj.archivecommon.parser

import java.io.ByteArrayInputStream
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.apache.commons.configuration.{Configuration, XMLConfiguration}

import scala.util.Try

/**
  * Companion object for ''MediumInfoParser''.
  */
private object MediumInfoParser:
  /**
    * Constant for an undefined checksum for a medium. This value is set by the
    * parser for the ''checksum'' property of generated description objects if
    * no specific checksum is provided.
    */
  final val ChecksumUndefined = ""

  /** Constant for the XML element defining the name of a medium. */
  private val ElemName = "name"

  /** Constant for the XML element defining the medium description. */
  private val ElemDesc = "description"

  /** Constant for the XML element defining the standard order mode. */
  private val ElemOrderMode = "order.mode"

  /**
    * Parses the content of a medium description file and returns a
    * [[Configuration]] that can be evaluated.
    *
    * @param data the array with the content of the medium description
    * @return the [[Configuration]] with the parsed medium description
    */
  private def parseMediumDescription(data: Array[Byte]): Configuration =
    val stream = new ByteArrayInputStream(data)
    val config = new XMLConfiguration()
    config.load(stream)
    config

  /**
    * Extracts information about a medium from the given [[Configuration]] and
    * returns it as a [[MediumInfo]] object.
    *
    * @param mediumID the ID to the affected medium
    * @param checksum the checksum for the resulting object
    * @param config     the configuration to be processed
    * @return the resulting ''MediumInfo'' object
    */
  private def extractMediumInfo(mediumID: MediumID, checksum: String, config: Configuration): MediumInfo =
    MediumInfo(name = config.getString(ElemName),
      description = config.getString(ElemDesc),
      orderMode = config.getString(ElemOrderMode),
      mediumID = mediumID,
      checksum = checksum)

/**
  * An internally used helper class for parsing XML files with meta data about
  * media.
  *
  * An instance of this class can parse an XML document with media information
  * (using the typical format). The XML is passed in as a byte array because
  * this is the result produced by a file loader actor.
  */
class MediumInfoParser:

  import MediumInfoParser._

  /**
    * Parses the given medium information provided in binary form and converts
    * it to a ''MediumInfo'' object if possible.
    *
    * @param data     the data to be parsed
    * @param mediumID the ID of the medium
    * @param checksum the value to set as checksum in the result
    * @return a ''Try'' with the resulting ''MediumInfo'' object
    */
  def parseMediumInfo(data: Array[Byte], mediumID: MediumID,
                      checksum: String = ChecksumUndefined): Try[MediumInfo] =
    Try(extractMediumInfo(mediumID, checksum, parseMediumDescription(data)))
