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

package de.oliver_heger.linedj.platform.mediaifc.ext

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MetadataCache.{MediumContent, MetadataRegistration, RemoveMetadataRegistration}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileID, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.{MediaMetadata, MetadataChunk, MetadataResponse}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer

object MetadataCacheSpec:
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
    MediumID(s"$idx. Hot Playlist", Some(s"playlist$idx.settings"))

  /**
    * Creates the URI of a test song based on the given index.
    *
    * @param idx the index of the song
    * @return the URI for this test song
    */
  private def songUri(idx: Int): String = s"test/song$idx.mp3"

  /**
    * Creates an object with metadata for a test song based on the given
    * index.
    *
    * @param idx the index of the song
    * @return metadata for this test song
    */
  private def songMetadata(idx: Int): MediaMetadata = MediaMetadata(title = Some(s"Title$idx"))

  /**
    * Extracts the index from a test medium ID.
    *
    * @param mid the test medium ID
    * @return the index
    */
  private def extractIndex(mid: MediumID): Int =
    val idxStr = mid.mediumURI.substring(0, mid.mediumURI.indexOf('.'))
    idxStr.toInt

  /**
    * Creates a response message with metadata. It contains only a single test
    * song.
    *
    * @param songIdx  the index of the test song
    * @param complete the complete flag
    * @param mediumID the medium ID
    * @return the chunk
    */
  private def createChunk(songIdx: Int, complete: Boolean, mediumID: MediumID = Medium): MetadataResponse =
    createResponse(List(songIdx), complete, mediumID)

  /**
    * Creates a response message containing metadata for the given test songs.
    *
    * @param songIndices       a list with the indices of the test songs
    * @param complete          the complete flag
    * @param mediumID          the medium ID
    * @param optRegistrationID optional ID of the registration of the chunk
    * @return the chunk
    */
  private def createResponse(songIndices: Seq[Int], complete: Boolean, mediumID: MediumID,
                             optRegistrationID: Option[Int] = None): MetadataResponse =
    val songMappings = songIndices map (i => songUri(i) -> songMetadata(i))
    MetadataResponse(MetadataChunk(mediumID, Map(songMappings: _*), complete),
      optRegistrationID getOrElse extractIndex(mediumID))

  /**
    * Convenience function to create a ''MediumContent'' object that contains
    * a single test song.
    *
    * @param songIdx  the index of the test song
    * @param complete the complete flag
    * @param mediumID the medium ID
    * @return the ''MediumContent'' object
    */
  private def createContent(songIdx: Int, complete: Boolean, mediumID: MediumID = Medium): MediumContent =
    createContent(List(songIdx), complete, mediumID)

  /**
    * Creates a ''MediumContent'' object for the given parameters.
    *
    * @param songIndices a list with the indices of the test songs
    * @param complete    the complete flag
    * @param mediumID    the medium ID
    * @return the ''MediumContent'' object
    */
  private def createContent(songIndices: Seq[Int], complete: Boolean, mediumID: MediumID): MediumContent =
    val songMappings = songIndices map { index =>
      val id = MediaFileID(mediumID, songUri(index))
      id -> songMetadata(index)
    }
    MediumContent(songMappings.toMap, complete)

  /**
    * Generates a map with data for the content of a medium based on a number
    * of test songs and a given medium ID.
    *
    * @param songIndices the indices of the test songs
    * @param mid         the ID of the medium
    * @return a map with content data
    */
  private def contentData(songIndices: Seq[Int], mid: MediumID = Medium): Map[MediaFileID, MediaMetadata] =
    songIndices.map { idx =>
      val id = MediaFileID(mid, songUri(idx))
      id -> songMetadata(idx)
    }.toMap

  /**
    * Creates a response with a number of songs for the given medium. The song
    * indices are derived from the medium index.
    *
    * @param numberOfSongs the number of songs to generate
    * @param complete      flag whether the medium is complete
    * @param mediumIdx     the index of the medium
    * @return generated metadata for this medium
    */
  private def createMetadataForMedium(numberOfSongs: Int, complete: Boolean, mediumIdx: Int): MetadataResponse =
    createResponse((1 to numberOfSongs).map(_ + 1000 * mediumIdx), complete, mediumID(mediumIdx))

  /**
    * Generates metadata which can be sent to the cache for a given set of
    * media. For each medium a response containing the given number of songs
    * is produced.
    *
    * @param media indices of media for which data is to be generated
    * @return a sequence with metadata for each medium
    */
  private def createMetadata(media: Int*): Seq[MetadataResponse] =
    media map (createMetadataForMedium(SongsPerMedium, complete = true, _))

  /**
    * Creates a callback function for being used in tests which adds received
    * messages to the specified list buffer.
    *
    * @param buffer the target list buffer
    * @return the callback function
    */
  private def createCallback(buffer: ListBuffer[MediumContent]): MediumContent => Unit =
    chunk => buffer += chunk

  /**
    * Creates a test metadata registration which collects received data in a
    * list buffer. The registration is added to the cache. The list buffer is
    * returned.
    *
    * @param cache    the cache
    * @param id       the object representing the listener ID
    * @param mediumID the medium ID
    * @return the list buffer receiving callback messages
    */
  private def register(cache: MetadataCache, id: ComponentID = TestComponentID,
                       mediumID: MediumID = Medium): ListBuffer[MediumContent] =
    val buffer = ListBuffer.empty[MediumContent]
    cache.receive(MetadataRegistration(mediumID, id, createCallback(buffer)))
    buffer

  /**
    * Combines the ''register()'' method with sending of metadata.
    *
    * @param cache the cache
    * @param data  the data to be sent to the cache
    * @param id    the object representing the listener ID
    * @return the list buffer receiving callback messages
    */
  private def registerAndReceive(cache: MetadataCache, data: MetadataResponse,
                                 id: ComponentID = TestComponentID): ListBuffer[MediumContent] =
    val buffer = register(cache, id, data.chunk.mediumID)
    cache receive data
    buffer

  /**
    * Creates a test cache instance.
    *
    * @param facade    the mock for the facade
    * @param cacheSize the size of the cache
    * @return the new cache instance
    */
  private def createCache(facade: MediaFacade, cacheSize: Int = DefaultCacheSize): MetadataCache =
    new MetadataCache(facade, cacheSize)

/**
  * Test class for ''MetaDataCache''.
  */
class MetadataCacheSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import MetadataCacheSpec._

  /**
    * Creates a mock for the media facade. The mock is prepared to answer
    * queries for metadata with a registration ID derived from the medium that
    * is queried. Optionally, a fix registration ID can be specified.
    *
    * @param optRegistrationID an optional fix registration ID
    * @return the media facade mock
    */
  private def createMediaFacade(optRegistrationID: Option[Int] = None): MediaFacade =
    val facade = mock[MediaFacade]
    val bus = mock[MessageBus]
    when(facade.bus).thenReturn(bus)
    when(facade.queryMetadataAndRegisterListener(any(classOf[MediumID])))
      .thenAnswer((invocation: InvocationOnMock) => {
        val mid = invocation.getArguments.head.asInstanceOf[MediumID]
        optRegistrationID getOrElse extractIndex(mid)
      })
    facade

  /**
    * Verifies that a request for metadata was sent via the facade.
    *
    * @param facade   the mock for the media facade
    * @param mediumID the expected medium ID
    * @param expTimes the expected number of requests for this medium
    */
  private def verifyMetaDataRequest(facade: MediaFacade, mediumID: MediumID = Medium,
                                    expTimes: Int = 1): Unit =
    verify(facade, times(expTimes)).queryMetadataAndRegisterListener(mediumID)

  /**
    * Checks whether a callback received the expected data.
    *
    * @param buffer   the buffer filled by the callback
    * @param expected the expected content messages
    */
  private def verifyReceivedChunks(buffer: ListBuffer[MediumContent],
                                   expected: MediumContent*): Unit =
    buffer.toList should be(expected.toList)

  "A MediumContent" should "provide an empty instance" in:
    MetadataCache.EmptyContent.data shouldBe empty
    MetadataCache.EmptyContent.complete shouldBe false

  it should "allow adding a chunk" in:
    val songIndices = Seq(1, 2, 3)
    val chunk = createResponse(songIndices, complete = false, Medium).chunk
    val expData = contentData(songIndices)

    val content = MetadataCache.EmptyContent.addChunk(chunk)
    content.complete shouldBe false
    content.data should contain theSameElementsAs expData

  it should "combine multiple chunks" in:
    val medium2 = mediumID(2)
    val songIndices1 = Seq(1, 2, 3)
    val songIndices2 = Seq(11, 12, 13)
    val chunk1 = createResponse(songIndices1, complete = false, Medium).chunk
    val chunk2 = createResponse(songIndices2, complete = true, medium2).chunk
    val expData = contentData(songIndices1) ++ contentData(songIndices2, medium2)

    val content = MetadataCache.EmptyContent.addChunk(chunk1).addChunk(chunk2)
    content.complete shouldBe true
    content.data should contain theSameElementsAs expData

  it should "resolve checksums in the file IDs" in:
    def checksum(idx: Int): String = s"check$idx"

    def createInfo(idx: Int): MediumInfo =
      MediumInfo("name" + idx, "someDesc" + idx, mediumID(idx), "noOrder", checksum(idx))

    val chunk1 = createChunk(1, complete = false).chunk
    val chunk2 = createChunk(2, complete = false, mediumID(2)).chunk
    val chunk3 = createChunk(3, complete = true, mediumID(13)).chunk
    val availableMedia = AvailableMedia(
      List(Medium -> createInfo(1),
        mediumID(2) -> createInfo(2),
        mediumID(3) -> createInfo(3))
    )
    val expData = Map(MediaFileID(Medium, songUri(1), Some(checksum(1))) -> songMetadata(1),
      MediaFileID(mediumID(2), songUri(2), Some(checksum(2))) -> songMetadata(2),
      MediaFileID(mediumID(13), songUri(3)) -> songMetadata(3))
    val content = MetadataCache.EmptyContent
      .addChunk(chunk1)
      .addChunk(chunk2)
      .addChunk(chunk3)

    val contentWithChecksums = content.resolveChecksums(availableMedia)
    contentWithChecksums.complete shouldBe true
    contentWithChecksums.data should contain theSameElementsAs expData

  "A MetadataCache" should "send a remote request for a medium not completely stored" in:
    val facade = createMediaFacade()
    val cache = createCache(facade)
    register(cache)

    verifyMetaDataRequest(facade)

  it should "send received metadata to a registered listener" in:
    val cache = createCache(createMediaFacade())
    val chunks = register(cache)
    val chunk = createChunk(0, complete = false)
    val content = createContent(0, complete = false)

    cache.receive(chunk)
    verifyReceivedChunks(chunks, content)

  it should "handle multiple listeners" in:
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val chunk1 = createChunk(0, complete = false)
    val chunk2 = createChunk(1, complete = false)
    val chunk3 = createChunk(2, complete = true)
    val content1 = createContent(0, complete = false)
    val content2 = createContent(1, complete = false)
    val content3 = createContent(2, complete = true)
    val content12 = createContent(List(0, 1), complete = false, Medium)
    val buf1 = register(cache)
    cache.receive(chunk1)
    cache.receive(chunk2)

    val buf2 = register(cache, ComponentID())
    cache.receive(chunk3)
    verifyMetaDataRequest(facade)
    verifyReceivedChunks(buf1, content1, content2, content3)
    verifyReceivedChunks(buf2, content12, content3)

  it should "handle multiple media" in:
    val Medium2 = mediumID(2)
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val chunk1 = createChunk(0, complete = false)
    val chunk2 = createChunk(1, complete = false, mediumID = Medium2)
    val content1 = createContent(0, complete = false)
    val content2 = createContent(1, complete = false, mediumID = Medium2)
    val buf1 = register(cache)

    val buf2 = register(cache, ComponentID(), Medium2)
    cache.receive(chunk1)
    cache.receive(chunk2)
    verifyMetaDataRequest(facade)
    verifyMetaDataRequest(facade, Medium2)
    verifyReceivedChunks(buf1, content1)
    verifyReceivedChunks(buf2, content2)

  it should "remove callbacks if all chunks for a medium have been received" in:
    val cache = createCache(createMediaFacade())
    val chunk1 = createChunk(1, complete = false)
    val chunk2 = createChunk(2, complete = true)
    val content1 = createContent(1, complete = false)
    val content2 = createContent(2, complete = true)
    val buf = register(cache)
    cache.receive(chunk1)
    cache.receive(chunk2)

    cache.receive(createChunk(3, complete = false))
    verifyReceivedChunks(buf, content1, content2)

  it should "not send a request for new data if the chunks are already complete" in:
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val chunk = createResponse(List(1, 2, 3), complete = true, Medium)
    val content = createContent(List(1, 2, 3), complete = true, Medium)
    val buf = register(cache)
    cache.receive(chunk)

    register(cache)
    verifyReceivedChunks(buf, content)
    verifyMetaDataRequest(facade)

  it should "correctly set the completed flag of combined chunks" in:
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val chunk1 = createResponse(List(1, 2, 3), complete = false, Medium)
    val chunk2 = createChunk(4, complete = true)
    val content = createContent(List(1, 2, 3, 4), complete = true, Medium)
    register(cache)
    cache.receive(chunk1)
    cache.receive(chunk2)

    val buf = register(cache)
    verifyReceivedChunks(buf, content)

  it should "allow removing a listener" in:
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val buf = register(cache)

    cache.receive(RemoveMetadataRegistration(Medium, TestComponentID))
    cache.receive(createChunk(1, complete = false))
    verifyReceivedChunks(buf)
    verify(facade).removeMetadataListener(Medium)

  it should "ignore a request to remove an unknown listener" in:
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val buf = register(cache)
    val chunk = createChunk(1, complete = false)
    val content = createContent(1, complete = false)

    cache.receive(RemoveMetadataRegistration(Medium, ComponentID()))
    cache.receive(chunk)
    verifyReceivedChunks(buf, content)
    verify(facade, never()).removeMetadataListener(Medium)

  it should "ignore a request to remove listeners for an unknown medium" in:
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val buf = register(cache)
    val chunk = createChunk(1, complete = false)
    val content = createContent(1, complete = false)
    val Medium2 = MediumID("_other", None)

    cache.receive(RemoveMetadataRegistration(Medium2, TestComponentID))
    cache.receive(chunk)
    verifyReceivedChunks(buf, content)
    verify(facade, never()).removeMetadataListener(Medium2)

  it should "not remove the remote medium listener if there are remaining listeners" in:
    val ListenerID2 = ComponentID()
    val facade = createMediaFacade()
    val cache = createCache(facade)
    val buf1 = register(cache)
    val buf2 = register(cache, ListenerID2)
    val chunk = createChunk(1, complete = false)
    val content = createContent(1, complete = false)

    cache.receive(RemoveMetadataRegistration(Medium, ListenerID2))
    cache.receive(chunk)
    verifyReceivedChunks(buf1, content)
    verifyReceivedChunks(buf2)
    verify(facade, never()).removeMetadataListener(Medium)

  it should "clean the cache when the archive becomes available again" in:
    val cache = createCache(createMediaFacade())
    cache.receive(createChunk(1, complete = false))

    cache.receive(MediaFacade.MediaArchiveAvailable)
    val buf = register(cache)
    verifyReceivedChunks(buf)

  it should "remove all listeners when the archive becomes unavailable" in:
    val cache = createCache(createMediaFacade())
    val buf = register(cache)

    cache.receive(MediaFacade.MediaArchiveUnavailable)
    cache.receive(createChunk(1, complete = false))
    verifyReceivedChunks(buf)

  it should "remove a medium from the cache if the cache size is reached" in:
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 50)
    val metaData = createMetadata(1 to 5: _*)
    metaData foreach (registerAndReceive(cache, _))
    verifyMetaDataRequest(facade)
    cache.numberOfEntries should be(cache.cacheSize)

    registerAndReceive(cache, createChunk(1, complete = false, mediumID = mediumID(10)))
    register(cache)
    verifyMetaDataRequest(facade, expTimes = 2)
    cache.numberOfEntries should be < cache.cacheSize

  it should "not remove the only medium from the cache" in:
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 9)
    val metaData = createResponse(1 to 10, complete = true, Medium)

    registerAndReceive(cache, metaData)
    register(cache)
    verifyMetaDataRequest(facade)

  it should "move a medium to the front when it is accessed" in:
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 50)
    val metaData = createMetadata(1 to 5: _*)
    metaData foreach (registerAndReceive(cache, _))

    register(cache)
    registerAndReceive(cache, createChunk(1, complete = false, mediumID = mediumID(10)))
    register(cache)
    verifyMetaDataRequest(facade)
    register(cache, mediumID = mediumID(2))
    verifyMetaDataRequest(facade, mediumID(2), expTimes = 2)

  it should "correctly maintain the LRU list when moving a medium to front" in:
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 30)
    val metaData = createMetadata(1 to 3: _*)
    metaData foreach (registerAndReceive(cache, _))

    register(cache)
    createMetadata(4 to 6: _*) foreach (registerAndReceive(cache, _))
    register(cache)
    verifyMetaDataRequest(facade, expTimes = 2)

  it should "remove multiple media from the cache if necessary to enforce the cache size" in:
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 50)
    val metaData = createMetadata(1 to 5: _*)
    metaData foreach (registerAndReceive(cache, _))

    registerAndReceive(cache, createMetadataForMedium(19, complete = true, mediumIdx = 10))
    register(cache)
    register(cache, mediumID = mediumID(2))
    verifyMetaDataRequest(facade, expTimes = 2)
    verifyMetaDataRequest(facade, mediumID = mediumID(2), expTimes = 2)

  it should "not remove media that are not yet complete" in:
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 20)
    registerAndReceive(cache, createMetadataForMedium(10, complete = false, mediumIdx = 1))
    registerAndReceive(cache, createMetadataForMedium(10, complete = true, mediumIdx = 2))

    registerAndReceive(cache, createMetadataForMedium(1, complete = true, mediumIdx = 3))
    register(cache)
    register(cache, mediumID = mediumID(2))
    verifyMetaDataRequest(facade)
    verifyMetaDataRequest(facade, mediumID = mediumID(2), expTimes = 2)

  it should "not remove the head of the LRU list" in:
    val facade = createMediaFacade()
    val cache = createCache(facade, cacheSize = 20)
    registerAndReceive(cache, createMetadataForMedium(10, complete = false, mediumIdx = 3))
    registerAndReceive(cache, createMetadataForMedium(10, complete = false, mediumIdx = 2))

    registerAndReceive(cache, createMetadataForMedium(10, complete = true, mediumIdx = 1))
    register(cache)
    verifyMetaDataRequest(facade)

  it should "ignore metadata with a wrong registration ID" in:
    val cache = createCache(createMediaFacade())
    val chunk1 = createResponse(1 to 10, complete = false, Medium)
    val chunk2 = createChunk(11, complete = true).copy(registrationID = 42)
    val content = createContent(1 to 10, complete = false, Medium)
    val chunks = registerAndReceive(cache, chunk1)

    cache receive chunk2
    verifyReceivedChunks(chunks, content)

  it should "not accept chunks after the completion of a medium" in:
    val cache = createCache(createMediaFacade())
    val chunk = createResponse(1 to 10, complete = true, Medium)
    val content = createContent(1 to 10, complete = true, Medium)
    registerAndReceive(cache, chunk)

    cache receive createChunk(11, complete = true)
    cache.numberOfEntries should be(10)
    val chunks = register(cache)
    verifyReceivedChunks(chunks, content)

  it should "correctly increase the cache size if a chunk is combined" in:
    val cache = createCache(createMediaFacade())
    registerAndReceive(cache, createChunk(1, complete = false))
    cache receive createChunk(2, complete = false)

    cache.numberOfEntries should be(2)

  it should "reset the registration ID when there are no more consumers" in:
    val lid2 = ComponentID()
    val cache = createCache(createMediaFacade())
    val chunk1 = createChunk(1, complete = false)
    val chunk2 = createChunk(2, complete = false)
    val content1 = createContent(1, complete = false)
    val content2 = createContent(2, complete = false)
    registerAndReceive(cache, chunk1)
    val chunks = register(cache, id = lid2)

    cache receive RemoveMetadataRegistration(Medium, TestComponentID)
    cache receive chunk2
    cache receive RemoveMetadataRegistration(Medium, lid2)
    cache receive createChunk(3, complete = false)
    verifyReceivedChunks(chunks, content1, content2)
    cache.numberOfEntries should be(0)

  it should "remove an incomplete medium if there are no more consumers" in:
    val facade = createMediaFacade()
    val cache = createCache(facade)
    registerAndReceive(cache, createChunk(1, complete = false))

    cache receive RemoveMetadataRegistration(Medium, TestComponentID)
    register(cache)
    verifyMetaDataRequest(facade, expTimes = 2)
    cache.numberOfEntries should be(0)

  it should "create a correct un-registration object from a registration" in:
    val reg = MetadataRegistration(Medium, TestComponentID, null)

    val unReg = reg.unRegistration
    unReg should be(RemoveMetadataRegistration(Medium, TestComponentID))

  it should "correctly handle the global undefined medium" in:
    val optRegID = Some(42)
    val undefMedium1 = mediumID(1).copy(mediumDescriptionPath = None)
    val undefMedium2 = mediumID(2).copy(mediumDescriptionPath = None)
    val chunk1 = createResponse(List(1, 2), complete = false, undefMedium1, optRegID)
    val chunk2 = createResponse(List(3, 4, 5), complete = false, undefMedium2, optRegID)
    val content1 = createContent(List(1, 2), complete = false, undefMedium1)
    val content2 = createContent(List(3, 4, 5), complete = false, undefMedium2)
    val content = MetadataCache.EmptyContent
      .addChunk(chunk1.chunk)
      .addChunk(chunk2.chunk)
    val cache = createCache(createMediaFacade(optRegistrationID = optRegID))

    val buf = register(cache, mediumID = MediumID.UndefinedMediumID)
    cache receive chunk1
    cache receive chunk2
    verifyReceivedChunks(buf, content1, content2)

    val buf2 = register(cache, mediumID = MediumID.UndefinedMediumID)
    verifyReceivedChunks(buf2, content)
