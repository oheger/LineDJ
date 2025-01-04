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

package de.oliver_heger.linedj.extract.id3.processor

import de.oliver_heger.linedj.extract.id3.model._
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingSuccess}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.apache.pekko.actor.{Actor, ActorRef, OneForOneStrategy, Props, SupervisorStrategy, Terminated}

object Mp3FileProcessorActor:

  private class Mp3FileProcessorActorImpl(metaDataActor: ActorRef, tagSizeLimit: Int,
                                          collector: MetadataPartsCollector,
                                          resultTemplate: MetadataProcessingSuccess)
    extends Mp3FileProcessorActor(metaDataActor, tagSizeLimit, collector, resultTemplate)
      with ChildActorFactory

  /**
    * Creates a ''Props'' object for creating an instance of this actor
    * class. The object is initialized to process a specific MP3 file.
    *
    * @param metadataActor the actor receiving metadata results
    * @param tagSizeLimit  the maximum size of an ID3 tag (in bytes); tags that
    *                      are bigger are ignored
    * @param mp3File       the data object pointing to the MP3 file
    * @param resultData    a data object defining parameters of the file to be
    *                      processed; here the extracted metadata is added
    * @return a ''Props'' object for creating a new actor instance
    */
  def apply(metadataActor: ActorRef, tagSizeLimit: Int, mp3File: FileData,
            resultData: MetadataProcessingSuccess): Props =
    Props(classOf[Mp3FileProcessorActorImpl], metadataActor, tagSizeLimit,
      new MetadataPartsCollector(mp3File), resultData)

/**
  * An actor class responsible for processing a whole mp3 file and extracting
  * all available metadata.
  *
  * For each MP3 file subject to metadata extraction an instance of this actor
  * class is created. The file is then read, and messages about ID3 frames or
  * MPEG data are sent to this actor. This actor collects these messages and
  * processes them further if necessary - delegating to child actors. When all
  * processing results arrived a resulting object with metadata results is
  * constructed and passed to the metadata actor.
  *
  * @param metadataActor  the actor receiving metadata results
  * @param tagSizeLimit   the maximum size of an ID3 tag (in bytes)
  * @param collector      the collector for parts of metadata
  * @param resultTemplate an object defining parameters for the result
  */
class Mp3FileProcessorActor(metadataActor: ActorRef, tagSizeLimit: Int,
                            collector: MetadataPartsCollector,
                            resultTemplate: MetadataProcessingSuccess) extends Actor:
  this: ChildActorFactory =>

  /** The reference to the MP3 data processor actor. */
  private var mp3DataActor: ActorRef = _

  /** Reference to an actor to which an ACK message has to be sent. */
  private var ackActor: ActorRef = _

  /**
    * Stores the current child actor for processing ID3v2 frames. For each ID3
    * frame encountered a new child actor is created. It is stopped when
    * processing results become available.
    */
  private var optID3ProcessorActor: Option[ActorRef] = None

  /** Holds an error caused by a child actor. */
  private var childActorError: Throwable = _

  /**
    * A counter for the MP3 chunks that are currently processed. This is used
    * to synchronize processing with the speed of the MP3 stream: We allow one
    * chunk to be processed in background. But if more chunks are piling up,
    * no ACK messages are sent until processing completes.
    */
  private var chunksInProgress = 0

  /**
    * A supervisor strategy that stops failing child actors. If a child actor
    * throws an exception, we expect that the file is corrupt and send an
    * error message to the metadata receiving actor.
    */
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy():
    case e: Throwable =>
      childActorError = e
      Stop

  override def preStart(): Unit =
    super.preStart()
    mp3DataActor = createChildActor(Props(classOf[Mp3DataProcessorActor],
      new Mp3DataExtractor()))
    context watch mp3DataActor

  override def receive: Receive =
    case Mp3StreamInit =>
      sender() ! Mp3ChunkAck

    case mp3Data: ProcessMp3Data =>
      mp3DataActor ! mp3Data
      chunksInProgress += 1
      if chunksInProgress == 1 then
        sender() ! Mp3ChunkAck
      else
        ackActor = sender()

    case Mp3DataProcessed =>
      chunksInProgress -= 1
      if chunksInProgress == 1 then
        ackActor ! Mp3ChunkAck

    case mp3Data: Mp3Metadata =>
      sendResultIfAvailable(collector setMp3Metadata mp3Data)

    case ID3v1Metadata(metaData) =>
      sendResultIfAvailable(collector setID3v1Metadata metaData)

    case procMsg: ProcessID3FrameData =>
      val id3Actor = optID3ProcessorActor getOrElse createID3ProcessorActor(procMsg)
      id3Actor ! procMsg
      collector.expectID3Data(procMsg.frameHeader.version)
      optID3ProcessorActor = if procMsg.lastChunk then None else Some(id3Actor)

    case id3Inc: IncompleteID3Frame if optID3ProcessorActor.isDefined =>
      optID3ProcessorActor.get ! id3Inc
      optID3ProcessorActor = None

    case id3Data: ID3FrameMetadata =>
      sendResultIfAvailable(collector addID3Data id3Data)
      context unwatch sender()
      context stop sender()

    case Mp3StreamCompleted =>
      mp3DataActor ! Mp3MetadataRequest

    case Mp3StreamFailure(exception) =>
      handleProcessingError(exception)

    case Terminated(_) =>
      handleProcessingError(childActorError)

  /**
    * Checks whether metadata processing is complete. If so, the metadata is
    * sent to the receiver actor.
    *
    * @param optMeta an option with the extracted metadata
    */
  private def sendResultIfAvailable(optMeta: Option[MediaMetadata]): Unit =
    optMeta foreach { m =>
      metadataActor ! resultTemplate.copy(metadata = m)
      stopSelf()
    }

  /**
    * Sends a message indicating a processing failure to the metadata
    * receiver actor and stops this actor.
    *
    * @param exception the exception to be sent
    */
  private def handleProcessingError(exception: Throwable): Unit =
    metadataActor ! createProcessingErrorMsg(exception)
    stopSelf()

  /**
    * Stops this actor. This is done when processing is complete - either
    * successfully or in case of an error.
    */
  private def stopSelf(): Unit =
    context stop self

  /**
    * Creates a child actor for processing of ID3v2 frames.
    *
    * @param procMsg the message to process an ID3 frame
    * @return the new child actor
    */
  private def createID3ProcessorActor(procMsg: ProcessID3FrameData): ActorRef =
    val actor = createChildActor(Props(classOf[ID3FrameProcessorActor], self,
      new ID3FrameExtractor(procMsg.frameHeader, tagSizeLimit)))
    context watch actor
    actor

  /**
    * Creates a message about a processing error.
    *
    * @param exception the exception which is the cause of the error
    * @return the processing error message
    */
  private def createProcessingErrorMsg(exception: Throwable): MetadataProcessingError =
    MetadataProcessingError(resultTemplate.mediumID, resultTemplate.uri, exception)
