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

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.nio.file.Files
import scala.concurrent.Future

/**
  * Test class for [[CloudArchiveCache]].
  */
class CloudArchiveCacheSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, BeforeAndAfterEach, Matchers, FileTestHelper:
  def this() = this(ActorSystem("CloudArchiveCacheSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import ArchiveContentTestHelper.*

  /**
    * Returns a new instance of [[CloudArchiveCache]] to be tested that is
    * initialized with the managed temporary folder.
    *
    * @return the new cache instance
    */
  private def createCache(): CloudArchiveCache = CloudArchiveCache.newInstance(testDirectory)

  /**
    * Creates an entry in the cache for a specific medium.
    *
    * @param cache     the cache object
    * @param mediumID  the ID of the medium
    * @param entryType the type of the entry
    * @param data      the data to be stored
    * @return a [[Future]] with the result of the operation
    */
  private def writeCacheFile(cache: CloudArchiveCache,
                             mediumID: Checksums.MediumChecksum,
                             entryType: CloudArchiveCache.EntryType,
                             data: String): Future[Any] =
    val entry = cache.entryFor(mediumID, entryType)
    val source = Source(ByteString(data).grouped(64).toList)
    source.runWith(entry.sink)

  /**
    * Creates entries for all provided items in the given cache.
    *
    * @param cache   the cache object
    * @param entries the list with entries to be created
    * @return a [[Future]] with the result
    */
  private def writeCacheFiles(cache: CloudArchiveCache, entries: List[MediumEntry]): Future[Any] =
    entries match
      case e :: t =>
        val index = testMediumIndex(e.id)
        val futEntries = for
          _ <- writeCacheFile(cache, e.id, CloudArchiveCache.EntryType.MediumDescription, testMediumDescription(index))
          r <- writeCacheFile(cache, e.id, CloudArchiveCache.EntryType.MediumSongs, testMediumMetadata(index))
        yield r
        futEntries.flatMap(_ => writeCacheFiles(cache, t))
      case _ =>
        Future.successful(())

  /**
    * Creates entries for all media listed in the given content document in a
    * cache.
    *
    * @param cache   the cache object
    * @param content the content document
    * @return a [[Future]] with the result
    */
  private def writeCacheFiles(cache: CloudArchiveCache, content: CloudArchiveContent): Future[Any] =
    writeCacheFiles(cache, content.media.values.toList)

  /**
    * Reads the data from the given cache entry.
    *
    * @param entry the entry to read
    * @return a [[Future]] with the string content of this entry
    */
  private def readEntry(entry: CloudArchiveCache.CacheEntry): Future[String] =
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    entry.source.runWith(sink).map(_.utf8String)

  "A CloudArchiveCache" should "return an empty content document if the cache is uninitialized" in :
    val cache = createCache()

    cache.loadAndValidateContent map : content =>
      content should be(CloudArchiveContent(Map.empty))

  it should "return the content document if the cache is up-to-date" in :
    val content = archiveContent(8)
    val cache = createCache()

    for
      _ <- writeCacheFiles(cache, content)
      _ <- cache.saveContent(content)
      result <- cache.loadAndValidateContent
    yield
      result should be(content)

  it should "support reading an entry for a medium description" in :
    val mediumID = testMediumID(42)
    writeFileContent(createPathInDirectory(s"${mediumID.checksum}.json"), FileTestHelper.TestData)
    val cache = createCache()

    readEntry(cache.entryFor(mediumID, CloudArchiveCache.EntryType.MediumDescription)) map : data =>
      data should be(FileTestHelper.TestData)

  it should "support reading an entry for the songs of a medium" in :
    val mediumID = testMediumID(42)
    writeFileContent(createPathInDirectory(s"${mediumID.checksum}.mdt"), FileTestHelper.TestData)
    val cache = createCache()

    readEntry(cache.entryFor(mediumID, CloudArchiveCache.EntryType.MediumSongs)) map : data =>
      data should be(FileTestHelper.TestData)

  it should "remove media with missing entries from the content document" in :

    def deleteFilesFromEntries(): Future[Any] = Future:
      val filesToDelete = List(testMediumID(9).checksum + ".mdt", testMediumID(10).checksum + ".json")
      filesToDelete.foreach: f =>
        Files.delete(testDirectory.resolve(f))

    val content = archiveContent(10)
    val cache = createCache()

    for
      _ <- writeCacheFiles(cache, content)
      _ <- cache.saveContent(content)
      _ <- deleteFilesFromEntries()
      result <- cache.loadAndValidateContent
    yield
      val expectedMedia = content.media - testMediumID(9) - testMediumID(10)
      result.media should be(expectedMedia)

  it should "remove entries from the cache for incomplete media" in :
    val cache = createCache()

    for
      _ <- cache.saveContent(archiveContent(1))
      _ <- writeCacheFiles(cache, List(testMediumEntry(1)))
      _ <- writeCacheFile(cache, testMediumID(2), CloudArchiveCache.EntryType.MediumSongs, "testSongData")
      _ <- writeCacheFile(cache, testMediumID(3), CloudArchiveCache.EntryType.MediumDescription, "testDesc")
      _ <- cache.loadAndValidateContent
    yield
      Files.isRegularFile(testDirectory.resolve(testMediumID(1).checksum + ".mdt")) shouldBe true
      val deletedPaths = List(
        testDirectory.resolve(testMediumID(2).checksum + ".mdt"),
        testDirectory.resolve(testMediumID(3).checksum + ".json")
      )
      forEvery(deletedPaths): p =>
        Files.exists(p) shouldBe false

  it should "not remove the table of contents file" in :
    val cache = createCache()

    for
      _ <- cache.saveContent(archiveContent(1))
      _ <- writeCacheFiles(cache, List(testMediumEntry(1)))
      _ <- writeCacheFile(cache, testMediumID(2), CloudArchiveCache.EntryType.MediumSongs, "testSongData")
      _ <- writeCacheFile(cache, testMediumID(3), CloudArchiveCache.EntryType.MediumDescription, "testDesc")
      _ <- cache.loadAndValidateContent
      result <- cache.loadAndValidateContent
    yield
      result.media should have size 1

  it should "remove entries from the cache for non-existing media" in :
    val cache = createCache()

    for
      _ <- cache.saveContent(archiveContent(1))
      _ <- writeCacheFiles(cache, List(testMediumEntry(1), testMediumEntry(2)))
      result <- cache.loadAndValidateContent
    yield
      result.media.keySet should contain only testMediumID(1)
      val deletedPaths = List(
        testDirectory.resolve(testMediumID(2).checksum + ".mdt"),
        testDirectory.resolve(testMediumID(2).checksum + ".json")
      )
      forEvery(deletedPaths): p =>
        Files.exists(p) shouldBe false
