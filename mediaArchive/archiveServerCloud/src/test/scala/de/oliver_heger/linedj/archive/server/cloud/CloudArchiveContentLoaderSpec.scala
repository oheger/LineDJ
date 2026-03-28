/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, actor as classic}
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

object CloudArchiveContentLoaderSpec:
  /**
    * Alias for a function that provides the data to mock a download operation.
    * It is invoked when a download from the archive is requested and has to
    * return the [[Source]] for the content of the response.
    */
  private type SourceProvider = () => Future[Source[ByteString, Any]]

  /**
    * A data class for storing media files passed to the content actor.
    *
    * @param mediumID the medium ID
    * @param fileUri  the URI of the media file
    * @param metadata the metadata about the song
    */
  private case class MediaFileData(mediumID: Checksums.MediumChecksum,
                                   fileUri: MediaFileUri,
                                   metadata: MediaMetadata)

  /** The number of test media contained in the simulated test archive. */
  private val TestMediaCount = 4

  /**
    * A collection of [[ArchiveModel.MediumDetails]] objects that are expected
    * to be passed to the content actor.
    */
  private val TestMediaDetails = (1 to TestMediaCount).map(ArchiveContentTestHelper.testMediumDetails)

  /**
    * A collection of data objects for songs that are expected to be passed to
    * the content actor.
    */
  private val TestMediaFiles = (1 to TestMediaCount).flatMap(ArchiveContentTestHelper.testSongDataForMedium)
    .map(mps => MediaFileData(Checksums.MediumChecksum(mps.mediumID.archiveComponentID), mps.uri, mps.metadata))

  /**
    * Generates the description path for a test medium in the archive.
    *
    * @param mediumID the ID of the test medium
    * @return the description path for this test medium
    */
  private def mediumDescriptionPath(mediumID: Checksums.MediumChecksum): String =
    val index = ArchiveContentTestHelper.testMediumIndex(mediumID)
    s"test-medium-$index/description.json"

  /**
    * Transforms the given content data into a string in JSON format.
    *
    * @param content the content
    * @return the JSON representation for this content
    */
  private def contentToJson(content: CloudArchiveContent): String =
    content.media.values.map(mediumEntryToJson).mkString(start = "[", sep = ",", end = "]")

  /**
    * Returns a JSON representation for the given entry.
    *
    * @param entry the entry
    * @return a JSON representation for this entry
    */
  private def mediumEntryToJson(entry: MediumEntry): String =
    s"""
       |{
       |  "changedAt": ${entry.timestamp},
       |  "checksum": "${entry.id.checksum}",
       |  "mediumDescriptionPath": "${mediumDescriptionPath(entry.id)}"
       |}
       |""".stripMargin

  /**
    * Returns a [[Source]] that yields the given string data.
    *
    * @param data the data to be produced by the source
    * @return the [[Source]] that yields this data
    */
  private def toSource(data: String): Source[ByteString, Any] =
    Source(ByteString(data).grouped(64).toList)

  /**
    * Returns a [[Future]] with a [[Source]] that produces the given string
    * which is needed as a return value for the content downloader.
    *
    * @param s the string representing the data
    * @return a [[Future]] with the [[Source]] yielding this data
    */
  private def toDownloadResult(s: String): Future[Source[ByteString, Any]] =
    Future.successful(toSource(s))

  /**
    * Generates a unique key for the given medium ID and cache entry type.
    *
    * @param mediumID  the medium ID
    * @param entryType the cache entry type
    * @return the key for this combination
    */
  private def entryKey(mediumID: Checksums.MediumChecksum, entryType: CloudArchiveCache.EntryType): String =
    s"${mediumID.checksum}|$entryType"

  /**
    * Appends an item to a list stored in an atomic reference. Note that this
    * function is called from an actor. Therefore, there is no concurrent
    * access.
    *
    * @param ref  the reference holding the list
    * @param item the item to append to the list
    * @tparam T the type of elements of this list
    */
  private def appendItem[T](ref: AtomicReference[List[T]], item: T): Unit =
    val current = ref.get()
    ref.set(item :: current)
end CloudArchiveContentLoaderSpec

/**
  * Test class for [[CloudArchiveContentLoader]].
  */
class CloudArchiveContentLoaderSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar:
  def this() = this(classic.ActorSystem("CloudArchiveContentLoaderSpec"))

  /** The test kit to test typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import CloudArchiveContentLoaderSpec.*

  "A CloudArchiveContentLoader" should "load the archive content from an up-to-date cache" in :
    val cacheContent = ArchiveContentTestHelper.archiveContent(TestMediaCount)
    val helper = new LoaderTestHelper
    helper.initCacheContent(cacheContent)
      .initCacheEntries(1 to TestMediaCount *)
      .initArchiveContent(cacheContent)

    helper.load().map: _ =>
      helper.verifyUpdatedCacheEntries()
        .verifyUpdatedCacheContent(null)
        .verifyArchiveContent()

  it should "load the archive content from the archive if the cache is uninitialized" in :
    val archiveContent = ArchiveContentTestHelper.archiveContent(TestMediaCount)
    val helper = new LoaderTestHelper
    helper.initCacheContent(CloudArchiveContent(Map.empty))
      .initArchiveContent(archiveContent)
      .initMediaDocuments(1 to TestMediaCount *)

    helper.load() map : _ =>
      helper.verifyUpdatedCacheContent(archiveContent)
        .verifyUpdatedCacheEntries(1 to TestMediaCount *)
        .verifyArchiveContent()

  it should "load unmodified entries from the cache, modified from the archive" in :
    val ModifiedIndex = TestMediaCount
    val modifiedEntry = ArchiveContentTestHelper.testMediumEntry(ModifiedIndex).copy(timestamp = 20260307165039L)
    val archiveContent = ArchiveContentTestHelper.archiveContent(TestMediaCount)
    val modifiedMedia = archiveContent.media + (modifiedEntry.id -> modifiedEntry)
    val cacheContent = CloudArchiveContent(modifiedMedia)
    val helper = new LoaderTestHelper
    helper.initCacheContent(cacheContent)
      .initArchiveContent(archiveContent)
      .initCacheEntries(1 until TestMediaCount *)
      .initMediaDocuments(ModifiedIndex)

    helper.load() map : _ =>
      helper.verifyUpdatedCacheContent(archiveContent)
        .verifyUpdatedCacheEntries(ModifiedIndex)
        .verifyArchiveContent()

  it should "ignore obsolete entries from the cache" in :
    val archiveContent = ArchiveContentTestHelper.archiveContent(TestMediaCount)
    val cacheContent = ArchiveContentTestHelper.archiveContent(TestMediaCount + 1)
    val helper = new LoaderTestHelper
    helper.initCacheContent(cacheContent)
      .initArchiveContent(archiveContent)
      .initCacheEntries(1 to TestMediaCount *)

    helper.load() map : _ =>
      helper.verifyUpdatedCacheContent(archiveContent)
        .verifyUpdatedCacheEntries()
        .verifyArchiveContent()

  it should "take the parallelism into account" in :
    val archiveContent = ArchiveContentTestHelper.archiveContent(2)
    val probeSource = typedTestKit.createTestProbe[String]()
    val promiseDesc1 = Promise[Source[ByteString, Any]]()
    val promiseDesc2 = Promise[Source[ByteString, Any]]()
    val promiseMetadata1 = Promise[Source[ByteString, Any]]()
    val promiseMetadata2 = Promise[Source[ByteString, Any]]()

    def notifyingSourceProvider(promSource: Promise[Source[ByteString, Any]]): SourceProvider = () =>
      probeSource.ref ! s"request at ${System.nanoTime()}"
      promSource.future

    val helper = new LoaderTestHelper
    helper.initCacheContent(CloudArchiveContent(Map.empty))
      .initArchiveContent(archiveContent)
      .expectDescriptionDownload(
        mediumDescriptionPath(ArchiveContentTestHelper.testMediumID(1))
      )(notifyingSourceProvider(promiseDesc1))
      .expectDescriptionDownload(
        mediumDescriptionPath(ArchiveContentTestHelper.testMediumID(2))
      )(notifyingSourceProvider(promiseDesc2))
      .expectMetadataDownload(ArchiveContentTestHelper.testMediumID(1))(notifyingSourceProvider(promiseMetadata1))
      .expectMetadataDownload(ArchiveContentTestHelper.testMediumID(2))(notifyingSourceProvider(promiseMetadata2))

    val futLoad = helper.load(parallelism = 2)

    (1 to 4).foreach(_ => probeSource.expectMessageType[String])
    probeSource.expectNoMessage(250.millis)

    promiseDesc1.success(toSource(ArchiveContentTestHelper.testMediumDescription(1)))
    promiseMetadata1.success(toSource(ArchiveContentTestHelper.testMediumMetadata(1)))
    promiseDesc2.success(toSource(ArchiveContentTestHelper.testMediumDescription(2)))
    promiseMetadata2.success(toSource(ArchiveContentTestHelper.testMediumMetadata(2)))
    futLoad map : result =>
      result should be(Done)

  /**
    * A test helper class to manage the object under test and its dependencies.
    */
  private class LoaderTestHelper:
    /** The mock for the content downloader. */
    private val downloader = mock[ContentDownloader]

    /** A variable to store updates of the cache's content- */
    private val updatedCacheContent = AtomicReference[CloudArchiveContent]

    /** A map storing the data of initial cache entries. */
    private val cacheEntries = ConcurrentHashMap[String, String]

    /** A map that stores entries written to the cache. */
    private val updatedEntries = ConcurrentHashMap[String, String]

    /** The mock for the archive cache. */
    private val cache = createCacheMock()

    /** Stores a list of detail objects passed to the content actor. */
    private val refAddedMediaContent = AtomicReference(List.empty[ArchiveModel.MediumDetails])

    /** Stores a list of song metadata passed to the content actor. */
    private val refAddedFilesContent = AtomicReference(List.empty[MediaFileData])

    /**
      * Prepares the mock for the content downloader to return a content
      * document with the provided data.
      *
      * @param content the content to be returned
      * @return this test helper
      */
    def initArchiveContent(content: CloudArchiveContent): LoaderTestHelper =
      when(downloader.loadContentDocument()).thenReturn(toDownloadResult(contentToJson(content)))
      this

    /**
      * Prepares the mock for the content downloader to return a medium
      * description document for a given path. The content of the document is
      * obtained from a [[SourceProvider]] function.
      *
      * @param path the requested path to be handled
      * @param src  the [[SourceProvider]] function
      * @return this test helper
      */
    def expectDescriptionDownload(path: String)(src: SourceProvider): LoaderTestHelper =
      when(downloader.loadMediumDescription(path)).thenAnswer((invocation: InvocationOnMock) => src())
      this

    /**
      * Prepares the mock for the content downloader to return a medium
      * metadata document for a given medium ID. The content of the document is
      * otained from a [[SourceProvider]] function.
      *
      * @param mediumID the requested medium ID to be handled
      * @param src      the [[SourceProvider]] function
      * @return this test helper
      */
    def expectMetadataDownload(mediumID: Checksums.MediumChecksum)(src: SourceProvider): LoaderTestHelper =
      when(downloader.loadMediumMetadata(mediumID)).thenAnswer((invocation: InvocationOnMock) => src())
      this

    /**
      * Prepares the mock for the content downloader to return generated
      * documents for the test media with the provided indices.
      *
      * @param indices the indices of test media
      * @return this test helper
      */
    def initMediaDocuments(indices: Int*): LoaderTestHelper =
      indices.foreach: index =>
        val mediumID = ArchiveContentTestHelper.testMediumID(index)
        val path = mediumDescriptionPath(mediumID)
        val description = ArchiveContentTestHelper.testMediumDescription(index)
        val metadata = ArchiveContentTestHelper.testMediumMetadata(index)
        expectDescriptionDownload(path)(() => toDownloadResult(description))
        expectMetadataDownload(mediumID)(() => toDownloadResult(metadata))
      this

    /**
      * Prepares the mock for the cache to return the given content document.
      *
      * @param content the content to be returned by the cache
      * @return this test helper
      */
    def initCacheContent(content: CloudArchiveContent): LoaderTestHelper =
      when(cache.loadAndValidateContent).thenReturn(Future.successful(content))
      this

    /**
      * Prepares the mock for the cache to provide entries for the test media
      * with the given indices.
      *
      * @param indices the indices of test media
      * @return this test helper
      */
    def initCacheEntries(indices: Int*): LoaderTestHelper =
      indices.foreach: index =>
        val mediumID = ArchiveContentTestHelper.testMediumID(index)
        val description = ArchiveContentTestHelper.testMediumDescription(index)
        val metadata = ArchiveContentTestHelper.testMediumMetadata(index)
        cacheEntries.put(entryKey(mediumID, CloudArchiveCache.EntryType.MediumDescription), description)
        cacheEntries.put(entryKey(mediumID, CloudArchiveCache.EntryType.MediumSongs), metadata)
      this

    /**
      * Performs a load operation with a test archive loader using the prepared
      * dependencies managed by this object.
      *
      * @param parallelism the value for the parallelism parameter
      * @return the [[Future]] with the outcome of this operation
      */
    def load(parallelism: Int = 1): Future[Done] =
      val loader = new CloudArchiveContentLoader
      val actor = typedTestKit.spawn(contentActor())
      loader.loadContent(downloader, cache, ArchiveContentTestHelper.TestArchiveName, actor, parallelism)

    /**
      * Verifies that the content of the cache was updated as expected. To test
      * that no update was done, pass '''null''' as argument.
      *
      * @param expected the expected updated cache content
      * @return this test helper
      */
    def verifyUpdatedCacheContent(expected: CloudArchiveContent): LoaderTestHelper =
      updatedCacheContent.get() should be(expected)
      this

    /**
      * Verifies that cache entries for the test media with the given indices
      * have been written (and only those).
      *
      * @param indices the indices of updated test media
      * @return this test helper
      */
    def verifyUpdatedCacheEntries(indices: Int*): LoaderTestHelper =
      updatedEntries should have size 2 * indices.length
      forEvery(indices): index =>
        val mediumID = ArchiveContentTestHelper.testMediumID(index)
        val description = ArchiveContentTestHelper.testMediumDescription(index)
        val metadata = ArchiveContentTestHelper.testMediumMetadata(index)
        updatedEntries.get(entryKey(mediumID, CloudArchiveCache.EntryType.MediumDescription)) should be(description)
        updatedEntries.get(entryKey(mediumID, CloudArchiveCache.EntryType.MediumSongs)) should be(metadata)
      this

    /**
      * Verifies that the expected content of the archive has been passed to
      * the content actor. Note that the content is the same for all test
      * cases. The tests only differ in the locations from which test media are
      * loaded (the cache or the remote archive).
      *
      * @return the result of the verification
      */
    def verifyArchiveContent(): Assertion =
      refAddedMediaContent.get() should contain theSameElementsAs TestMediaDetails
      refAddedFilesContent.get() should contain theSameElementsAs TestMediaFiles

    /**
      * Creates a mock for the cache and prepares it for standard interactions.
      * Especially the methods to access entries and to update the content are
      * mocked to access internal data structures.
      *
      * @return the mock for the archive cache
      */
    private def createCacheMock(): CloudArchiveCache =
      val cacheMock = mock[CloudArchiveCache]

      given classic.ActorSystem = ArgumentMatchers.eq(testSystem)

      when(cacheMock.saveContent(any())).thenAnswer(
        (invocation: InvocationOnMock) =>
          updatedCacheContent.get() should be(null)
          updatedCacheContent.set(invocation.getArgument(0))
          Future.successful(())
      )
      when(cacheMock.entryFor(any(), any())).thenAnswer(
        (invocation: InvocationOnMock) =>
          val mediumID = invocation.getArgument[String](0)
          val entryType = invocation.getArgument[CloudArchiveCache.EntryType](1)
          cacheEntry(entryKey(Checksums.MediumChecksum(mediumID), entryType))
      )
      cacheMock

    /**
      * Creates a cache entry implementation for a given entry key. The
      * implementation reads data from and writes data to the maps managed by
      * the test helper class. That way, the content of the cache can be
      * controlled, and updates to the cache can be tracked.
      *
      * @param entryKey the key for this entry
      * @return the cache entry for the given key
      */
    private def cacheEntry(entryKey: String): CloudArchiveCache.CacheEntry =
      new CloudArchiveCache.CacheEntry:
        override def source: Source[ByteString, Any] =
          val content = cacheEntries.get(entryKey)
          content should not be null
          toSource(content)

        override def sink: Sink[ByteString, Future[Any]] =
          val foldSink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
          foldSink.mapMaterializedValue: futData =>
            futData map : data =>
              updatedEntries.put(entryKey, data.utf8String)

    /**
      * Returns the behavior of an actor simulating the archive's content 
      * actor. The actor implementation records the passed data, so that it can
      * be verified whether the full content of the test archive has been
      * processed.
      *
      * @return the behavior for the content actor
      */
    private def contentActor(): Behavior[ArchiveCommands.UpdateArchiveContentCommand] =
      Behaviors.receiveMessage:
        case ArchiveCommands.UpdateArchiveContentCommand.AddMedium(details: ArchiveModel.MediumDetails) =>
          appendItem(refAddedMediaContent, details)
          Behaviors.same
        case ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(mid, uri, metadata) =>
          appendItem(refAddedFilesContent, MediaFileData(mid, uri, metadata))
          Behaviors.same