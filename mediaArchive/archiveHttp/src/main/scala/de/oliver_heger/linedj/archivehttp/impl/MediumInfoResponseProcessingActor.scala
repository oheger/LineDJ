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

package de.oliver_heger.linedj.archivehttp.impl

import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.io.parser.JsonStreamParser
import de.oliver_heger.linedj.shared.archive.media.{MediumDescription, MediumID, MediumInfo}
import org.apache.pekko.stream.KillSwitch
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

/**
  * An actor class for processing responses for medium info files in JSON
  * format.
  *
  * In order to obtain full information about media available in an HTTP
  * archive, the responsible archive management actor downloads the JSON
  * information files for these media during processing of the archive's table
  * of contents. The sources from the responses of such download requests are
  * passed to this actor. The actor reads the entity of the response, parses it
  * as JSON, and extracts the relevant information. If this is successful, it
  * generates a result of type [[MediumInfoResponseProcessingResult]] and sends
  * it to the caller.
  *
  * Note that in order to obtain the correct checksum for the medium, the
  * [[HttpMediumDesc]] object for the current medium is evaluated. The actor
  * assumes that the metadata file is named by the checksum of the medium; so
  * it can derive the checksum from this URI.
  */
class MediumInfoResponseProcessingActor extends AbstractResponseProcessingActor:
  /**
    * @inheritdoc This implementation reads the full content of the source and
    *             combines it to a single ''ByteString'' (assuming that the
    *             size is limited, which is ensured by the base class). The
    *             resulting byte array is passed to the parser, in order to
    *             produce a result object.
    */
  override protected def processSource(source: Source[ByteString, Any],
                                       mid: MediumID,
                                       desc: HttpMediumDesc,
                                       config: HttpArchiveConfig,
                                       seqNo: Int): (Future[Any], KillSwitch) =
    val (futDesc, ks) = JsonStreamParser.parseObjectWithCancellation[MediumDescription](source, Int.MaxValue)
    val futureInfo = futDesc.map { mediumDesc =>
      MediumInfoResponseProcessingResult(
        MediumInfo(mid, mediumDesc, fetchChecksum(desc)),
        seqNo
      )
    }
    (futureInfo, ks)

  /**
    * Obtains the checksum of the current medium from the medium description.
    * This implementation returns the file name of the URI for the metadata
    * file. Per convention, this file has the checksum as name plus an
    * extension.
    *
    * @param desc the medium description
    * @return the checksum to be used for this medium
    */
  private def fetchChecksum(desc: HttpMediumDesc): String =
    val lastSegmentPos = desc.metaDataPath lastIndexOf '/'
    val lastSegment = if lastSegmentPos >= 0 then desc.metaDataPath.substring(lastSegmentPos + 1)
    else desc.metaDataPath
    val extPos = lastSegment lastIndexOf '.'
    if extPos > 0 then lastSegment.substring(0, extPos)
    else lastSegment
