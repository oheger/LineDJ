/*
 * Copyright 2015-2020 The Developers Team.
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

import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitch, KillSwitches}
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.parser._
import de.oliver_heger.linedj.archivecommon.uri.UriMapper
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess

import scala.concurrent.Future

/**
  * An actor class responsible for processing a response for a meta data file.
  *
  * This actor parses the meta data file into a sequence of
  * [[MetaDataProcessingSuccess]] objects. This sequence is then passed in a
  * [[MetaDataResponseProcessingResult]] message to the sender actor.
  *
  * @param uriMapper the object for mapping URIs of meta data results
  */
class MetaDataResponseProcessingActor(private val uriMapper: UriMapper)
  extends AbstractResponseProcessingActor {

  def this() = this(new UriMapper)

  /**
    * @inheritdoc This implementation processes the content of a meta data
    *             file and parses it into a sequence of
    *             [[MetaDataProcessingSuccess]] objects. Based on this, a
    *             result object is produced.
    */
  protected override def processSource(source: Source[ByteString, Any], mid: MediumID,
                                       desc: HttpMediumDesc, config: HttpArchiveConfig,
                                       seqNo: Int): (Future[Any], KillSwitch) = {
    val sink = Sink.fold[List[MetaDataProcessingSuccess],
      MetaDataProcessingSuccess](List.empty)((lst, r) => r :: lst)
    val (killSwitch, futStream) = source.via(new MetaDataParserStage(mid))
      .viaMat(KillSwitches.single)(Keep.right)
      .map(r => (r, uriMapper.mapUri(config.metaMappingConfig, mid, r.uri)))
      .filter(_._2.isDefined)
      .map(t => t._1.copy(uri = t._2.get))
      .toMat(sink)(Keep.both)
      .run
    (futStream.map(MetaDataResponseProcessingResult(mid, _, seqNo)), killSwitch)
  }
}
