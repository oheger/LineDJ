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

package de.oliver_heger.linedj.archivecommon.parser

import java.nio.charset.StandardCharsets

import akka.util.ByteString
import de.oliver_heger.linedj.io.parser.{JSONParser, ParserImpl, ParserStage}
import de.oliver_heger.linedj.io.parser.ParserTypes.Failure
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess

object MetaDataParserStage {
  /** The object for parsing meta data processing results. */
  private val parser = new MetaDataParser(ParserImpl, JSONParser.jsonParser(ParserImpl))

  /**
    * The parsing function for the parsing stage.
    *
    * @param mid         the medium ID
    * @param chunk       the current chunk
    * @param lastFailure the failure from the last parsing operation
    * @param lastChunk   flag whether this is the last chunk
    * @return partial parsing results and a failure for the current operation
    */
  private def parseFunc(mid: MediumID)(chunk: ByteString, lastFailure: Option[Failure],
                                       lastChunk: Boolean):
  (Iterable[MetaDataProcessingSuccess], Option[Failure]) =
    parser.processChunk(chunk.decodeString(StandardCharsets.UTF_8), mid, lastChunk, lastFailure)
}

/**
  * A specialized [[ParserStage]] implementation for parsing a stream with
  * meta data in JSON format.
  *
  * This class extends the generic ''ParserStage'' class by a parser function
  * based on a [[MetaDataParser]]. This results in a flow stage which consumes
  * ''ByteString'' instances and produces ''MetaDataProcessingResult''
  * objects.
  *
  * @param mediumID the ID of the medium that is parsed
  */
class MetaDataParserStage(val mediumID: MediumID)
  extends ParserStage[MetaDataProcessingSuccess](MetaDataParserStage.parseFunc(mediumID))
