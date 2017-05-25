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

package de.oliver_heger.linedj.archivehttp.impl

import akka.stream.{KillSwitch, KillSwitches}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.shared.archive.media.MediumID

import scala.concurrent.Future

object MediumInfoResponseProcessingActor {
  /** Constant for the file type processed by this actor. */
  val FileType = "MediumInfo"
}

/**
  * An actor class for processing responses for medium info files (aka
  * playlist settings).
  *
  * In order to obtain full information about media available in an HTTP
  * archive, the XML-based information files for these media are
  * downloaded. When a request for such a file is done, the corresponding
  * response is passed to this actor. The actor reads the entity of the
  * response, parses it as XML document, and extracts the relevant
  * information. If this is successful, a result of type
  * [[MediumInfoResponseProcessingResult]] is produced and sent to the caller.
  *
  * @param infoParser the parser for medium info files
  */
class MediumInfoResponseProcessingActor(val infoParser: MediumInfoParser)
  extends AbstractResponseProcessingActor(MediumInfoResponseProcessingActor.FileType) {
  /**
    * Default constructor. This constructor creates an instance with a default
    * [[MediumInfoParser]] object.
    *
    * @return the new instance
    */
  def this() = this(new MediumInfoParser)

  /**
    * @inheritdoc This implementation reads the full content of the source and
    *             combines it to a single ''ByteString'' (assuming that the
    *             size is limited, which is ensured by the base class). The
    *             resulting byte array is passed to the parser, in order to
    *             produce a result object.
    */
  override protected def processSource(source: Source[ByteString, Any], mid: MediumID,
                                       seqNo: Int): (Future[Any], KillSwitch) = {
    val sink = Sink.fold[ByteString, ByteString](ByteString())(_ ++ _)
    val (killSwitch, futureResult) = source
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(sink)(Keep.both)
      .run()
    val futureInfo = futureResult map { bs =>
      MediumInfoResponseProcessingResult(infoParser.parseMediumInfo(bs.toArray, mid).get,
        seqNo)
    }
    (futureInfo, killSwitch)
  }
}
