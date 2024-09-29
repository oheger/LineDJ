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

package de.oliver_heger.linedj.platform.audio.impl

import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.platform.audio.playlist.{PlaylistMetaData, PlaylistMetaDataRegistration, PlaylistMetaDataUnregistration}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.ext.NoGroupingMediaIfcExtension
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import de.oliver_heger.linedj.shared.archive.metadata.{FilesMetaDataResponse, GetFilesMetaData, MediaMetaData}
import de.oliver_heger.linedj.utils.LRUCache
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.Actor.Receive
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

object PlaylistMetaDataResolver:
  /** Constant for metadata for a file which could not be resolved. */
  private val UndefinedMetadata = MediaMetaData()

  /**
    * An internally used message that triggers the processing of a response
    * message received from the metadata manager actor.
    *
    * Responses have to be handled in the UI thread. Therefore, a special
    * message is sent to the UI bus which is then handled by this object
    * itself.
    *
    * @param response the response to be processed
    */
  private case class ProcessMetaDataResponse(response: FilesMetaDataResponse)

  /**
    * An internally used message that triggers a propagation of the current
    * metadata to all registered consumers.
    *
    * This message mainly allows a correct ordering of consumer invocations:
    * It must be ensured that update notifications for the audio player state
    * are passed to consumers before updates of playlist metadata. Therefore,
    * such updates can only be sent out in a later cycle of the UI thread.
    */
  private case object PropagateMetaData

  /**
    * Returns a map with metadata for all files that have been requested. This
    * function appends undefined metadata to files which could not be
    * resolved. So the resulting map contains all files in the original
    * request. Data that is already contained in the map with current 
    * metadata is filtered out.
    *
    * @param response    the response from the metadata actor
    * @param currentData the map with current metadata
    * @return a map with complete metadata
    */
  private def completeMetadataResponse(response: FilesMetaDataResponse,
                                       currentData: Map[MediaFileID, MediaMetaData]):
  Map[MediaFileID, MediaMetaData] =
    val data = response.data.toMap
    val unresolved = response.request.files filterNot { f =>
      data.contains(f) || currentData.contains(f)
    }
    if unresolved.nonEmpty then
      data ++ unresolved.map(f => f -> UndefinedMetadata)
    else data

/**
  * A class responsible for retrieving metadata for songs in the current audio
  * playlist.
  *
  * This class is a consumer for [[AudioPlayerStateChangedEvent]] events. When
  * it detects a change in the current playlist it queries metadata for
  * unknown songs from the provided metadata actor. From the responses
  * received objects of type [[PlaylistMetaData]] are constructed and
  * propagated to consumers registered.
  *
  * The class keeps a cache of the metadata that has already been received. So
  * when the playlist changes it may not be necessary to query all songs again
  * from the archive.
  *
  * @param metadataActor  reference to the metadata manager actor
  * @param bus            the UI message bus
  * @param queryChunkSize chunk size when querying metadata for songs
  * @param cacheSize      the size of the cache (in metadata entries
  * @param requestTimeout the timeout for metadata requests
  * @param ec             the execution context
  */
private class PlaylistMetaDataResolver(val metadataActor: ActorRef, val bus: MessageBus,
                                       val queryChunkSize: Int, val cacheSize: Int,
                                       val requestTimeout: Timeout)
                                      (implicit val ec: ExecutionContext)
  extends NoGroupingMediaIfcExtension[PlaylistMetaData] with Identifiable:

  import PlaylistMetaDataResolver._

  /** The registration for change events of the audio player state. */
  val playerStateChangeRegistration: AudioPlayerStateChangeRegistration =
    AudioPlayerStateChangeRegistration(componentID, handleAudioPlayerEvent)

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** Implicit timeout declaration for requests to the metadata actor. */
  private implicit val metadataTimeout: Timeout = requestTimeout

  /** A cache for metadata that has already been resolved. */
  private var cache = new LRUCache[MediaFileID, MediaMetaData](cacheSize)()

  /** Stores the current metadata. */
  private var currentMetaData = PlaylistMetaData(Map.empty)

  /** A list with files that need to be resolved. */
  private var filesToBeResolved = List.empty[MediaFileID]

  /** A list with the files which are currently queried. */
  private var requestedFiles = List.empty[MediaFileID]

  /** Stores the current state of the audio player. */
  private var currentPlayerState = AudioPlayerState.Initial

  /** A sequence number to be increased when a playlist update comes in. */
  private var seqNo = -1

  /**
    * A message processing function that can be overridden by derived classes
    * to implement their own message handling. The ''receive()'' implementation
    * returns a concatenated function of this method and the base message
    * handling function.
    *
    * @return a message handling function for specific events
    */
  override protected def receiveSpecific: Receive =
    case reg: PlaylistMetaDataRegistration =>
      addConsumer(reg)
      reg.callback(currentMetaData)

    case PlaylistMetaDataUnregistration(cid) =>
      removeConsumer(cid)

    case ProcessMetaDataResponse(response) =>
      log.debug("Got response from metadata manager actor.")
      if response.request.seqNo == seqNo then
        handleResponse(response)
      else
        handleOutdatedResponse(response)

    case PropagateMetaData =>
      invokeConsumers(currentMetaData)

  /**
    * A notification method that is invoked when receiving an event about a
    * completed media scan. This event might be of interest for derived classes
    * which may have to updated themselves for new data becoming available.
    *
    * @param hasConsumers a flag whether currently consumers are registered
    */
  override def onMediaScanCompleted(hasConsumers: Boolean): Unit =
    cache = new LRUCache[MediaFileID, MediaMetaData](cacheSize)()
    processNewPlaylist(currentPlayerState)

  /**
    * The consumer function for audio player state change events. If there is a
    * change in the current playlist, another resolve operation is triggered.
    *
    * @param event the state change event
    */
  private def handleAudioPlayerEvent(event: AudioPlayerStateChangedEvent): Unit =
    if event.state.playlistSeqNo != currentPlayerState.playlistSeqNo then
      log.info("Playlist has changed. Start resolving.")
      currentPlayerState = event.state
      processNewPlaylist(event.state)

  /**
    * The current playlist was changed. This method starts processing of the
    * new one.
    *
    * @param state the updated state of the audio player
    */
  private def processNewPlaylist(state: AudioPlayerState): Unit =
    seqNo += 1
    val (resolved, unresolved) = groupFilesInPlaylist(state)
    currentMetaData = PlaylistMetaData(resolved.map(f => (f, cache.get(f).get)).toMap)
    if resolved.nonEmpty then
      bus publish PropagateMetaData
    filesToBeResolved = unresolved
    queryMetadata()

  /**
    * Handles a valid response from the metadata actor. The data in the
    * response is added to the map with current metadata.
    *
    * @param response the response
    */
  private def handleResponse(response: FilesMetaDataResponse): Unit =
    val updatedMetaData = findNewMetadata(response)
    if updatedMetaData.nonEmpty then
      currentMetaData = currentMetaData.copy(data = currentMetaData.data ++ updatedMetaData)
      updatedMetaData foreach (e => cache.addItem(e._1, e._2))
      invokeConsumers(currentMetaData)
    queryMetadata()

  /**
    * Handles a response from the metadata actor that refers to a previous
    * playlist. If it contains data that is also needed for the current
    * playlist, it is used.
    *
    * @param response the response
    */
  private def handleOutdatedResponse(response: FilesMetaDataResponse): Unit =
    val data = response.data.toMap

    def resolvedMap(files: Iterable[MediaFileID]): Map[MediaFileID, MediaMetaData] =
      val keys = files filter data.contains
      keys.map(f => (f, data(f))).toMap

    val m1 = resolvedMap(requestedFiles)
    val m2 = resolvedMap(filesToBeResolved)
    if m1.nonEmpty || m2.nonEmpty then
      currentMetaData = currentMetaData.copy(data = currentMetaData.data ++ m1 ++ m2)
      invokeConsumers(currentMetaData)

  /**
    * Returns a grouping of files into resolved and unresolved ones after an
    * update of the current playlist. The files that are already contained in
    * the cache do not have to be resolved again.
    *
    * @param state the audio player state
    * @return a tuple with resolved and unresolved files
    */
  private def groupFilesInPlaylist(state: AudioPlayerState):
  (List[MediaFileID], List[MediaFileID]) =
    val newPlaylist = state.playlist.pendingSongs ++ state.playlist.playedSongs
    newPlaylist partition cache.contains

  /**
    * Extracts metadata from a response which is not already contained in the
    * current map of data. In addition, missing metadata (for unresolved
    * files) is added.
    *
    * @param response a response from the metadata actor
    * @return a map containing only new metadata
    */
  private def findNewMetadata(response: FilesMetaDataResponse): Map[MediaFileID, MediaMetaData] =
    val completeMetaData = completeMetadataResponse(response, currentMetaData.data)
    completeMetaData filterNot (e => currentMetaData.data.contains(e._1))

  /**
    * Queries a chunk of metadata if there are still files which need to be
    * resolved.
    */
  private def queryMetadata(): Unit =
    if filesToBeResolved.nonEmpty then
      val (files, remaining) = filesToBeResolved splitAt queryChunkSize
      val request = GetFilesMetaData(files, seqNo)
      log.debug("Sending metadata request {}.", request)
      val futResponse = metadataActor ? request
      futResponse.mapTo[FilesMetaDataResponse] fallbackTo Future(FilesMetaDataResponse(request,
        Nil)) foreach (r => bus publish ProcessMetaDataResponse(r))
      filesToBeResolved = remaining
      requestedFiles = files
