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

import de.oliver_heger.linedj.archivecommon.parser._
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{KillSwitch, KillSwitches}
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

/**
  * An actor class responsible for processing a response for a metadata file.
  *
  * This actor parses the metadata file into a sequence of
  * [[MetadataProcessingSuccess]] objects. This sequence is then passed in a
  * [[MetadataResponseProcessingResult]] message to the sender actor.
  */
class MetadataResponseProcessingActor extends AbstractResponseProcessingActor:
  /**
    * @inheritdoc This implementation processes the content of a metadata
    *             file and parses it into a sequence of
    *             [[MetadataProcessingSuccess]] objects. Based on this, a
    *             result object is produced.
    */
  protected override def processSource(source: Source[ByteString, Any],
                                       mid: MediumID,
                                       desc: HttpMediumDesc,
                                       config: HttpArchiveConfig,
                                       seqNo: Int): (Future[Any], KillSwitch) =
    val sink = Sink.fold[List[MetadataProcessingSuccess],
      MetadataProcessingSuccess](List.empty)((lst, r) => r :: lst)
    val (killSwitch, futStream) = MetadataParser.parseMetadata(source, mid)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(sink)(Keep.both)
      .run()
    (futStream.map(MetadataResponseProcessingResult(mid, _, seqNo)), killSwitch)
