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

package de.oliver_heger.linedj.archive.metadata.persistence

import akka.actor.{ActorLogging, ActorRef}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.archive.config.ArchiveContentTableConfig
import de.oliver_heger.linedj.archive.metadata.persistence.ArchiveToCWriterActor.WriteToC
import de.oliver_heger.linedj.archivecommon.uri.UriMapper
import de.oliver_heger.linedj.io.stream.AbstractFileWriterActor.StreamFailure
import de.oliver_heger.linedj.io.stream.{AbstractFileWriterActor, CancelableStreamSupport}
import de.oliver_heger.linedj.shared.archive.media.MediumID

object ArchiveToCWriterActor {

  /**
    * A message processed by [[ArchiveToCWriterActor]] telling it to write the
    * file with the table of content.
    *
    * The actor generates the content file based on the provided configuration
    * and writes it to the defined target location. Errors are just logged, no
    * response message is generated.
    *
    * @param config  the configuration for the ToC
    * @param content the content of the archive to be written out
    */
  case class WriteToC(config: ArchiveContentTableConfig, content: List[(MediumID, String)])

}

/**
  * An actor class that writes the table of content of a media archive.
  *
  * This actor processes messages that define the content and the format of a
  * JSON document listing all media (with their relative folders and meta data
  * files) that belong to the archive. The actor then produces such a file at
  * the specified location.
  *
  * Writing the ToC file is kind of a fire-and-forget operation. As this
  * functionality is not critical for the archive, no sophisticated error
  * handling is implemented.
  */
class ArchiveToCWriterActor extends AbstractFileWriterActor with CancelableStreamSupport
  with ActorLogging {
  /** The URI mapper object. */
  private val uriMapper = new UriMapper

  override protected def customReceive: Receive = {
    case m@WriteToC(config, content) =>
      assert(config.contentFile.isDefined, "Undefined content file!")
      writeFile(createToCSource(config, content), config.contentFile.get, m)
  }

  /**
    * @inheritdoc This implementation does nothing. We do no result reporting,
    *             neither in success nor in error case.
    */
  override protected def propagateResult(client: ActorRef, result: Any): Unit = {}

  /**
    * @inheritdoc This implementation does nothing. Errors are just ignored.
    */
  override protected def handleFailure(client: ActorRef, f: StreamFailure): Unit = {}

  /**
    * Returns a ''Source'' to generate the ToC for an archive.
    *
    * @param config  the configuration for the ToC
    * @param content the content
    * @return the source which produces the ToC
    */
  private def createToCSource(config: ArchiveContentTableConfig,
                              content: List[(MediumID, String)]): Source[ByteString, Any] = {
    val cr = System.lineSeparator()
    val source = Source(content).map { t =>
      (uriMapper.mapUri(config, t._1, t._1.mediumDescriptionPath.orNull), t._2)
    }.filter(_._1.isDefined)
      .map { t =>
        val desc = s"""{"mediumDescriptionPath":"${t._1.get}""""
        val meta = s""""metaDataPath":"${config.metaDataPrefix.getOrElse("")}${t._2}.mdt"}"""
        desc + "," + meta
      }.scan(ByteString("[" + cr)) { (prev, e) =>
      if (prev(0) != '[') ByteString("," + cr + e)
      else ByteString(e)
    }.concat(Source.single(ByteString(cr + "]" + cr)))
    source
  }
}
