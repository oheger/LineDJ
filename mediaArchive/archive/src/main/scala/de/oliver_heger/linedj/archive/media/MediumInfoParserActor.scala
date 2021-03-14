/*
 * Copyright 2015-2021 The Developers Team.
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

import java.nio.file.Path

import akka.actor.ActorLogging
import akka.stream.scaladsl.{FileIO, Keep, Sink, Source}
import akka.stream.{KillSwitch, KillSwitches}
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport, StreamSizeRestrictionStage}
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}

import scala.concurrent.Future

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
}

/**
  * An actor implementation which parses a medium description file.
  *
  * This actor class is used to parse medium description files in parallel. At
  * construction time a [[MediumInfoParser]] is passed. This actor accepts a
  * message for parsing a medium description file. The file is then loaded using
  * a stream, and the aggregated content is passed to the parser. The result is
  * sent back as a
  * [[de.oliver_heger.linedj.archive.media.MediumInfoParserActor.ParseMediumInfoResult]]
  * object.
  *
  * As parsing of description files happens in memory, a maximum file size can
  * be specified. If a file is longer, parsing fails with an exception.
  *
  * @param parser the parser for medium description data
  * @param maxSize the maximum size of a file to be processed
  */
class MediumInfoParserActor(parser: MediumInfoParser, maxSize: Int)
  extends AbstractStreamProcessingActor with ActorLogging with CancelableStreamSupport {

  import MediumInfoParserActor._

  override def customReceive: Receive = {
    case req: ParseMediumInfo =>
      handleParseRequest(req)
  }

  /**
    * Handles a request to parse a medium description file.
    *
    * @param req the request to be processed
    */
  private def handleParseRequest(req: ParseMediumInfo): Unit = {
    val source = createSource(req.descriptionPath)
    val (ks, futParse) = runStream(req, source)
    processStreamResult(futParse, ks) { f =>
      log.error(f.exception, "Could not read medium info file " + req.descriptionPath)
      ParseMediumInfoResult(req, DummyMediumSettingsData.copy(mediumID = req.mediumID))
    }
  }

  /**
    * Runs a stream to read and parse the specified medium description file.
    *
    * @param req    the request to parse the file
    * @param source the source to be processed
    * @return a tuple with the kill switch and the future stream result
    */
  private def runStream(req: ParseMediumInfo, source: Source[ByteString, Any]):
  (KillSwitch, Future[ParseMediumInfoResult]) = {
    val sink: Sink[ByteString, Future[ByteString]] = Sink.fold(ByteString.empty)(_ ++ _)
    val (ks, futStream) = source
      .viaMat(KillSwitches.single)(Keep.right)
      .via(new StreamSizeRestrictionStage(maxSize))
      .toMat(sink)(Keep.both)
      .run()
    val futParse = futStream map { bs =>
      ParseMediumInfoResult(req, parser.parseMediumInfo(bs.toArray, req.mediumID).get)
    }
    (ks, futParse)
  }

  /**
    * Creates the source for reading the file with medium info.
    *
    * @param path the path to the file to be read
    * @return the source for reading this file
    */
  private[media] def createSource(path: Path): Source[ByteString, Any] =
    FileIO.fromPath(path)
}
