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

import java.nio.file.{Path, StandardOpenOption}

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef}
import akka.event.LoggingAdapter
import akka.stream.scaladsl.{FileIO, Source}
import akka.stream.{ActorMaterializer, IOResult}
import akka.util.ByteString
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataWriterActor.{MediumData, ProcessMedium, StreamOperationComplete}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{GetMetaData, MediaMetaData, MetaDataChunk}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object PersistentMetaDataWriterActor {

  /**
    * A message processed by [[PersistentMetaDataWriterActor]] that tells it to
    * write meta data files for a specific medium. This message causes the
    * actor to register for this medium and to handle notifications about
    * chunks of meta data.
    *
    * @param mediumID        the ID of the affected medium
    * @param target          the path to the meta data file to be written
    * @param metaDataManager the meta data manager actor
    * @param uriPathMapping  a URI to path mapping
    * @param resolvedSize    the number of resolved files on this medium
    */
  case class ProcessMedium(mediumID: MediumID, target: Path, metaDataManager: ActorRef,
                           uriPathMapping: Map[String, FileData], resolvedSize: Int)

  /**
    * An internally used message that is passed to this actor when a stream
    * operation has finished. Only a single stream operation should be done at
    * any given time. Therefore, when an operation is finished a notification
    * has to be sent to trigger the next operation if one is pending.
    */
  private[persistence] case object StreamOperationComplete

  /**
    * A case class storing information about a medium to be processed. This
    * class keeps track about the number of elements that have already been
    * written; so it can be determined when the file for this medium has to be
    * written again.
    *
    * @param process         the ''ProcessMedium'' message for this medium
    * @param elementsWritten the number of elements that have been written
    * @param elements        the elements recorded for this medium
    */
  private case class MediumData(process: ProcessMedium, elementsWritten: Int,
                                elements: Map[String, MediaMetaData])

}

/**
  * An actor that produces meta data files for media while they are processed
  * by the meta data manager.
  *
  * This actor comes into play when media are detected for which no or
  * incomplete meta data files exist. In this case, the files on the medium are
  * scanned to extract meta data. When this is done (or if a sufficient number
  * of files has been processed) the obtained meta data should be written out.
  *
  * This actor is notified about media that fall under this category. It
  * registers itself as listener for these media and handles update
  * notifications about incoming meta data. When a configurable number of files
  * on a medium has been processed the meta data file for this medium is
  * written to disk.
  *
  * @param blockSize the number of meta data files processed on a medium
  *                  before the meta data file is written
  */
class PersistentMetaDataWriterActor(blockSize: Int,
                                    private[persistence] val resultHandler:
                                    FutureIOResultHandler) extends Actor with ActorLogging {
  def this(blockSize: Int) = this(blockSize, new FutureIOResultHandler)

  /** The object for materializing streams. */
  private implicit val materializer = ActorMaterializer()

  /** The JSON converter for meta data. */
  private val metaDataConverter = new MetaDataJsonConverter

  /**
    * Stores information about media which are currently processed.
    */
  private var mediaInProgress = Map.empty[MediumID, MediumData]

  /**
    * A map that stores the media and corresponding data that are pending to be
    * written to disk.
    */
  private var mediaToBeWritten = Map.empty[MediumID, MediumData]

  /**
    * Flag whether currently a write operation is in progress. This is used to
    * allow only a single write operation at any time.
    */
  private var writeInProgress = false

  override def receive: Receive = {
    case p: ProcessMedium =>
      p.metaDataManager ! GetMetaData(p.mediumID, registerAsListener = true)
      mediaInProgress += p.mediumID -> MediumData(p, p.resolvedSize, Map.empty)

    case c: MetaDataChunk =>
      mediaInProgress.get(c.mediumID).foreach { mediumData =>
        val nextElements = mediumData.elements ++ c.data
        val nextData = if (nextElements.size - mediumData.elementsWritten >= blockSize || c
          .complete) {
          val d = mediumData.copy(elementsWritten = nextElements.size, elements = nextElements)
          mediaToBeWritten += c.mediumID -> d
          startWriteOperationIfPossible()
          d
        } else mediumData.copy(elements = nextElements)

        if (c.complete) mediaInProgress -= c.mediumID
        else mediaInProgress += (c.mediumID -> nextData)
      }

    case StreamOperationComplete =>
      writeInProgress = false
      startWriteOperationIfPossible()
  }

  /**
    * Checks if a write operation can be started. If so, the write is
    * triggered.
    */
  private def startWriteOperationIfPossible(): Unit = {
    if (!writeInProgress && mediaToBeWritten.nonEmpty) {
      val mid = mediaToBeWritten.keys.head
      val result = triggerWriteMetaDataFile(mediaToBeWritten(mid))
      resultHandler.handleFutureResult(context, result, self, log)
      writeInProgress = true
      mediaToBeWritten -= mid
    }
  }

  /**
    * Triggers writing of a file with meta data. The specified map with meta
    * data is written into the target file for the given medium.
    *
    * @param mediumData the object with data about the medium in question
    * @return a future for the result of the write operation
    */
  private def triggerWriteMetaDataFile(mediumData: MediumData): Future[IOResult] = {
    log.info("Writing meta data file {}.", mediumData.process.target)
    val source = Source(mediumData.elements)
    val sourceCount = Source(0 until mediumData.elements.size)
    val result = source.zip(sourceCount)
      .map(e => ByteString(processElement(e._1._1, mediumData.process.uriPathMapping(e._1._1), e
        ._1._2, e._2)))
      .concat(Source.single(ByteString("\n]\n", "UTF-8")))
      .runWith(FileIO.toFile(mediumData.process.target.toFile,
        Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)))
    result
  }

  /**
    * Processes a single media file in the stream for writing media files.
    * The element is an entry of the map from a meta data chunk. It is
    * converted to a JSON representation in binary form. This method also
    * ensures that separators between elements are set correctly.
    *
    * @param uri   the URI of the song
    * @param file  the ''FileData'' for the song
    * @param data  the meta data for song
    * @param index the index in the stream of elements
    * @return a JSON representation for this song
    */
  private def processElement(uri: String, file: FileData, data: MediaMetaData, index: Int):
  String = {
    val buf = new StringBuilder
    if (index == 0) {
      buf ++= "[\n"
    } else {
      buf ++= ",\n"
    }
    buf ++= metaDataConverter.convert(uri, file.path, data)
    buf.toString()
  }
}

/**
  * An internally used helper class that handles the future with the IO result
  * produced by a stream operation.
  *
  * The main purpose of this class is to notify the owning actor when writing
  * of the stream is complete. Then the next write operation can be triggered.
  */
private class FutureIOResultHandler {
  /**
    * Handles the specified future IO result.
    *
    * @param context      the actor context
    * @param futureResult the future IO result
    * @param actor        the owning actor
    * @param log          the logger for producing log output
    */
  def handleFutureResult(context: ActorContext, futureResult: Future[IOResult],
                         actor: ActorRef, log: LoggingAdapter): Unit = {
    import context._
    futureResult onComplete { r =>
      onResultComplete(r.flatMap(_.status), actor, log)
    }
  }

  /**
    * Callback method invoked when the future result completes. The method is
    * invoked with one of the involved ''Try'' objects: Either the
    * ''Try[IOResult]'' or the ''Try[Done]'' from the ''IOResult''. There is
    * need to distinguish here because we are only interested in failures.
    *
    * @param result the completed result
    * @param actor  the owning actor
    * @param log    the logger for producing log output
    */
  protected def onResultComplete(result: Try[_], actor: ActorRef, log: LoggingAdapter): Unit = {
    actor ! PersistentMetaDataWriterActor.StreamOperationComplete
    result match {
      case Failure(ex) =>
        log.error(ex, "Stream operation caused an exception!")
      case Success(_) =>
        log.info("Meta data file written successfully.")
    }
  }
}
