/*
 * Copyright 2015-2016 The Developers Team.
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

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import de.oliver_heger.linedj.io.{ChannelHandler, FileReaderActor}
import de.oliver_heger.linedj.archive.metadata.MetaDataProcessingResult
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataReaderActor.{ProcessingResults, ReadMetaDataFile}
import de.oliver_heger.linedj.archive.metadata.persistence.parser.{MetaDataParser, ParserTypes}
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

  /**
    * A message produced by [[PersistentMetaDataReaderActor]] sent to the
    * parent actor as soon as processing results become available. The parser
    * returns a sequence of processing results for each chunk that is
    * processed. This data is sent to the parent using this message.
    *
    * @param data the sequence of processing results
    */
  case class ProcessingResults(data: Seq[MetaDataProcessingResult])

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
  * The file is read using a ''FileReaderActor'' and processed chunk-wise using
  * a [[de.oliver_heger.linedj.archive.metadata.persistence.parser.MetaDataParser]].
  * The results extracted from a chunk of data are sent to the target actor as
  * specified in the constructor as soon as they become available. when the
  * file has been fully processed this actor stops itself; it also stops if the
  * file reader actor throws an exception. That way a calling actor can
  * determine in any case when processing of the file is done (in the normal
  * way or aborted due to an error).
  *
  * @param parent    the parent actor that receives extracted results
  * @param parser    the parser for parsing the file
  * @param chunkSize the chunk size when reading the file
  */
class PersistentMetaDataReaderActor(parent: ActorRef, parser: MetaDataParser, chunkSize: Int)
  extends Actor with ActorLogging {
  this: ChildActorFactory =>

  /** The reference to the underlying file reader actor. */
  private var fileReaderActor: ActorRef = _

  /** The ID of the processed medium. */
  private var mediumID: MediumID = _

  /**
    * Stores the previous chunk obtained from the reader. This chunk cannot be
    * processed before another chunk arrives because otherwise it is not known
    * whether this is the last chunk or not.
    */
  private var previousChunk: Option[FileReaderActor.ReadResult] = None

  /** Stores the previous failure received from the parser. */
  private var previousFailure: Option[ParserTypes.Failure] = None

  /**
    * Sets a supervisor strategy that terminates the child on an exception.
    * The child reader actor may throw an exception. In this case, it
    * should be stopped, and processing ends.
    */
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: IOException => Stop
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    fileReaderActor = createChildActor(Props[FileReaderActor])
    context watch fileReaderActor
  }

  override def receive: Receive = {
    case ReadMetaDataFile(p, mid) =>
      log.info("Reading persistent meta data file {} for medium {}.", p, mid)
      fileReaderActor ! ChannelHandler.InitFile(p)
      readNextChunk()
      mediumID = mid

    case res: FileReaderActor.ReadResult =>
      readNextChunk()
      processPreviousChunk(false)
      previousChunk = Some(res)

    case FileReaderActor.EndOfFile(_) =>
      processPreviousChunk(lastChunk = true)
      log.info("Processing of meta data file for medium {} finished.", mediumID)
      context stop self

    case Terminated(a) =>
      log.error("File reader actor crashed for medium {}!", mediumID)
      context stop self
  }

  /**
    * Processes the previous chunk when the next chunk becomes available. At
    * this time it is known whether this is the last chunk or not.
    *
    * @param lastChunk a flag whether this is the last chunk
    */
  private def processPreviousChunk(lastChunk: Boolean): Unit = {
    previousChunk foreach { d =>
      val text = new Predef.String(d.data, 0, d.length, StandardCharsets.UTF_8)
      val (results, nextFailure) = parser.processChunk(text, mediumID, lastChunk = lastChunk,
        previousFailure)
      if (results.nonEmpty) {
        parent ! ProcessingResults(results)
      }
      previousFailure = nextFailure
      if(lastChunk && previousFailure.isDefined) {
        log.warning("Failure at the end of medium {}!", mediumID)
        log.info("Failure is {}.", nextFailure.get)
      }
    }
  }

  /**
    * Reads the next chunk from the underlying reader actor.
    */
  private def readNextChunk(): Unit = {
    fileReaderActor ! FileReaderActor.ReadData(chunkSize)
  }
}
