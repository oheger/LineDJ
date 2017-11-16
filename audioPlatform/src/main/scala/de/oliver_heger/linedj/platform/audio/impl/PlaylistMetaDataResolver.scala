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

package de.oliver_heger.linedj.platform.audio.impl

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.ext.NoGroupingMediaIfcExtension
import de.oliver_heger.linedj.player.engine.AudioSourcePlaylistInfo
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import de.oliver_heger.linedj.shared.archive.metadata.{FilesMetaDataResponse, GetFilesMetaData,
  MediaMetaData}
import de.oliver_heger.linedj.utils.LRUCache
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

object PlaylistMetaDataResolver {
  /** Constant for meta data for a file which could not be resolved. */
  private val UndefinedMetaData = MediaMetaData()

  /**
    * An internally used message that triggers the processing of a response
    * message received from the meta data manager actor.
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
    * meta data to all registered consumers.
    *
    * This message mainly allows a correct ordering of consumer invocations:
    * It must be ensured that update notifications for the audio player state
    * are passed to consumers before updates of playlist meta data. Therefore,
    * such updates can only be sent out in a later cycle of the UI thread.
    */
  private case object PropagateMetaData

  /**
    * Returns a map with meta data for all files that have been requested. This
    * function appends undefined meta data to files which could not be
    * resolved. So the resulting map contains all files in the original
    * request. Data that is already contained in the map with current meta
    * data is filtered out.
    *
    * @param response    the response from the meta data actor
    * @param currentData the map with current meta data
    * @return a map with complete meta data
    */
  private def completeMetaDataResponse(response: FilesMetaDataResponse,
                                       currentData: Map[MediaFileID, MediaMetaData]):
  Map[MediaFileID, MediaMetaData] = {
    val unresolved = response.request.files filterNot { f =>
      response.data.contains(f) || currentData.contains(f)
    }
    if (unresolved.nonEmpty) {
      response.data ++ unresolved.map(f => f -> UndefinedMetaData)
    } else response.data
  }
}

/**
  * A class responsible for retrieving meta data for songs in the current audio
  * playlist.
  *
  * This class is a consumer for [[AudioPlayerStateChangedEvent]] events. When
  * it detects a change in the current playlist it queries meta data for
  * unknown songs from the provided meta data actor. From the responses
  * received objects of type [[PlaylistMetaData]] are constructed and
  * propagated to consumers registered.
  *
  * The class keeps a cache of the meta data that has already been received. So
  * when the playlist changes it may not be necessary to query all songs again
  * from the archive.
  *
  * @param metaDataActor  reference to the meta data manager actor
  * @param bus            the UI message bus
  * @param queryChunkSize chunk size when querying meta data for songs
  * @param cacheSize      the size of the cache (in meta data entries
  * @param requestTimeout the timeout for meta data requests
  * @param ec             the execution context
  */
private class PlaylistMetaDataResolver(val metaDataActor: ActorRef, val bus: MessageBus,
                                       val queryChunkSize: Int, val cacheSize: Int,
                                       val requestTimeout: Timeout)
                                      (implicit val ec: ExecutionContext)
  extends NoGroupingMediaIfcExtension[PlaylistMetaData] with Identifiable {

  import PlaylistMetaDataResolver._

  /** The registration for change events of the audio player state. */
  val playerStateChangeRegistration: AudioPlayerStateChangeRegistration =
    AudioPlayerStateChangeRegistration(componentID, handleAudioPlayerEvent)

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** Implicit timeout declaration for requests to the meta data actor. */
  private implicit val metaDataTimeout: Timeout = requestTimeout

  /** A cache for meta data that has already been resolved. */
  private var cache = new LRUCache[MediaFileID, MediaMetaData](cacheSize)()

  /** Stores the current meta data. */
  private var currentMetaData = PlaylistMetaData(Map.empty)

  /** A list with files that need to be resolved. */
  private var filesToBeResolved = List.empty[MediaFileID]

  /** A list with the files which are currently queried. */
  private var requestedFiles = List.empty[MediaFileID]

  /** Stores the current playlist processed by this object. */
  private var currentPlaylist = List.empty[AudioSourcePlaylistInfo]

  /** Stores the current state of the audio player. */
  private var currentPlayerState = AudioPlayerState(Nil, Nil, playbackActive = false,
    playlistClosed = false)

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
  override protected def receiveSpecific: Receive = {
    case reg: PlaylistMetaDataRegistration =>
      addConsumer(reg)
      reg.callback(currentMetaData)

    case PlaylistMetaDataUnregistration(cid) =>
      removeConsumer(cid)

    case ProcessMetaDataResponse(response) =>
      log.debug("Got response from meta data manager actor.")
      if (response.request.seqNo == seqNo) {
        handleResponse(response)
      } else {
        handleOutdatedResponse(response)
      }

    case PropagateMetaData =>
      invokeConsumers(currentMetaData)
  }

  /**
    * A notification method that is invoked when receiving an event about a
    * completed media scan. This event might be of interest for derived classes
    * which may have to updated themselves for new data becoming available.
    *
    * @param hasConsumers a flag whether currently consumers are registered
    */
  override def onMediaScanCompleted(hasConsumers: Boolean): Unit = {
    cache = new LRUCache[MediaFileID, MediaMetaData](cacheSize)()
    processNewPlaylist(currentPlayerState)
  }

  /**
    * The consumer function for audio player state change events. If there is a
    * change in the current playlist, another resolve operation is triggered.
    *
    * @param event the state change event
    */
  private def handleAudioPlayerEvent(event: AudioPlayerStateChangedEvent): Unit = {
    val playlist = event.state.playedSongs.reverse ++ event.state.pendingSongs
    if (playlist != currentPlaylist) {
      log.info("Playlist has changed. Start resolving.")
      currentPlaylist = playlist
      currentPlayerState = event.state
      processNewPlaylist(event.state)
    }
  }

  /**
    * The current playlist was changed. This method starts processing of the
    * new one.
    *
    * @param state the updated state of the audio player
    */
  private def processNewPlaylist(state: AudioPlayerState): Unit = {
    seqNo += 1
    val (resolved, unresolved) = groupFilesInPlaylist(state)
    currentMetaData = PlaylistMetaData(resolved.map(f => (f, cache.get(f).get)).toMap)
    if (resolved.nonEmpty) {
      bus publish PropagateMetaData
    }
    filesToBeResolved = unresolved
    queryMetaData()
  }

  /**
    * Handles a valid response from the meta data actor. The data in the
    * response is added to the map with current meta data.
    *
    * @param response the response
    */
  private def handleResponse(response: FilesMetaDataResponse): Unit = {
    val updatedMetaData = findNewMetaData(response)
    if (updatedMetaData.nonEmpty) {
      currentMetaData = currentMetaData.copy(data = currentMetaData.data ++ updatedMetaData)
      updatedMetaData foreach (e => cache.addItem(e._1, e._2))
      invokeConsumers(currentMetaData)
    }
    queryMetaData()
  }

  /**
    * Handles a response from the meta data actor that refers to a previous
    * playlist. If it contains data that is also needed for the current
    * playlist, it is used.
    *
    * @param response the response
    */
  private def handleOutdatedResponse(response: FilesMetaDataResponse): Unit = {
    def resolvedMap(files: Iterable[MediaFileID]): Map[MediaFileID, MediaMetaData] = {
      val keys = files filter response.data.contains
      keys.map(f => (f, response.data(f))).toMap
    }

    val m1 = resolvedMap(requestedFiles)
    val m2 = resolvedMap(filesToBeResolved)
    if (m1.nonEmpty || m2.nonEmpty) {
      currentMetaData = currentMetaData.copy(data = currentMetaData.data ++ m1 ++ m2)
      invokeConsumers(currentMetaData)
    }
  }

  /**
    * Returns a grouping of files into resolved and unresolved ones after an
    * update of the current playlist. The files that are already contained in
    * the cache do not have to be resolved again.
    *
    * @param state the audio player state
    * @return a tuple with resolved and unresolved files
    */
  private def groupFilesInPlaylist(state: AudioPlayerState):
  (List[MediaFileID], List[MediaFileID]) = {
    val newPlaylist = (state.pendingSongs ++ state.playedSongs) map (_.sourceID)
    newPlaylist partition cache.contains
  }

  /**
    * Extracts meta data from a response which is not already contained in the
    * current map of data. In addition, missing meta data (for unresolved
    * files) is added.
    *
    * @param response a response from the meta data actor
    * @return a map containing only new meta data
    */
  private def findNewMetaData(response: FilesMetaDataResponse): Map[MediaFileID, MediaMetaData] = {
    val completeMetaData = completeMetaDataResponse(response, currentMetaData.data)
    completeMetaData filterNot (e => currentMetaData.data.contains(e._1))
  }

  /**
    * Queries a chunk of meta data if there are still files which need to be
    * resolved.
    */
  private def queryMetaData(): Unit = {
    if (filesToBeResolved.nonEmpty) {
      val (files, remaining) = filesToBeResolved splitAt queryChunkSize
      val request = GetFilesMetaData(files, seqNo)
      log.debug("Sending meta data request {}.", request)
      val futResponse = metaDataActor ? request
      futResponse.mapTo[FilesMetaDataResponse] fallbackTo Future(FilesMetaDataResponse(request,
        Map.empty)) foreach (r => bus publish ProcessMetaDataResponse(r))
      filesToBeResolved = remaining
      requestedFiles = files
    }
  }
}
