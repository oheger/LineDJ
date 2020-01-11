/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.ext

import java.nio.file.Paths

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MetaDataCache.{MetaDataRegistration, RemoveMetaDataRegistration}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{MediaMetaData, MetaDataChunk, MetaDataResponse}
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.mutable.ListBuffer

object MetaDataCacheSpec {
  /** Constant for a test medium. */
  private val Medium = mediumID(1)

  /** A test component ID. */
  private val TestComponentID = ComponentID()

  /** Constant for a default cache size. */
  private val DefaultCacheSize = 1000

  /** The number of songs on a test medium. */
  private val SongsPerMedium = 10

  /**
    * Creates a test medium ID.
    *
    * @param idx the index of the medium
    * @return the test medium ID with this index
    */
  private def mediumID(idx: Int): MediumID =
  MediumID(s"$idx. Hot Playlist", Some(Paths.get(s"playlist$idx.settings").toString))

  /**
    * Extracts the index from a test medium ID.
    *
    * @param mid the test medium ID
    * @return the index
    */
  private def extractIndex(mid: MediumID): Int = {
    val idxStr = mid.mediumURI.substring(0, mid.mediumURI.indexOf('.'))
    idxStr.toInt
  }

  /**
   * Creates a response message with meta data. It contains only a single test
   * song.
   * @param songIdx the index of the test song
   * @param complete the complete flag
   * @param mediumID the medium ID
   * @return the chunk
   */
  private def createChunk(songIdx: Int, complete: Boolean, mediumID: MediumID = Medium):
  MetaDataResponse =
    createChunk(List(songIdx), complete, mediumID)

  /**
   * Creates a response message containing meta data for the given test songs.
   * @param songIndices a list with the indices of the test songs
   * @param complete the complete flag
   * @param mediumID the medium ID
   * @return the chunk
   */
  private def createChunk(songIndices: Seq[Int], complete: Boolean, mediumID: MediumID):
  MetaDataResponse = {
    val songMappings = songIndices map (i => s"Song$i" -> MediaMetaData(title = Some(s"Title$i")))
    MetaDataResponse(MetaDataChunk(mediumID, Map(songMappings: _*), complete),
      extractIndex(mediumID))
  }

  /**
    * Creates a response with a number of songs for the given medium. The song
    * indices are derived from the medium index.
    *
    * @param numberOfSongs the number of songs to generate
    * @param complete      flag whether the medium is complete
    * @param mediumIdx     the index of the medium
    * @return generated meta data for this medium
    */
  private def createMetaDataForMedium(numberOfSongs: Int, complete: Boolean, mediumIdx: Int):
  MetaDataResponse =
  createChunk((1 to numberOfSongs).map(_ + 1000 * mediumIdx), complete, mediumID(mediumIdx))

  /**
    * Generates meta data which can be send to the cache for a given set of
    * media. For each medium a response containing the given number of songs
    * is produced.
    *
    * @param media indices of media for which data is to be generated
    * @return a sequence with meta data for each medium
    */
  private def createMetaData(media: Int*):
  Seq[MetaDataResponse] =
  media map (createMetaDataForMedium(SongsPerMedium, complete = true, _))

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
   * @param id the object representing the listener ID
   * @param mediumID the medium ID
   * @return the list buffer receiving callback messages
   */
  private def register(cache: MetaDataCache, id: ComponentID = TestComponentID,
                       mediumID: MediumID = Medium):
  ListBuffer[MetaDataChunk] = {
    val buffer = ListBuffer.empty[MetaDataChunk]
    cache.receive(MetaDataRegistration(mediumID, id, createCallback(buffer)))
    buffer
  }

  /**
    * Combines the ''register()'' method with sending of meta data.
    *
    * @param cache the cache
    * @param data  the data to be sent to the cache
    * @param id    the object representing the listener ID
    * @return the list buffer receiving callback messages
    */
  private def registerAndReceive(cache: MetaDataCache, data: MetaDataResponse,
                                 id: ComponentID = TestComponentID):
  ListBuffer[MetaDataChunk] = {
    val buffer = register(cache, id, data.chunk.mediumID)
    cache receive data
    buffer
  }

  /**
    * Creates a test cache instance.
    *
    * @param facade    the mock for the facade
    * @param cacheSize the size of the cache
    * @return the new cache instance
    */
  private def createCache(facade: MediaFacade, cacheSize: Int = DefaultCacheSize): MetaDataCache =
  new MetaDataCache(facade, cacheSize)
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
    when(facade.queryMetaDataAndRegisterListener(any(classOf[MediumID])))
      .thenAnswer((invocation: InvocationOnMock) => {
      val mid = invocation.getArguments.head.asInstanceOf[MediumID]
      extractIndex(mid)
    })
    facade
  }

  /**
    * Verifies that a request for meta data was sent via the facade.
    *
    * @param facade   the mock for the media facade
    * @param mediumID the expected medium ID
    * @param expTimes the expected number of requests for this medium
    */
  private def verifyMetaDataRequest(facade: MediaFacade, mediumID: MediumID = Medium,
                                    expTimes: Int = 1): Unit = {
    verify(facade, times(expTimes)).queryMetaDataAndRegisterListener(mediumID)
  }

  /**
   * Checks whether a callback received the expected chunks.
   * @param buffer the buffer filled by the callback
   * @param chunks the expected response messages
   */
  private def verifyReceivedChunks(buffer: ListBuffer[MetaDataChunk],
                                   chunks: MetaDataResponse*): Unit = {
    buffer.toList should be(chunks.map(_.chunk).toList)
  }

  "A MetaDataCache" should "send a remote request for a medium not completely stored" in {
    val facade = createMediaFacade()
    val cache = createCache(facade)
    register(cache)

    verifyMetaDataRequest(facade)
  }

  it should "send received meta data to a registered listener" in {
    val cache = createCache(createMediaFacade())
    val chunks = register(cache)
    val chunk = createChunk(0, complete = false)

    cache.receive(chunk)
    verifyReceivedChunks(chunks, chunk)
  }

  it should "handle multiple listeners" in {
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val chunk1 = createChunk(0, complete = false)
    val chunk2 = createChunk(1, complete = false)
    val chunk3 = createChunk(2, complete = true)
    val buf1 = register(cache)
    cache.receive(chunk1)
    cache.receive(chunk2)

    val buf2 = register(cache, ComponentID())
    cache.receive(chunk3)
    verifyMetaDataRequest(facade)
    verifyReceivedChunks(buf1, chunk1, chunk2, chunk3)
    verifyReceivedChunks(buf2, createChunk(List(0, 1), complete = false, Medium), chunk3)
  }

  it should "handle multiple media" in {
    val Medium2 = mediumID(2)
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val chunk1 = createChunk(0, complete = false)
    val chunk2 = createChunk(1, complete = false, mediumID = Medium2)
    val buf1 = register(cache)

    val buf2 = register(cache, ComponentID(), Medium2)
    cache.receive(chunk1)
    cache.receive(chunk2)
    verifyMetaDataRequest(facade)
    verifyMetaDataRequest(facade, Medium2)
    verifyReceivedChunks(buf1, chunk1)
    verifyReceivedChunks(buf2, chunk2)
  }

  it should "remove callbacks if all chunks for a medium have been received" in {
    val cache = createCache(createMediaFacade())
    val chunk1 = createChunk(1, complete = false)
    val chunk2 = createChunk(2, complete = true)
    val buf = register(cache)
    cache.receive(chunk1)
    cache.receive(chunk2)

    cache.receive(createChunk(3, complete = false))
    verifyReceivedChunks(buf, chunk1, chunk2)
  }

  it should "not send a request for new data if the chunks are already complete" in {
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val chunk = createChunk(List(1, 2, 3), complete = true, Medium)
    val buf = register(cache)
    cache.receive(chunk)

    register(cache)
    verifyReceivedChunks(buf, chunk)
    verifyMetaDataRequest(facade)
  }

  it should "correctly set the completed flag of combined chunks" in {
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val chunk1 = createChunk(List(1, 2, 3), complete = false, Medium)
    val chunk2 = createChunk(4, complete = true)
    register(cache)
    cache.receive(chunk1)
    cache.receive(chunk2)

    val buf = register(cache)
    verifyReceivedChunks(buf, createChunk(List(1, 2, 3, 4), complete = true, Medium))
  }

  it should "allow removing a listener" in {
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val buf = register(cache)

    cache.receive(RemoveMetaDataRegistration(Medium, TestComponentID))
    cache.receive(createChunk(1, complete = false))
    verifyReceivedChunks(buf)
    verify(facade).removeMetaDataListener(Medium)
  }

  it should "ignore a request to remove an unknown listener" in {
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val buf = register(cache)
    val chunk = createChunk(1, complete = false)

    cache.receive(RemoveMetaDataRegistration(Medium, ComponentID()))
    cache.receive(chunk)
    verifyReceivedChunks(buf, chunk)
    verify(facade, never()).removeMetaDataListener(Medium)
  }

  it should "ignore a request to remove listeners for an unknown medium" in {
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val buf = register(cache)
    val chunk = createChunk(1, complete = false)
    val Medium2 = MediumID("_other", None)

    cache.receive(RemoveMetaDataRegistration(Medium2, TestComponentID))
    cache.receive(chunk)
    verifyReceivedChunks(buf, chunk)
    verify(facade, never()).removeMetaDataListener(Medium2)
  }

  it should "not remove the remote medium listener if there are remaining listeners" in {
    val ListenerID2 = ComponentID()
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val buf1 = register(cache)
    val buf2 = register(cache, ListenerID2)
    val chunk = createChunk(1, complete = false)

    cache.receive(RemoveMetaDataRegistration(Medium, ListenerID2))
    cache.receive(chunk)
    verifyReceivedChunks(buf1, chunk)
    verifyReceivedChunks(buf2)
    verify(facade, never()).removeMetaDataListener(Medium)
  }

  it should "clean the cache when the archive becomes available again" in {
    val cache = createCache(createMediaFacade())
    cache.receive(createChunk(1, complete = false))

    cache.receive(MediaFacade.MediaArchiveAvailable)
    val buf = register(cache)
    verifyReceivedChunks(buf)
  }

  it should "remove all listeners when the archive becomes unavailable" in {
    val cache = createCache(createMediaFacade())
    val buf = register(cache)

    cache.receive(MediaFacade.MediaArchiveUnavailable)
    cache.receive(createChunk(1, complete = false))
    verifyReceivedChunks(buf)
  }

  it should "remove a medium from the cache if the cache size is reached" in {
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 50)
    val metaData = createMetaData(1 to 5: _*)
    metaData foreach (registerAndReceive(cache, _))
    verifyMetaDataRequest(facade)
    cache.numberOfEntries should be(cache.cacheSize)

    registerAndReceive(cache, createChunk(1, complete = false, mediumID = mediumID(10)))
    register(cache)
    verifyMetaDataRequest(facade, expTimes = 2)
    cache.numberOfEntries should be < cache.cacheSize
  }

  it should "not remove the only medium from the cache" in {
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 9)
    val metaData = createChunk(1 to 10, complete = true, Medium)

    registerAndReceive(cache, metaData)
    register(cache)
    verifyMetaDataRequest(facade)
  }

  it should "move a medium to the front when it is accessed" in {
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 50)
    val metaData = createMetaData(1 to 5: _*)
    metaData foreach (registerAndReceive(cache, _))

    register(cache)
    registerAndReceive(cache, createChunk(1, complete = false, mediumID = mediumID(10)))
    register(cache)
    verifyMetaDataRequest(facade)
    register(cache, mediumID = mediumID(2))
    verifyMetaDataRequest(facade, mediumID(2), expTimes = 2)
  }

  it should "correctly maintain the LRU list when moving a medium to front" in {
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 30)
    val metaData = createMetaData(1 to 3: _*)
    metaData foreach (registerAndReceive(cache, _))

    register(cache)
    createMetaData(4 to 6: _*) foreach(registerAndReceive(cache, _))
    register(cache)
    verifyMetaDataRequest(facade, expTimes = 2)
  }

  it should "remove multiple media from the cache if necessary to enforce the cache size" in {
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 50)
    val metaData = createMetaData(1 to 5: _*)
    metaData foreach (registerAndReceive(cache, _))

    registerAndReceive(cache, createMetaDataForMedium(19, complete = true, mediumIdx = 10))
    register(cache)
    register(cache, mediumID = mediumID(2))
    verifyMetaDataRequest(facade, expTimes = 2)
    verifyMetaDataRequest(facade, mediumID = mediumID(2), expTimes = 2)
  }

  it should "not remove media that are not yet complete" in {
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 20)
    registerAndReceive(cache, createMetaDataForMedium(10, complete = false, mediumIdx = 1))
    registerAndReceive(cache, createMetaDataForMedium(10, complete = true, mediumIdx = 2))

    registerAndReceive(cache, createMetaDataForMedium(1, complete = true, mediumIdx = 3))
    register(cache)
    register(cache, mediumID = mediumID(2))
    verifyMetaDataRequest(facade)
    verifyMetaDataRequest(facade, mediumID = mediumID(2), expTimes = 2)
  }

  it should "not remove the head of the LRU list" in {
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 20)
    registerAndReceive(cache, createMetaDataForMedium(10, complete = false, mediumIdx = 3))
    registerAndReceive(cache, createMetaDataForMedium(10, complete = false, mediumIdx = 2))

    registerAndReceive(cache, createMetaDataForMedium(10, complete = true, mediumIdx = 1))
    register(cache)
    verifyMetaDataRequest(facade)
  }

  it should "ignore meta data with a wrong registration ID" in {
    val cache = createCache(createMediaFacade())
    val chunk1 = createChunk(1 to 10, complete = false, Medium)
    val chunk2 = createChunk(11, complete = true).copy(registrationID = 42)
    val chunks = registerAndReceive(cache, chunk1)

    cache receive chunk2
    verifyReceivedChunks(chunks, chunk1)
  }

  it should "not accept chunks after the completion of a medium" in {
    val cache = createCache(createMediaFacade())
    val chunk = createChunk(1 to 10, complete = true, Medium)
    registerAndReceive(cache, chunk)

    cache receive createChunk(11, complete = true)
    cache.numberOfEntries should be(10)
    val chunks = register(cache)
    verifyReceivedChunks(chunks, chunk)
  }

  it should "correctly increase the cache size if a chunk is combined" in {
    val cache = createCache(createMediaFacade())
    registerAndReceive(cache, createChunk(1, complete = false))
    cache receive createChunk(2, complete = false)

    cache.numberOfEntries should be(2)
  }

  it should "reset the registration ID when there are no more consumers" in {
    val lid2 = ComponentID()
    val cache = createCache(createMediaFacade())
    val chunk1 = createChunk(1, complete = false)
    val chunk2 = createChunk(2, complete = false)
    registerAndReceive(cache, chunk1)
    val chunks = register(cache, id = lid2)

    cache receive RemoveMetaDataRegistration(Medium, TestComponentID)
    cache receive chunk2
    cache receive RemoveMetaDataRegistration(Medium, lid2)
    cache receive createChunk(3, complete = false)
    verifyReceivedChunks(chunks, chunk1, chunk2)
    cache.numberOfEntries should be(0)
  }

  it should "remove an incomplete medium if there are no more consumers" in {
    val facade = createMediaFacade()
    val cache = createCache(facade)
    registerAndReceive(cache, createChunk(1, complete = false))

    cache receive RemoveMetaDataRegistration(Medium, TestComponentID)
    register(cache)
    verifyMetaDataRequest(facade, expTimes = 2)
    cache.numberOfEntries should be(0)
  }

  it should "create a correct un-registration object from a registration" in {
    val reg = MetaDataRegistration(Medium, TestComponentID, null)

    val unReg = reg.unRegistration
    unReg should be(RemoveMetaDataRegistration(Medium, TestComponentID))
  }
}
