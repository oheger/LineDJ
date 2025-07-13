/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport}
import de.oliver_heger.linedj.shared.archive.media.{MediumDescription, MediumID, MediumInfo}
import org.apache.pekko.actor.ActorLogging
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.file.Path

/**
  * Companion object.
  */
object MediumInfoParserActor:
  /** The name of an unknown medium. */
  private val UnknownMedium = "unknown"

  /**
    * Template for a default settings object to be returned in case of a
    * parsing error.
    */
  private[media] lazy val DummyMediumSettingsData = MediumInfo(
    mediumID = MediumID.UndefinedMediumID,
    mediumDescription = MediumDescription(
      name = UnknownMedium,
      description = "",
      orderMode = ""
    ),
    checksum = ""
  )

  /** A format for the JSON serialization of [[MediumDescription]] objects. */
  private[media] given RootJsonFormat[MediumDescription] = jsonFormat3(MediumDescription.apply)

  /**
    * A message processed by ''MediumInfoParserActor'' which tells it to parse a
    * medium description. The actor will read the file, pass its content to
    * the associated parser and send a [[ParseMediumInfoResult]] object as
    * answer.
    *
    * @param descriptionPath the path to the description file
    * @param mediumID        the medium ID
    * @param seqNo           a sequence number
    */
  case class ParseMediumInfo(descriptionPath: Path, mediumID: MediumID, seqNo: Int)

  /**
    * A message representing the response of a request to parse a medium info
    * file. The message contains the original request plus the resulting
    * ''MediumInfo'' object.
    *
    * @param request the request this result is about
    * @param info    the actual result of the parse operation
    */
  case class ParseMediumInfoResult(request: ParseMediumInfo, info: MediumInfo)

  /**
    * Returns a ''MediumInfo'' object for an undefined medium. This object can
    * be used if no information about a medium is available (e.g. if no or an
    * invalid description file is encountered).
    *
    * @return a ''MediumInfo'' object for an undefined medium
    */
  def undefinedMediumInfo: MediumInfo = DummyMediumSettingsData

/**
  * An actor implementation which parses a medium description file.
  *
  * This actor class is used to parse medium description files in parallel. It
  * accepts a message for parsing a medium description file. The actor then
  * parses the file using [[JsonStreamParser]], and extracts the information
  * about the medium. The result is sent back as a
  * [[de.oliver_heger.linedj.archive.media.MediumInfoParserActor.ParseMediumInfoResult]]
  * object.
  *
  * As parsing of description files happens in memory, a maximum file size can
  * be specified. If a file is longer, parsing fails with an exception.
  *
  * @param maxSize the maximum size of a file to be processed
  */
class MediumInfoParserActor(maxSize: Int) extends AbstractStreamProcessingActor
  with ActorLogging with CancelableStreamSupport:

  import MediumInfoParserActor.*
  import MediumInfoParserActor.given_RootJsonFormat_MediumDescription

  override def customReceive: Receive =
    case req: ParseMediumInfo =>
      log.info("Parsing file '{}'.", req.descriptionPath)
      import context.{dispatcher, system}
      val client = sender()
      val (futDesc, ks) = JsonStreamParser.parseFileWithCancellation[MediumDescription](req.descriptionPath, maxSize)
      val futResult = futDesc.map { description =>
        ParseMediumInfoResult(req, MediumInfo(req.mediumID, description, ""))
      }

      processStreamResult(futResult, ks) { f =>
        log.error(f.exception, "Failed to parse file '{}'.", req.descriptionPath)
        ParseMediumInfoResult(req, DummyMediumSettingsData.copy(mediumID = req.mediumID))
      }
