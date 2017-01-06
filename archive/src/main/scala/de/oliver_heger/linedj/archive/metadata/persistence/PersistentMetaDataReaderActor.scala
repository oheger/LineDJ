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

package de.oliver_heger.linedj.archive.metadata.persistence

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import akka.NotUsed
import akka.actor._
import akka.stream.scaladsl.{FileIO, Keep, Sink}
import akka.stream.{ActorMaterializer, FlowShape, Graph}
import akka.util.ByteString
import de.oliver_heger.linedj.archive.metadata.MetaDataProcessingResult
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataReaderActor.ReadMetaDataFile
import de.oliver_heger.linedj.archive.metadata.persistence.parser.ParserTypes.Failure
import de.oliver_heger.linedj.archive.metadata.persistence.parser.{MetaDataParser, ParserStage}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.utils.ChildActorFactory

object PersistentMetaDataReaderActor {

  /**
    * A message processed by [[PersistentMetaDataReaderActor]] that tells the
    * actor to process the specified file with persistent meta data. The actor
    * expects exactly one message of this type after its creation. It processes
    * this file and terminates itself when done.
    *
    * @param path     the path to the file to be processed
    * @param mediumID the ID of the medium
    */
  case class ReadMetaDataFile(path: Path, mediumID: MediumID)

  private class PersistentMetaDataReaderActorImpl(parent: ActorRef, parser: MetaDataParser,
                                                  chunkSize: Int) extends
    PersistentMetaDataReaderActor(parent, parser, chunkSize) with ChildActorFactory

  /**
    * Returns a ''Props'' object for creating new actor instances of this
    * class.
    *
    * @param parent    the parent actor
    * @param parser    the parser
    * @param chunkSize the chunk size
    * @return creation properties
    */
  def apply(parent: ActorRef, parser: MetaDataParser, chunkSize: Int): Props =
    Props(classOf[PersistentMetaDataReaderActorImpl], parent, parser, chunkSize)
}

/**
  * An actor that reads a file with persistent meta data for the media files of
  * a medium.
  *
  * Meta data for media files is persisted in JSON format. For each medium a
  * file exists consisting of a single array with all the data for the single
  * medium files as elements. This actor is responsible for reading one such
  * file.
  *
  * The file is read using stream processing with a [[ParserStage]] stage using
  * a [[de.oliver_heger.linedj.archive.metadata.persistence.parser.MetaDataParser]].
  * The results extracted from a chunk of data are sent to the target actor as
  * specified in the constructor as soon as they become available. When the
  * file has been fully processed this actor stops itself; it also stops if the
  * stream fails with an exception. That way a calling actor can
  * determine in any case when processing of the file is done (in the normal
  * way or aborted due to an error).
  *
  * @param parent    the parent actor that receives extracted results
  * @param parser    the parser for parsing the file
  * @param chunkSize the chunk size when reading the file
  */
class PersistentMetaDataReaderActor(parent: ActorRef, parser: MetaDataParser, chunkSize: Int)
  extends Actor with ActorLogging {
  override def receive: Receive = {
    case ReadMetaDataFile(p, mid) =>
      log.info("Reading persistent meta data file {} for medium {}.", p, mid)
      implicit val maeterializer = ActorMaterializer()
      import context.dispatcher
      val source = FileIO.fromPath(p, chunkSize)
      val sink = Sink.foreach[MetaDataProcessingResult](parent ! _)
      val stage: Graph[FlowShape[ByteString, MetaDataProcessingResult], NotUsed] =
        new ParserStage[MetaDataProcessingResult](parseFunc(mid))
      val flow = source.via(stage).toMat(sink)(Keep.right)
      val future = flow.run()
      future.onComplete(_ => context.stop(self))
  }

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
  (Iterable[MetaDataProcessingResult], Option[Failure]) =
    parser.processChunk(chunk.decodeString(StandardCharsets.UTF_8), mid, lastChunk, lastFailure)
}
