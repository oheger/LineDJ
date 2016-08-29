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

import java.nio.file.Paths

import de.oliver_heger.linedj.client.comm.MessageBus
import de.oliver_heger.linedj.client.mediaifc.MediaFacade
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.metadata.{MediaMetaData, MetaDataChunk}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable.ListBuffer

object MetaDataCacheSpec {
  /** Constant for a test medium. */
  private val Medium = MediumID("Hot Playlist", Some(Paths.get("playlist.settings").toString))

  /**
   * Creates a test chunk with meta data. It contains only a single test song.
   * @param songIdx the index of the test song
   * @param complete the complete flag
   * @param mediumID the medium ID
   * @return the chunk
   */
  private def createChunk(songIdx: Int, complete: Boolean, mediumID: MediumID = Medium):
  MetaDataChunk =
    createChunk(List(songIdx), complete, mediumID)

  /**
   * Creates a chunk containing meta data for the given test songs.
   * @param songIndices a list with the indices of the test songs
   * @param complete the complete flag
   * @param mediumID the medium ID
   * @return the chunk
   */
  private def createChunk(songIndices: List[Int], complete: Boolean, mediumID: MediumID):
  MetaDataChunk = {
    val songMappings = songIndices map (i => s"Song$i" -> MediaMetaData(title = Some(s"Title$i")))
    MetaDataChunk(mediumID, Map(songMappings: _*), complete)
  }

  /**
   * Creates a callback function for being used in tests which adds received
   * messages to the specified list buffer.
   * @param buffer the target list buffer
   * @return the callback function
   */
  private def createCallback(buffer: ListBuffer[MetaDataChunk]): MetaDataChunk => Unit = {
    chunk => buffer += chunk
  }

  /**
   * Creates a test meta data registration which collects received data in a
   * list buffer. The registration is added to the cache. The list buffer is
   * returned.
   * @param cache the cache
   * @param obj the object representing the listener ID
   * @param mediumID the medium ID
   * @return the list buffer receiving callback messages
   */
  private def register(cache: MetaDataCache, obj: Any, mediumID: MediumID = Medium):
  ListBuffer[MetaDataChunk] = {
    val buffer = ListBuffer.empty[MetaDataChunk]
    cache.receive(MetaDataRegistration(mediumID, obj)(createCallback(buffer)))
    buffer
  }
}

/**
 * Test class for ''MetaDataCache''.
 */
class MetaDataCacheSpec extends FlatSpec with Matchers with MockitoSugar {

  import MetaDataCacheSpec._

  /**
    * Creates a mock for the media facade.
    *
    * @return the media facade mock
    */
  private def createMediaFacade(): MediaFacade = {
    val facade = mock[MediaFacade]
    val bus = mock[MessageBus]
    when(facade.bus).thenReturn(bus)
    facade
  }

  /**
   * Verifies that a request for meta data was sent via the facade.
   * @param facade the mock for the media facade
   * @param mediumID the expected medium ID
   */
  private def verifyMetaDataRequest(facade: MediaFacade, mediumID: MediumID = Medium): Unit = {
    verify(facade, times(1)).queryMetaDataAndRegisterListener(mediumID)
  }

  /**
   * Checks whether a callback received the expected chunks.
   * @param buffer the buffer filled by the callback
   * @param chunks the expected chunks
   */
  private def verifyReceivedChunks(buffer: ListBuffer[MetaDataChunk], chunks: MetaDataChunk*):
  Unit = {
    buffer.toList should be(chunks.toList)
  }

  "A MetaDataCache" should "send a remote request for a medium not completely stored" in {
    val facade = createMediaFacade()
    val cache = new MetaDataCache(facade)
    register(cache, this)

    verifyMetaDataRequest(facade)
  }

  it should "send received meta data to a registered listener" in {
    val cache = new MetaDataCache(createMediaFacade())
    val chunks = register(cache, this)
    val chunk = createChunk(0, complete = false)

    cache.receive(chunk)
    verifyReceivedChunks(chunks, chunk)
  }

  it should "handle multiple listeners" in {
    val facade = createMediaFacade()
    val cache = new MetaDataCache(facade)
    val chunk1 = createChunk(0, complete = false)
    val chunk2 = createChunk(1, complete = false)
    val chunk3 = createChunk(2, complete = true)
    val buf1 = register(cache, this)
    cache.receive(chunk1)
    cache.receive(chunk2)

    val buf2 = register(cache, "other")
    cache.receive(chunk3)
    verifyMetaDataRequest(facade)
    verifyReceivedChunks(buf1, chunk1, chunk2, chunk3)
    verifyReceivedChunks(buf2, createChunk(List(0, 1), complete = false, Medium), chunk3)
  }

  it should "handle multiple media" in {
    val Medium2 = MediumID("Another cool playlist", None)
    val facade = createMediaFacade()
    val cache = new MetaDataCache(facade)
    val chunk1 = createChunk(0, complete = false)
    val chunk2 = createChunk(1, complete = false, mediumID = Medium2)
    val buf1 = register(cache, this)

    val buf2 = register(cache, "other", Medium2)
    cache.receive(chunk1)
    cache.receive(chunk2)
    verifyMetaDataRequest(facade)
    verifyMetaDataRequest(facade, Medium2)
    verifyReceivedChunks(buf1, chunk1)
    verifyReceivedChunks(buf2, chunk2)
  }

  it should "accept a chunk of data for which no listeners are registered" in {
    val cache = new MetaDataCache(createMediaFacade())
    val chunk = createChunk(1, complete = false)

    cache.receive(chunk)
    val buf = register(cache, this)
    verifyReceivedChunks(buf, chunk)
  }

  it should "remove callbacks if all chunks for a medium have been received" in {
    val cache = new MetaDataCache(createMediaFacade())
    val chunk1 = createChunk(1, complete = false)
    val chunk2 = createChunk(2, complete = true)
    val buf = register(cache, this)
    cache.receive(chunk1)
    cache.receive(chunk2)

    cache.receive(createChunk(3, complete = false))
    verifyReceivedChunks(buf, chunk1, chunk2)
  }

  it should "not send a request for new data if the chunks are already complete" in {
    val facade = createMediaFacade()
    val cache = new MetaDataCache(facade)
    val chunk = createChunk(List(1, 2, 3), complete = true, Medium)
    cache.receive(chunk)

    val buf = register(cache, this)
    verifyReceivedChunks(buf, chunk)
    verifyZeroInteractions(facade)
  }

  it should "allow removing a listener" in {
    val facade = createMediaFacade()
    val cache = new MetaDataCache(facade)
    val buf = register(cache, this)

    cache.receive(RemoveMetaDataRegistration(Medium, this))
    cache.receive(createChunk(1, complete = false))
    verifyReceivedChunks(buf)
    verify(facade).removeMetaDataListener(Medium)
  }

  it should "ignore a request to remove an unknown listener" in {
    val facade = createMediaFacade()
    val cache = new MetaDataCache(facade)
    val buf = register(cache, this)
    val chunk = createChunk(1, complete = false)

    cache.receive(RemoveMetaDataRegistration(Medium, "other"))
    cache.receive(chunk)
    verifyReceivedChunks(buf, chunk)
    verify(facade, never()).removeMetaDataListener(Medium)
  }

  it should "ignore a request to remove listeners for an unknown medium" in {
    val facade = createMediaFacade()
    val cache = new MetaDataCache(facade)
    val buf = register(cache, this)
    val chunk = createChunk(1, complete = false)
    val Medium2 = MediumID("_other", None)

    cache.receive(RemoveMetaDataRegistration(Medium2, this))
    cache.receive(chunk)
    verifyReceivedChunks(buf, chunk)
    verify(facade, never()).removeMetaDataListener(Medium2)
  }

  it should "not remove the remote medium listener if there are remaining listeners" in {
    val ListenerID2 = "AnotherTestListener"
    val facade = createMediaFacade()
    val cache = new MetaDataCache(facade)
    val buf1 = register(cache, this)
    val buf2 = register(cache, ListenerID2)
    val chunk = createChunk(1, complete = false)

    cache.receive(RemoveMetaDataRegistration(Medium, ListenerID2))
    cache.receive(chunk)
    verifyReceivedChunks(buf1, chunk)
    verifyReceivedChunks(buf2)
    verify(facade, never()).removeMetaDataListener(Medium)
  }

  it should "clean the cache when the archive becomes available again" in {
    val cache = new MetaDataCache(createMediaFacade())
    cache.receive(createChunk(1, complete = false))

    cache.receive(MediaFacade.MediaArchiveAvailable)
    val buf = register(cache, this)
    verifyReceivedChunks(buf)
  }

  it should "remove all listeners when the archive becomes unavailable" in {
    val cache = new MetaDataCache(createMediaFacade())
    val buf = register(cache, this)

    cache.receive(MediaFacade.MediaArchiveUnavailable)
    cache.receive(createChunk(1, complete = false))
    verifyReceivedChunks(buf)
  }
}
