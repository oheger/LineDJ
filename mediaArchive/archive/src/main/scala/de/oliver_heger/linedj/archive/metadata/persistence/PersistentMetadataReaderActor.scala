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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetadataReaderActor.ReadMetadataFile
import de.oliver_heger.linedj.archivecommon.parser.MetadataParser
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess
import org.apache.pekko.actor.*
import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink}

import java.nio.file.Path

object PersistentMetadataReaderActor:

  /**
    * A message processed by [[PersistentMetadataReaderActor]] that tells the
    * actor to process the specified file with persistent metadata. The actor
    * expects exactly one message of this type after its creation. It processes
    * this file and terminates itself when done.
    *
    * @param path     the path to the file to be processed
    * @param mediumID the ID of the medium
    */
  case class ReadMetadataFile(path: Path, mediumID: MediumID)

  /**
    * Returns a ''Props'' object for creating new actor instances of this
    * class.
    *
    * @param parent    the parent actor
    * @param chunkSize the chunk size
    * @return creation properties
    */
  def apply(parent: ActorRef, chunkSize: Int): Props =
    Props(classOf[PersistentMetadataReaderActor], parent, chunkSize)

/**
  * An actor that reads a file with persistent metadata for the media files of
  * a medium.
  *
  * metadata for media files is persisted in JSON format. For each medium a
  * file exists consisting of a single array with all the data for the single
  * medium files as elements. This actor is responsible for reading one such
  * file.
  *
  * The file is read using stream processing with a ''ParserStage'' stage using
  * a ''MetaDataParser''.
  *
  * The results extracted from a chunk of data are sent to the target actor as
  * specified in the constructor as soon as they become available. When the
  * file has been fully processed this actor stops itself; it also stops if the
  * stream fails with an exception. That way a calling actor can
  * determine in any case when processing of the file is done (in the normal
  * way or aborted due to an error).
  *
  * @param parent    the parent actor that receives extracted results
  * @param chunkSize the chunk size when reading the file
  */
class PersistentMetadataReaderActor(parent: ActorRef, chunkSize: Int)
  extends Actor with ActorLogging:
  override def receive: Receive =
    case ReadMetadataFile(p, mid) =>
      log.info("Reading persistent metadata file {} for medium {}.", p, mid)
      import context.{dispatcher, system}
      val source = MetadataParser.parseMetadata(FileIO.fromPath(p, chunkSize), mid)
      val sink = Sink.foreach[MetadataProcessingSuccess](parent ! _)
      val flow = source.toMat(sink)(Keep.right)
      val future = flow.run()
      future.onComplete(_ => context.stop(self))
