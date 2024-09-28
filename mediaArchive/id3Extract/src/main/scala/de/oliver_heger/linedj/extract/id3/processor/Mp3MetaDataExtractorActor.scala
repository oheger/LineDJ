/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.extract.id3.processor

import de.oliver_heger.linedj.io.stream.CancelableStreamSupport
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingSuccess, ProcessMetadataFile}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{Actor, ActorRef, Props, Terminated}
import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink, Source}
import org.apache.pekko.stream.{KillSwitch, KillSwitches}
import org.apache.pekko.util.ByteString

object Mp3MetaDataExtractorActor:

  private class Mp3MetaDataExtractorActorImpl(metaDataActor: ActorRef, tagSizeLimit: Int,
                                              readChunkSize: Int)
    extends Mp3MetaDataExtractorActor(metaDataActor, tagSizeLimit, readChunkSize)
      with ChildActorFactory with CancelableStreamSupport

  /**
    * Returns a ''Props'' object for creating a new actor instance.
    *
    * @param metadataActor the metadata receiver actor
    * @param tagSizeLimit  the maximum size of a tag to be processed
    * @param readChunkSize the chunk size for read operations
    * @return ''Props'' to create a new actor instance
    */
  def apply(metadataActor: ActorRef, tagSizeLimit: Int, readChunkSize: Int): Props =
    Props(classOf[Mp3MetaDataExtractorActorImpl], metadataActor, tagSizeLimit, readChunkSize)

/**
  * The main actor for extracting metadata from MP3 files.
  *
  * This actor is responsible for metadata extraction from MP3 files. When the
  * local media archive is triggered to scan for media files an instance of
  * this class is created and invoked for each MP3 file encountered.
  *
  * In order to extract all metadata from an MP3 file, the file has to be
  * read completely. Therefore, the actor opens a stream for the file with some
  * special processing stages. Also, an [[Mp3FileProcessorActor]] instance is
  * created which collects the results for this MP3 file. Completed results are
  * then sent to the metadata manager actor from the union archive.
  *
  * This actor class manages the streams currently open to process MP3 files.
  * It implements cancellation logic, so that a scan operation can be
  * aborted at any time.
  *
  * @param metadataActor the metadata receiver actor
  * @param tagSizeLimit  the maximum size of a tag to be processed
  * @param readChunkSize the chunk size for read operations
  */
class Mp3MetaDataExtractorActor(metadataActor: ActorRef, tagSizeLimit: Int, readChunkSize: Int)
  extends Actor:
  this: ChildActorFactory with CancelableStreamSupport =>

  /**
    * A map storing information about the files currently processed. The keys
    * of the map are the processing actors; the values are the IDs of the
    * corresponding registered kill switches.
    */
  private var activeFiles = Map.empty[ActorRef, Int]

  /** Stores the sender of a close request. */
  private var closeRequest: Option[ActorRef] = None

  override def receive: Receive =
    case ProcessMetadataFile(file, result) =>
      val (actor, killSwitch) = startProcessingStream(file, result)
      val ksID = registerKillSwitch(killSwitch)
      activeFiles += actor -> ksID
      context watch actor

    case CloseRequest =>
      cancelCurrentStreams()
      closeRequest = Some(sender())
      sendCloseAckIfPossible()

    case Terminated(actor) =>
      val optKs = activeFiles get actor
      activeFiles -= actor
      optKs foreach unregisterKillSwitch
      sendCloseAckIfPossible()

  /**
    * Starts a stream for processing a single MP3 file.
    *
    * @param file   an object with information about the file in question
    * @param result a template for the processing result
    * @return the new processing actor and a kill switch
    */
  private def startProcessingStream(file: FileData, result: MetadataProcessingSuccess):
  (ActorRef, KillSwitch) =
    import context.system
    val actor = createChildActor(Mp3FileProcessorActor(metadataActor, tagSizeLimit,
      file, result))
    val source = createSource(file)
    val id3v2Stage = new ID3v2ProcessingStage(Some(actor))
    val id3v1Stage = new ID3v1ProcessingStage(actor)
    val sink = createSink(actor)
    val ks = source
      .viaMat(KillSwitches.single)(Keep.right)
      .via(id3v2Stage)
      .via(id3v1Stage)
      .map(ProcessMp3Data.apply)
      .toMat(sink)(Keep.left)
      .run()
    (actor, ks)

  /**
    * Creates the source for processing an MP3 stream. This source just reads
    * the MP3 file.
    *
    * @param file the file to be read
    * @return the source for processing this file
    */
  private[processor] def createSource(file: FileData): Source[ByteString, Any] =
    FileIO.fromPath(file.path, chunkSize = readChunkSize)

  /**
    * Creates the sink for processing an MP3 stream. This is a sink which
    * sends incoming stream elements to the specified actor.
    *
    * @param actor the MP3 file processing actor
    * @return the sink for MP3 stream processing
    */
  private def createSink(actor: ActorRef): Sink[Any, NotUsed] =
    Sink.actorRefWithBackpressure(actor, onInitMessage = Mp3StreamInit,
      ackMessage = Mp3ChunkAck, onCompleteMessage = Mp3StreamCompleted,
      onFailureMessage = t => Mp3StreamFailure(t))

  /**
    * Checks whether a close request can be acknowledged and - if so - sends
    * the corresponding message. The close operation is done if there are no
    * more active streams.
    */
  private def sendCloseAckIfPossible(): Unit =
    if activeFiles.isEmpty then
      closeRequest foreach (_ ! CloseAck(self))
      closeRequest = None
