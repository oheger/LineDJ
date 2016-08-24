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

package de.oliver_heger.linedj.browser.cache

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.client.comm.MessageBusListener
import de.oliver_heger.linedj.client.mediaifc.MediaFacade
import de.oliver_heger.linedj.client.mediaifc.RemoteRelayActor.{ServerAvailable, ServerUnavailable}
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.metadata.MetaDataChunk

object MetaDataCache {
  /** Constant for the chunk for an unknown medium. */
  private val UndefinedChunk = MetaDataChunk(null, Map.empty, complete = false)
}

/**
 * A class implementing a client-side cache for meta data.
 *
 * Clients may request meta data for one and the same medium multiple times; it
 * therefore makes sense to cache it locally. This is done by this class. It
 * stores chunks of meta data received from the server and is also able to
 * combine them; this is done in form of [[MetaDataChunk]] objects.
 *
 * This class is not directly accessed by other classes. Rather, interaction
 * takes place via the message bus: A component needing access to the meta data
 * of a medium sends a [[de.oliver_heger.linedj.metadata.GetMetaData]] message
 * on the message bus. This is intercepted by this class. If data for this
 * medium in contained in the cache, the corresponding ''CachedMetaData'' can
 * be directly published to the message bus. Otherwise, a remote request is
 * triggered. In order to receive the desired meta data, the client component
 * has to listen for ''CachedMetaData'' and
 * [[de.oliver_heger.linedj.metadata.MetaDataChunk]] messages.
 *
 * Some other interactions via the message bus are supported as well. For
 * instance, a client component can indicate that it is not longer
 * interested on the data of a certain medium. If necessary, this class then
 * sends a message for removing the corresponding medium listener.
 *
 * If the connection to the server is lost and later reestablished, the cache
 * is cleared, and all registered listeners are removed.
 *
 * @param mediaFacade the facade to the media archive
 */
class MetaDataCache(mediaFacade: MediaFacade) extends MessageBusListener {

  import MetaDataCache._

  /** A map for storing the callbacks registered for different media. */
  var callbacks = Map.empty[MediumID, Map[Any, MetaDataChunk => Unit]]

  /** A map with the already received meta data chunks per medium. */
  private var receivedChunks = Map.empty[MediumID, MetaDataChunk]

  override def receive: Receive = {
    case registration: MetaDataRegistration =>
      val currentChunk = receivedChunks.getOrElse(registration.mediumID, UndefinedChunk)
      val callBacksForMedium = callbacks.getOrElse(registration.mediumID, Map.empty)
      if (!currentChunk.complete && callBacksForMedium.isEmpty) {
        mediaFacade.queryMetaDataAndRegisterListener(registration.mediumID)
      }
      callbacks = callbacks + (registration.mediumID -> (callBacksForMedium + (registration
        .listenerID -> registration.listenerCallback)))
      if (currentChunk.data.nonEmpty) {
        registration.listenerCallback(currentChunk)
      }

    case chunk: MetaDataChunk =>
      val combinedChunk = receivedChunks.get(chunk.mediumID).map(combine(_, chunk)) getOrElse chunk
      receivedChunks = receivedChunks + (chunk.mediumID -> combinedChunk)
      callbacks.getOrElse(chunk.mediumID, Map.empty).values foreach (_.apply(chunk))

      if (chunk.complete) {
        callbacks = callbacks - chunk.mediumID
      }

    case RemoveMetaDataRegistration(mediumID, listenerID) =>
      val mediumListeners = callbacks.getOrElse(mediumID, Map.empty)
      if (mediumListeners contains listenerID) {
        val newListeners = mediumListeners - listenerID
        if (newListeners.nonEmpty) {
          callbacks = callbacks + (mediumID -> newListeners)
        } else {
          mediaFacade.removeMetaDataListener(mediumID)
          callbacks = callbacks - mediumID
        }
      }

    case ServerAvailable =>
      receivedChunks = Map.empty

    case ServerUnavailable =>
      callbacks = Map.empty
  }

  /**
   * Combines two chunks of meta data to a single one.
   * @param chunk1 chunk 1
   * @param chunk2 chunk 2
   * @return the combined chunk
   */
  private def combine(chunk1: MetaDataChunk, chunk2: MetaDataChunk): MetaDataChunk =
    chunk1.copy(data = chunk1.data ++ chunk2.data)
}
