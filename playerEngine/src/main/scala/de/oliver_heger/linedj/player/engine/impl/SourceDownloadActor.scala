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

package de.oliver_heger.linedj.player.engine.impl

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.impl.LocalBufferActor.{BufferFilled, FillBuffer}
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourceID, AudioSourcePlaylistInfo, PlayerConfig}
import de.oliver_heger.linedj.shared.archive.media.{MediumFileRequest, MediumFileResponse, MediumID, ReaderActorAlive}
import de.oliver_heger.linedj.utils.SchedulerSupport

import scala.collection.mutable

/**
 * Companion object.
 */
object SourceDownloadActor {

  /**
   * A message processed by ''SourceDownloadActor'' telling it to send a
   * message to the source actor that the current download operation is still
   * in progress. Such messages have to be sent periodically to indicate that
   * this client is still alive.
   */
  case object ReportReaderActorAlive

  /**
    * Constant for a special ''AudioSourcePlaylistInfo'' that determines the
    * end of the playlist. Playback will finish before this source. After
    * this source has been received, no further audio sources can be added to
    * the playlist.
    */
  val PlaylistEnd = AudioSourcePlaylistInfo(AudioSourceID(MediumID.UndefinedMediumID, null), -1, -1)

  /**
   * Constant for an error message caused by an unexpected download response
   * message. Responses are only accepted after a request was sent out.
   */
  val ErrorUnexpectedDownloadResponse = "Unexpected MediumFileResponse message!"

  /**
   * Constant for an error message caused by an unexpected buffer filled
   * message. Such messages are only accepted after a reader actor has been
   * passed to the buffer actor.
   */
  val ErrorUnexpectedBufferFilled = "Unexpected BufferFilled message!"

  /**
    * Constant for an error message caused by an audio source added to this
    * actor after and end of playlist message had been received.
    */
  val ErrorSourceAfterPlaylistEnd = "Cannot add audio sources after a playlist end message!"

  /**
    * Generates a ''MediumFileRequest'' message from the specified source ID.
    *
    * @param sourceID the source ID
    * @return the ''MediumFileRequest''
    */
  private def downloadRequest(sourceID: AudioSourceID): MediumFileRequest =
    MediumFileRequest(sourceID.mediumID, sourceID.uri, withMetaData = false)

  private class SourceDownloadActorImpl(config: PlayerConfig, bufferActor: ActorRef,
                                        readerActor: ActorRef)
    extends SourceDownloadActor(config, bufferActor, readerActor)
    with SchedulerSupport

  /**
    * Creates creation properties for an actor instance of this class.
    *
    * @param config      an object with configuration settings
    * @param bufferActor the local buffer actor
    * @param readerActor the actor which reads audio data from the buffer
    * @return creation properties for a new actor instance
    */
  def apply(config: PlayerConfig, bufferActor: ActorRef, readerActor:
  ActorRef): Props =
    Props(classOf[SourceDownloadActorImpl], config, bufferActor, readerActor)
}

/**
 * An actor which downloads audio sources from a source actor into the local
 * buffer.
 *
 * This actor accepts messages which define a playlist. During playback, it
 * requests the sources in the playlist from a source actor. This is done in
 * form of actor references which actually point to file reader actors. These
 * references are passed to the [[LocalBufferActor]] actor which reads the
 * data and stores it locally. (Note that these actors have to be stopped after
 * they have been read completely!)
 *
 * A source object which has been fully stored in the local buffer is also
 * reported to an outbound actor. This actor is then able to read data from the
 * local buffer and manage the actual audio playback.
 *
 * @param config the object with configuration settings
 * @param bufferActor the local buffer actor
 * @param readerActor the actor which reads audio data from the buffer
 */
class SourceDownloadActor(config: PlayerConfig, bufferActor: ActorRef, readerActor: ActorRef)
  extends Actor {
  me: SchedulerSupport =>

  import SourceDownloadActor._

  /** A queue for the items in the playlist. */
  private val playlist = mutable.Queue.empty[AudioSourcePlaylistInfo]

  /** The playlist item which is currently downloaded. */
  private var currentDownload: Option[AudioSourcePlaylistInfo] = None

  /** A download response which is about to be processed. */
  private var downloadToProcess: Option[MediumFileResponse] = None

  /** The read actor currently processed by the buffer. */
  private var currentReadActor: Option[ActorRef] = None

  /** Cancellable for periodic download in progress notifications. */
  private var cancellableReaderAlive: Option[Cancellable] = None

  /** A flag whether an end-of-playlist message has been received. */
  private var playlistClosed = false

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    cancellableReaderAlive = Some(scheduleMessage(config.downloadInProgressNotificationDelay,
      config.downloadInProgressNotificationInterval, self, ReportReaderActorAlive))
  }

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    cancellableReaderAlive foreach (_.cancel())
    super.postStop()
  }

  override def receive: Receive = {
    case src: AudioSourcePlaylistInfo =>
      if (playlistClosed) {
        sender ! PlaybackProtocolViolation(src, ErrorSourceAfterPlaylistEnd)
      } else {
        if (src == PlaylistEnd) {
          if (nothingToProcess()) {
            bufferActor ! LocalBufferActor.SequenceComplete
          }
          playlistClosed = true
        } else {
          playlist += src
          downloadIfPossible()
        }
      }

    case response: MediumFileResponse =>
      resetCurrentDownload() match {
        case Some(info) =>
          if (isValidDownloadResponse(response)) {
            readerActor ! AudioSource(uri = info.sourceID.uri, length =
                          response.length, skip = info.skip, skipTime = info.skipTime)
            downloadToProcess = fillBufferIfPossible(response)
          }
          downloadIfPossible()

        case None =>
          sender ! PlaybackProtocolViolation(response, ErrorUnexpectedDownloadResponse)
      }

    case filled: BufferFilled =>
      currentReadActor match {
        case Some(actor) =>
          readerActor ! SourceReaderActor.AudioSourceDownloadCompleted(filled.sourceLength)
          resetDownloadToProcess() foreach fillBufferIfPossible
          downloadIfPossible()
          context stop actor
          if (playlistClosed && nothingToProcess()) {
            bufferActor ! LocalBufferActor.SequenceComplete
          }

        case None =>
          sender ! PlaybackProtocolViolation(filled, ErrorUnexpectedBufferFilled)
      }

    case CloseRequest =>
      currentReadActor foreach context.stop
      sender ! CloseAck(self)

    case ReportReaderActorAlive =>
      currentReadActor foreach (config.mediaManagerActor ! ReaderActorAlive(_))
  }

  /**
    * Returns a flag if currently no action is triggered by this actor. If this
    * state is reached at the end of the playlist, the buffer actor can be
    * closed.
    *
    * @return a flag if this actor is currently idle
    */
  private def nothingToProcess(): Boolean = {
    currentReadActor.isEmpty && currentDownload.isEmpty
  }

  /**
   * Checks whether the specified ''AudioSourceDownloadResponse'' object
   * indicates a valid download. The media manager actor returns responses with
   * a negative length to indicate that a requested source could not be
   * delivered.
    *
    * @param response the response object to be checked
   * @return a flag whether the response is valid
   */
  private def isValidDownloadResponse(response: MediumFileResponse): Boolean =
    response.length >= 0

  /**
   * Sets the current download to ''None''. The old value is returned.
   *
   * @return the current download before it was reset
   */
  private def resetCurrentDownload(): Option[AudioSourcePlaylistInfo] = {
    val result = currentDownload
    currentDownload = None
    result
  }

  /**
    * Initiates the download of an audio source if this is currently possible.
    *
    * @return a flag whether a download could be started
    */
  private def downloadIfPossible(): Boolean = {
    if (playlist.nonEmpty && downloadToProcess.isEmpty && currentDownload.isEmpty) {
      val info = playlist.dequeue()
      config.mediaManagerActor ! downloadRequest(info.sourceID)
      currentDownload = Some(info)
      true
    } else false
  }

  /**
   * Sets the download to be processed to ''None'' and returns the old value.
    *
    * @return the download to be processed before it was reset
   */
  private def resetDownloadToProcess(): Option[MediumFileResponse] = {
    val result = downloadToProcess
    downloadToProcess = None
    currentReadActor = None
    result
  }

  /**
   * Triggers a new fill operation when a response for a download request is
   * received. If possible, the new content is directly filled into the buffer;
   * otherwise, it has to be parked until the buffer can accept further input.
    *
    * @param response the response
   * @return the new value for the response to be processed
   */
  private def fillBufferIfPossible(response: MediumFileResponse):
  Option[MediumFileResponse] = {
    currentReadActor match {
      case Some(_) =>
        Some(response)

      case None =>
        bufferActor ! FillBuffer(response.contentReader)
        currentReadActor = Some(response.contentReader)
        None
    }
  }
}
