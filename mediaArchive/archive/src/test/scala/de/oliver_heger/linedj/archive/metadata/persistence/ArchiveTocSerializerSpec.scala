/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.metadata.persistence.ArchiveTocSerializerSpec.{TestTime, createInternalEntry, createMediaData, createMediumChecksum, createMediumEntries, createMediumEntry, createMediumID, readToc, testClock, writeToc}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}
import org.apache.pekko.testkit.TestKit
import org.scalatest.{Assertion, BeforeAndAfterAll, BeforeAndAfterEach, OptionValues}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.Future

object ArchiveTocSerializerSpec:
  /** An instant representing the test time used by tests. */
  private val TestTimeInstant = Instant.parse("2025-12-23T11:38:15Z")

  /** The test timestamp used by this test class. */
  private val TestTime = TestTimeInstant.toEpochMilli

  /**
    * Creates a test [[MediumID]] based on the given index.
    *
    * @param index the index
    * @return the test medium ID with this index
    */
  private def createMediumID(index: Int): MediumID =
    val indexStr = if index < 10 then "0" + index else index.toString
    MediumID(s"some-medium-uri-$indexStr", Some(s"medium$indexStr"))

  /**
    * Creates a checksum for a test medium based on the given index.
    *
    * @param index the index
    * @return the checksum for this test medium
    */
  private def createMediumChecksum(index: Int): Checksums.MediumChecksum =
    Checksums.MediumChecksum(s"test-medium-$index")

  /**
    * Creates a list with information about media based on the provided indices.
    *
    * @param fromIdx the start index
    * @param toIdx   the end index (exclusive)
    * @return the list with information about media
    */
  private def createMediaData(fromIdx: Int, toIdx: Int): Map[MediumID, Checksums.MediumChecksum] =
    (fromIdx to toIdx).map: idx =>
      createMediumID(idx) -> createMediumChecksum(idx)
    .toMap

  /**
    * Creates a test internal medium entry based on the given index.
    *
    * @param index     the index
    * @param timestamp the last modified timestamp for this entry
    * @return the internal medium entry for this index
    */
  private def createInternalEntry(index: Int, timestamp: Long = TestTime): ArchiveTocSerializer.InternalMediumEntry =
    ArchiveTocSerializer.InternalMediumEntry(
      mediumDescriptionPath = createMediumID(index).mediumDescriptionPath.get,
      checksum = Some(createMediumChecksum(index).checksum),
      changedAt = Some(timestamp)
    )

  /**
    * Creates a test medium entry based on the given index.
    *
    * @param index the index
    * @return the medium entry for this index
    */
  private def createMediumEntry(index: Int): ArchiveTocSerializer.MediumEntry =
    ArchiveTocSerializer.MediumEntry(
      mediumDescriptionPath = createMediumID(index).mediumDescriptionPath.get,
      checksum = createMediumChecksum(index).checksum,
      changedAt = TestTime
    )

  /**
    * Creates a number of test medium entries based on the given index range.
    *
    * @param fromIdx   the start index
    * @param toIdx     the end index (exclusive)
    * @param timestamp the timestamp for the entries
    * @return the list with test entries
    */
  private def createMediumEntries(fromIdx: Int,
                                  toIdx: Int,
                                  timestamp: Long): List[ArchiveTocSerializer.InternalMediumEntry] =
    (fromIdx to toIdx).map: index =>
      createInternalEntry(index, timestamp)
    .toList

  /**
    * Creates a [[Clock]] that can be used by tests that always returns the
    * test timestamp.
    *
    * @return the test clock
    */
  private def testClock(): Clock = Clock.fixed(TestTimeInstant, ZoneId.of("Z"))

  /**
    * Reads the given file with a table of contents and performs a JSON
    * de-serialization.
    *
    * @param path the path to the file to read
    * @return the list of entries read from this file
    */
  private def readToc(path: Path): List[ArchiveTocSerializer.InternalMediumEntry] =
    val data = Files.readString(path)
    val json = data.parseJson
    json.convertTo[List[ArchiveTocSerializer.InternalMediumEntry]]

  /**
    * Writes a ToC file with the given entries.
    *
    * @param path    the path where to write the file
    * @param entries the entries to write
    * @return the path to the file that was written
    */
  private def writeToc(path: Path, entries: List[ArchiveTocSerializer.InternalMediumEntry]): Path =
    val json = entries.toJson.compactPrint
    Files.writeString(path, json)
end ArchiveTocSerializerSpec

/**
  * Test class for [[ArchiveTocSerializer]].
  */
class ArchiveTocSerializerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with OptionValues with FileTestHelper:
  def this() = this(ActorSystem("ArchiveTocSerializerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  /**
    * Verifies that the ToC file at the given paths contains exactly the
    * expected entries.
    *
    * @param path            the path
    * @param expectedEntries the expected entries
    * @return the result of the check
    */
  private def verifyToc(path: Path, expectedEntries: Iterable[ArchiveTocSerializer.InternalMediumEntry]): Assertion =
    readToc(path) should contain theSameElementsInOrderAs expectedEntries

  "An ArchiveTocWriter" should "not write anything if there are no changes" in :
    val mediaData = createMediaData(1, 8)
    val originalEntries = createMediumEntries(1, 8, TestTime)
    val target = writeToc(createPathInDirectory("toc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer()

    writer.writeToc(target, mediaData, Set.empty, Set.empty) map : result =>
      result shouldBe empty

  it should "create a new file containing modified media" in :
    val mediaData = createMediaData(1, 3)
    val modified = Set(createMediumChecksum(1), createMediumChecksum(2), createMediumChecksum(3))
    val target = createPathInDirectory("newToc.json")
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, modified) map : result =>
      result.value should be(target)
      val expectedEntries = List(
        createInternalEntry(1),
        createInternalEntry(2),
        createInternalEntry(3)
      )
      verifyToc(target, expectedEntries)

  it should "override modified entries in the ToC file" in :
    val originalTime = TestTime - 10000
    val mediaData = createMediaData(1, 8)
    val originalEntries = createMediumEntries(1, 8, originalTime)
    val target = writeToc(createPathInDirectory("overrideToc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, Set(createMediumChecksum(1))) map : result =>
      result.value should be(target)
      val expectedEntries = originalEntries.head.copy(changedAt = Some(TestTime)) :: originalEntries.tail
      verifyToc(target, expectedEntries)

  it should "handle non existing medium checksums" in :
    val mediaData = createMediaData(2, 2)
    val modified = Set(createMediumChecksum(1), createMediumChecksum(2))
    val target = createPathInDirectory("invalidChecksum.json")
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, modified) map : _ =>
      verifyToc(target, List(createInternalEntry(2)))

  it should "remove obsolete entries from the ToC" in :
    val mediaData = createMediaData(2, 2)
    val originalEntries = createMediumEntries(1, 2, TestTime - 17)
    val target = writeToc(createPathInDirectory("obsoleteToc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, Set(createMediumChecksum(2))) map : _ =>
      verifyToc(target, List(createInternalEntry(2)))

  it should "remove unused entries from the ToC" in :
    val mediaData = createMediaData(1, 16)
    val originalTime = TestTime - 128000
    val originalEntries = createMediumEntries(1, 16, originalTime)
    val unused = (1 to 8).map(createMediumChecksum).toSet
    val target = writeToc(createPathInDirectory("unusedToc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer()

    writer.writeToc(target, mediaData, unused, Set.empty) map : result =>
      result.value should be(target)
      val expectedEntries = createMediumEntries(9, 16, originalTime)
      verifyToc(target, expectedEntries)

  it should "add entries for new media" in :
    val mediaData = createMediaData(1, 16)
    val originalTime = TestTime - 128000
    val originalEntries = createMediumEntries(1, 14, originalTime)
    val target = writeToc(createPathInDirectory("newEntriesToc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, Set.empty) map : result =>
      result.value should be(target)
      val expectedEntries = originalEntries ++ createMediumEntries(15, 16, TestTime)
      verifyToc(target, expectedEntries)

  it should "filter out medium IDs without a path" in :
    val invalidMediumID = createMediumID(1).copy(mediumDescriptionPath = None)
    val mediaData = createMediaData(2, 4) + (invalidMediumID -> createMediumChecksum(1))
    val modified = mediaData.values.toSet
    val target = createPathInDirectory("noPathsToc.json")
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, modified) map : _ =>
      val expectedEntries = createMediumEntries(2, 4, TestTime)
      verifyToc(target, expectedEntries)

  it should "ignore invalid entries in the existing ToC file" in :
    val mediaData = createMediaData(2, 2)
    val originalEntries = List(
      ArchiveTocSerializer.InternalMediumEntry(
        mediumDescriptionPath = "some/description/path",
        checksum = None,
        changedAt = None,
        metaDataPath = None
      )
    )
    val target = writeToc(createPathInDirectory("invalidEntriesToc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, Set(createMediumChecksum(2))) map : _ =>
      val expectedEntries = List(createInternalEntry(2))
      verifyToc(target, expectedEntries)

  it should "replace the metadata path by the checksum for existing entries" in :
    val mediaData = createMediaData(1, 5)
    val originalTime = TestTime - 256000
    val originalEntries = createMediumEntries(1, 4, originalTime).map: e =>
      e.copy(checksum = None, metaDataPath = e.checksum.map(_ + ".mdt"))
    val target = writeToc(createPathInDirectory("legacyToc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, Set(createMediumChecksum(5))) map : _ =>
      val expectedEntries = createMediumEntries(1, 4, originalTime).appended(createInternalEntry(5))
      verifyToc(target, expectedEntries)

  it should "set the timestamp if it is missing for existing entries" in :
    val mediaData = createMediaData(1, 5)
    val originalEntries = createMediumEntries(1, 4, 0).map: e =>
      e.copy(changedAt = None, checksum = None, metaDataPath = e.checksum.map(_ + ".mdt"))
    val target = writeToc(createPathInDirectory("noTimestampsToc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer(testClock())

    writer.writeToc(target, mediaData, Set.empty, Set(createMediumChecksum(5))) map : _ =>
      val expectedEntries = createMediumEntries(1, 5, TestTime)
      verifyToc(target, expectedEntries)
      
  it should "not write the ToC file if there are unused media not contained in existing entries" in:
    val mediaData = createMediaData(1, 4)
    val originalEntries = createMediumEntries(1, 4, TestTime)
    val unusedEntries = Set(createMediumChecksum(5), createMediumChecksum(42))
    val target = writeToc(createPathInDirectory("unusedButIrrelevantToc.json"), originalEntries)
    val writer = ArchiveTocSerializer.writer()

    writer.writeToc(target, mediaData, unusedEntries, Set.empty) map : result =>
      result shouldBe empty

  /**
    * Reads a ToC file using the given reader.
    *
    * @param reader the reader
    * @param path   the path to the file to read
    * @return a [[Future]] with the entries that were read
    */
  private def readTocWithReader(reader: ArchiveTocSerializer.ArchiveTocReader, path: Path):
  Future[List[ArchiveTocSerializer.MediumEntry]] =
    val sink =
      Sink.fold[List[ArchiveTocSerializer.MediumEntry], ArchiveTocSerializer.MediumEntry](List.empty): (lst, e) =>
        e :: lst
    reader.readToc(FileIO.fromPath(path)).runWith(sink)

  "An ArchiveTocReader" should "return a Source to read a ToC file" in :
    val mediaData = createMediaData(1, 4)
    val modified = Set(createMediumChecksum(2), createMediumChecksum(3), createMediumChecksum(4))
    val expectedEntries = List(
      createMediumEntry(1),
      createMediumEntry(2),
      createMediumEntry(3),
      createMediumEntry(4)
    )
    val target = createPathInDirectory("newTocToRead.json")
    val writer = ArchiveTocSerializer.writer(testClock())

    for
      writeResult <- writer.writeToc(target, mediaData, Set.empty, modified)
      readResult <- readTocWithReader(ArchiveTocSerializer.reader(), writeResult.value)
    yield
      readResult should contain theSameElementsAs expectedEntries

  it should "ignore invalid entries in the ToC file to read" in :
    val originalEntries = List(
      ArchiveTocSerializer.InternalMediumEntry(
        mediumDescriptionPath = "some/description/path",
        checksum = None,
        changedAt = None,
        metaDataPath = None
      ),
      createInternalEntry(2)
    )
    val target = writeToc(createPathInDirectory("invalidEntriesToc.json"), originalEntries)
    val reader = ArchiveTocSerializer.reader()

    readTocWithReader(reader, target) map : result =>
      result should contain only createMediumEntry(2)

  it should "set the timestamp if it is missing for entries" in :
    val originalEntries = createMediumEntries(1, 4, 0).map: e =>
      e.copy(changedAt = None, checksum = None, metaDataPath = e.checksum.map(_ + ".mdt"))
    val target = writeToc(createPathInDirectory("noTimestampsToc.json"), originalEntries)
    val reader = ArchiveTocSerializer.reader(testClock())

    readTocWithReader(reader, target) map : result =>
      val expectedEntries = (1 to 4).map(createMediumEntry)
      result should contain theSameElementsAs expectedEntries
