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

package de.oliver_heger.linedj.player.engine.actors

import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferFilled, FillBuffer}
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourcePlaylistInfo, PlayerConfig}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.utils.SchedulerSupport
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}

import scala.collection.mutable

/**
 * Companion object.
 */
object SourceDownloadActor:

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
  val PlaylistEnd = AudioSourcePlaylistInfo(MediaFileID(MediumID.UndefinedMediumID, null), -1, -1)

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
  private def downloadRequest(sourceID: MediaFileID): MediumFileRequest =
    MediumFileRequest(sourceID, withMetaData = false)

  /**
    * An actor implementation which is used for media files that cannot be
    * downloaded.
    *
    * The actor simulates an empty reader actor. It reacts on download data
    * requests by returning a ''DownloadComplete'' message immediately.
    */
  private class DummyReaderActor extends Actor:
    override def receive: Receive =
      case DownloadData(_) =>
        sender() ! DownloadComplete

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
  extends Actor with ActorLogging:
  me: SchedulerSupport =>

  import SourceDownloadActor._

  /** A queue for the items in the playlist. */
  private val playlist = mutable.Queue.empty[AudioSourcePlaylistInfo]

  /** The playlist item which is currently downloaded. */
  private var currentDownload: Option[AudioSourcePlaylistInfo] = None

  /**
    * Stores information about the current file to be downloaded as long as the
    * download is in progress.
    */
  private var downloadInProgress: Option[MediumFileRequest] = None

  /** The read actor currently processed by the buffer. */
  private var currentReadActor: Option[ActorRef] = None

  /** Cancellable for periodic download in progress notifications. */
  private var cancellableReaderAlive: Option[Cancellable] = None

  /** A flag whether an end-of-playlist message has been received. */
  private var playlistClosed = false

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit =
    super.preStart()
    cancellableReaderAlive = Some(scheduleMessage(config.downloadInProgressNotificationDelay,
      config.downloadInProgressNotificationInterval, self, ReportReaderActorAlive))

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit =
    cancellableReaderAlive foreach (_.cancel())
    super.postStop()

  override def receive: Receive =
    case src: AudioSourcePlaylistInfo =>
      if playlistClosed then
        sender() ! PlaybackProtocolViolation(src, ErrorSourceAfterPlaylistEnd)
      else
        if src == PlaylistEnd then
          if nothingToProcess() then
            bufferActor ! LocalBufferActor.SequenceComplete
          playlistClosed = true
        else
          playlist += src
          downloadIfPossible()

    case response: MediumFileResponse =>
      resetCurrentDownload() match
        case Some(info) =>
          readerActor ! AudioSource(uri = info.sourceID.uri, length =
            AudioSource.UnknownLength, skip = info.skip, skipTime = info.skipTime)
          currentReadActor = triggerFillBuffer(response)
          downloadInProgress = Some(response.request)

        case None =>
          sender() ! PlaybackProtocolViolation(response, ErrorUnexpectedDownloadResponse)

    case filled: BufferFilled =>
      currentReadActor match
        case Some(actor) =>
          currentReadActor = None
          downloadInProgress = None
          downloadIfPossible()
          context stop actor
          if playlistClosed && nothingToProcess() then
            bufferActor ! LocalBufferActor.SequenceComplete

        case None =>
          sender() ! PlaybackProtocolViolation(filled, ErrorUnexpectedBufferFilled)

    case CloseRequest =>
      currentReadActor foreach context.stop
      sender() ! CloseAck(self)

    case ReportReaderActorAlive =>
      for
        readerActor <- currentReadActor
        downloadRequest <- downloadInProgress
      do
        config.mediaManagerActor ! DownloadActorAlive(readerActor,
          downloadRequest.fileID)

  /**
    * Returns a flag if currently no action is triggered by this actor. If this
    * state is reached at the end of the playlist, the buffer actor can be
    * closed.
    *
    * @return a flag if this actor is currently idle
    */
  private def nothingToProcess(): Boolean =
    currentReadActor.isEmpty && currentDownload.isEmpty

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
    response.length >= 0 && response.contentReader.isDefined

  /**
   * Sets the current download to ''None''. The old value is returned.
   *
   * @return the current download before it was reset
   */
  private def resetCurrentDownload(): Option[AudioSourcePlaylistInfo] =
    val result = currentDownload
    currentDownload = None
    result

  /**
    * Initiates the download of an audio source if this is currently possible.
    *
    * @return a flag whether a download could be started
    */
  private def downloadIfPossible(): Boolean =
    if playlist.nonEmpty && currentDownload.isEmpty && currentReadActor.isEmpty then
      val info = playlist.dequeue()
      config.mediaManagerActor ! downloadRequest(info.sourceID)
      currentDownload = Some(info)
      true
    else false

  /**
    * Triggers a new fill operation when a response for a download request is
    * received. Returns the option with the new reader actor.
    *
    * @param response the response
    * @return the new value for the current reader actor
    */
  private def triggerFillBuffer(response: MediumFileResponse): Option[ActorRef] =
    val downloadActor = if isValidDownloadResponse(response) then
      assert(response.contentReader.isDefined)
      response.contentReader.get
    else
      log.warning("Download failed! Creating dummy actor.")
      context.actorOf(Props[DummyReaderActor]())
    bufferActor ! FillBuffer(downloadActor)
    Some(downloadActor)
