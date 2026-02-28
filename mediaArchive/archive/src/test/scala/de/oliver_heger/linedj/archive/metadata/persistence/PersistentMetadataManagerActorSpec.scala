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
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.{EnhancedMediaScanResult, MediaScanResult, PathUriConverter}
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetadataReaderActor.ReadMetadataFile
import de.oliver_heger.linedj.archive.metadata.{ScanForMetadataFiles, UnresolvedMetadataFiles}
import de.oliver_heger.linedj.archivecommon.parser.MetadataParser
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.actors.ChildActorFactory
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.Checksums.MediumChecksum
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata, MetadataFileInfo, RemovePersistentMetadata, RemovePersistentMetadataResult}
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingResult, MetadataProcessingSuccess}
import org.apache.pekko.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{ArrayBlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.reflect.ClassTag

object PersistentMetadataManagerActorSpec:
  /** A root path for scan results. */
  private val RootPath = Paths get "root"

  /** A path for the archive's ToC file. */
  private val TocPath = Paths.get("/path/to/toc.json")

  /** The name of the test archive. */
  private val ArchiveName = "MyTestArchive"

  /** The number of concurrent reader actors. */
  private val ParallelCount = 3

  /** The chunk size when reading metadata files. */
  private val ChunkSize = 42

  /** The number of media files in a medium. */
  private val FileCount = 8

  /** Index of the test medium. */
  private val MediumIndex = 1

  /** The name of the blocking dispatcher. */
  private val BlockingDispatcher = "test-blocking-dispatcher"

  /** Constant for the reader child actor class. */
  private val ClassReaderChildActor = PersistentMetadataReaderActor(null, 0).actorClass()

  /** Constant for the metadata file remove child actor class. */
  private val ClassRemoveChildActor = MetadataFileRemoveActor().actorClass()

  /** The converter used by the test actor. */
  private val Converter = new PathUriConverter(RootPath)

  /**
    * Generates a checksum based on the given index.
    *
    * @param index the index
    * @return the checksum for this index
    */
  private def checksum(index: Int): String = "check_" + index

  /**
    * Generates a [[Checksums.MediumChecksum]] based on the given index.
    *
    * @param index the index
    * @return the medium checksum for this index
    */
  private def mediumChecksum(index: Int): Checksums.MediumChecksum =
    Checksums.MediumChecksum(checksum(index))

  /**
    * Generates a medium ID based on the given index.
    *
    * @param index the index
    * @return the corresponding medium ID
    */
  private def mediumID(index: Int): MediumID = MediumID("someURI" + index, Some("Path" + index))

  /**
    * Generates a scan result that contains media derived from the passed in
    * indices.
    *
    * @param indices the indices
    * @return the ''MediaScanResult''
    */
  private def scanResult(indices: Int*): MediaScanResult =
    MediaScanResult(
      root = RootPath,
      mediaFiles = indices.map(i => (mediumID(i), mediumFiles(mediumID(i)))).toMap,
      archiveName = ArchiveName
    )

  /**
    * Generates an enhanced media scan result that contains media and their
    * checksums derived from the passed in indices.
    *
    * @param indices the indices
    * @return the ''EnhancedMediaScanResult''
    */
  private def enhancedScanResult(indices: Int*): EnhancedMediaScanResult =
    val checksumMapping = indices.map(i => (mediumID(i), MediumChecksum(checksum(i)))).toMap
    EnhancedMediaScanResult(scanResult(indices: _*), checksumMapping)

  /**
    * Generates a list of files on a test medium.
    *
    * @param mediumID the medium ID
    * @return the files on this test medium
    */
  private def mediumFiles(mediumID: MediumID): List[FileData] =
    val mediumPath = RootPath resolve mediumID.mediumURI
    (1 to FileCount).map(i => FileData(mediumPath resolve s"song$i.mp3", i * 1000)).toList

  /**
    * Generates the processing results for the test medium.
    *
    * @return a list with processing results for this medium
    */
  private def processingResults(): List[MetadataProcessingSuccess] =
    processingResults(mediumID(MediumIndex))

  /**
    * Generates processing results for the specified medium ID.
    *
    * @param mid the medium ID
    * @return a list with processing results for this medium
    */
  private def processingResults(mid: MediumID): List[MetadataProcessingSuccess] =
    mediumFiles(mid) map (f => MetadataProcessingSuccess(mid, Converter.pathToUri(f.path),
      MediaMetadata.UndefinedMediaData.copy(title = Some("Song " + f.path))))

  /**
    * Expects a message of the specified type for each of the passed in test
    * probes.
    *
    * @param probes the test probes
    * @tparam T the type of the message
    * @return a set with all received messages (the order is typically
    *         unspecified)
    */
  private def expectMessages[T](probes: TestProbe*)(implicit t: ClassTag[T]): Set[T] =
    probes.foldLeft(Set.empty[T])((s, p) => s + p.expectMsgType[T])

/**
  * Test class for ''PersistenceMetaDataManagerActor''.
  */
class PersistentMetadataManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar
  with FileTestHelper:

  import PersistentMetadataManagerActorSpec._

  def this() = this(ActorSystem("PersistenceMetaDataManagerActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    tearDownTestFile()

  /** A test path with persistent metadata files. */
  private val FilePath = Files.createDirectory(createPathInDirectory("testMetadataPath"))

  /**
    * Generates a path for the metadata file associated with the given
    * checksum.
    *
    * @param checksum the checksum
    * @return the corresponding metadata path
    */
  private def metaDataFile(checksum: String): Path =
    FilePath.resolve(checksum + ".mdt")

  /**
    * Generates a read meta file message for the medium with the specified index.
    *
    * @param index the index
    * @return the message for the reader actor
    */
  private def readerMessage(index: Int): PersistentMetadataReaderActor.ReadMetadataFile =
    PersistentMetadataReaderActor.ReadMetadataFile(metaDataFile(checksum(index)), mediumID(index))

  /**
    * Generates a map with data about metadata files corresponding to the
    * specified indices. Such a map can be returned by the mock file
    * scanner.
    *
    * @param indices the indices of contained media
    * @return a mapping for metadata files
    */
  private def persistentFileMapping(indices: Int*): Map[MediumChecksum, Path] =
    indices.map { i =>
      val cs = MediumChecksum(checksum(i))
      (cs, metaDataFile(cs.checksum))
    }.toMap

  /**
    * Generates a map with data about metadata files corresponding to the
    * specified indices using plain strings for checksum values.
    *
    * @param indices the indices of contained media
    * @return a mapping with metadata files
    */
  private def persistentFileMappingStr(indices: Int*): Map[String, Path] =
    persistentFileMapping(indices: _*) map (e => e._1.checksum -> e._2)

  /**
    * Tests a [[Sink]] that was passed in an [[UnresolvedMetadataFiles]]
    * message. The function checks whether the sink constructs a correct
    * metadata file at the expected path.
    *
    * @param unresolvedMsg the message about unresolved files
    */
  private def checkMetadataPersistenceSink(unresolvedMsg: UnresolvedMetadataFiles): Unit =
    val results = processingResults()
    val mid = unresolvedMsg.mediumID
    val metadataQueue = new LinkedBlockingQueue[MetadataProcessingResult]
    val errorResult = MetadataProcessingError(mid, MediaFileUri("errorFile"), new Exception("test exception"))
    val metadataSource = Source(errorResult :: results)

    import system.dispatcher
    metadataSource.runWith(unresolvedMsg.metadataSink).foreach: _ =>
      val expectedMetadataPath = FilePath.resolve(checksum(1) + ".mdt")
      val mdtSource = MetadataParser.parseMetadata(FileIO.fromPath(expectedMetadataPath), mid)
      val sink = Sink.foreach[MetadataProcessingSuccess](metadataQueue.offer)
      mdtSource.runWith(sink)

    def nextProcessingResult(): MetadataProcessingResult =
      val result = metadataQueue.poll(3, TimeUnit.SECONDS)
      result should not be null
      result

    val persistedResults = (1 to results.size).map: _ =>
      nextProcessingResult()
    metadataQueue.poll(100, TimeUnit.MILLISECONDS) should be(null)
    persistedResults should contain theSameElementsAs results

  "A PersistenceMetadataManagerActor" should "create a default file scanner" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val testRef = TestActorRef[PersistentMetadataManagerActor](PersistentMetadataManagerActor
      (helper.config, helper.metadataUnionActor.ref, Converter))

    testRef.underlyingActor.fileScanner should not be null

  it should "generate correct creation properties" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val props = PersistentMetadataManagerActor(helper.config, helper.metadataUnionActor.ref, Converter)

    classOf[PersistentMetadataManagerActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should have size 5
    props.args.head should be(helper.config)
    props.args(1) should be(helper.metadataUnionActor.ref)
    props.args(3) should be(Converter)
    props.args(4) shouldBe a[ArchiveTocSerializer.ArchiveTocWriter]

  it should "notify the caller for unknown media immediately" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2).createTestActor()
    val result = enhancedScanResult(3, 4)
    val ignoreSink = Sink.ignore

    actor ! result
    val unresolvedMsgs = Set(expectMsgType[UnresolvedMetadataFiles],
      expectMsgType[UnresolvedMetadataFiles])

    def unresolvedMessage(index: Int): UnresolvedMetadataFiles =
      val id = mediumID(index)
      UnresolvedMetadataFiles(id, result.scanResult.mediaFiles(id), result, Nil, ignoreSink)

    unresolvedMsgs.map(_.copy(metadataSink = ignoreSink)) should contain allOf(
      unresolvedMessage(3),
      unresolvedMessage(4)
    )
    helper.expectNoChildReaderActor()

  it should "provide a Sink to create missing metadata files" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(2).createTestActor()
    val result = enhancedScanResult(1, 2)
    actor ! result
    val unresolvedMsg = expectMsgType[UnresolvedMetadataFiles]

    checkMetadataPersistenceSink(unresolvedMsg)

  /**
    * Stops an actor and waits until the termination message arrives.
    *
    * @param actor the actor to be stopped
    */
  private def stopActor(actor: ActorRef): Unit =
    actor ! PoisonPill
    val probe = TestProbe()
    probe watch actor
    probe.expectTerminated(actor)

  it should "create reader actors for known media" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2).createTestActor()

    actor ! enhancedScanResult(1, 2, 3)
    expectMsgType[UnresolvedMetadataFiles].mediumID should be(mediumID(3))
    val readerActors = helper.expectChildReaderActors(count = 2)
    val readMessages = expectMessages[PersistentMetadataReaderActor.ReadMetadataFile](readerActors: _*)
    readMessages should contain allOf(readerMessage(1), readerMessage(2))

  it should "create not more reader actors than configured" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3, 4, 5).createTestActor()

    actor ! enhancedScanResult(1, 2, 3, 4, 5)
    helper.expectChildReaderActors(count = ParallelCount)
    helper.expectNoChildReaderActor()

  it should "handle the case that metadata files are retrieved later" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2).createTestActor(startFileScan = false)

    actor ! enhancedScanResult(1)
    actor ! ScanForMetadataFiles
    helper.expectChildReaderActor().expectMsg(readerMessage(1))

  it should "handle a failed future when reading metadata files" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles().createTestActor()

    actor ! enhancedScanResult(1)
    expectMsgType[UnresolvedMetadataFiles].mediumID should be(mediumID(1))
    helper.expectNoChildReaderActor()

  /**
    * Expects that processing results are sent to the listener actor.
    *
    * @param results the expected results
    */
  private def expectProcessingResults(results: List[MetadataProcessingSuccess]): Unit =
    results foreach (r => expectMsg(r))

  it should "send arriving metadata processing results to the manager actor" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1).createTestActor()
    actor ! enhancedScanResult(1)
    val results = processingResults() take (FileCount / 2)

    helper.sendProcessingResults(results)
    expectProcessingResults(results)

  it should "not crash for processing results with an unknown medium ID" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    helper.initMediaFiles(1).createTestActor()

    helper.sendProcessingResults(processingResults())

  it should "start processing of a new medium when a reader actor terminates" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3, 4).createTestActor()
    actor ! enhancedScanResult(1, 2, 3, 4)
    val readerActor = helper.expectChildReaderActor()
    val request = readerActor.expectMsgType[ReadMetadataFile]
    val readers = helper.expectChildReaderActors(2)
    val messages = expectMessages[ReadMetadataFile](readers: _*) + request
    val results = processingResults(request.mediumID)
    helper sendProcessingResults results
    expectProcessingResults(results)

    stopActor(readerActor.ref)
    val nextReader = helper.expectChildReaderActor()
    val msg = nextReader.expectMsgType[ReadMetadataFile]
    (messages + msg).map(_.mediumID) should contain allOf(mediumID(1), mediumID(2), mediumID(3),
      mediumID(4))

  it should "send unresolved files when a reader actor terminates" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1).createTestActor()
    val esr = enhancedScanResult(1)
    actor ! esr
    val readerActor = helper.expectChildReaderActor()
    readerActor.expectMsgType[ReadMetadataFile]
    val results = processingResults()
    val partialResults = results take 3
    helper sendProcessingResults partialResults
    expectProcessingResults(partialResults)

    stopActor(readerActor.ref)
    val mid = mediumID(1)
    val unresolvedMsg = expectMsgType[UnresolvedMetadataFiles]
    unresolvedMsg.mediumID should be(mid)
    unresolvedMsg.files should be(mediumFiles(mid) drop 3)
    unresolvedMsg.result should be(esr)
    unresolvedMsg.resolvedFiles should be(partialResults)

  it should "provide a sink to write persistent metadata" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1).createTestActor()
    val esr = enhancedScanResult(1)
    actor ! esr
    val readerActor = helper.expectChildReaderActor()
    readerActor.expectMsgType[ReadMetadataFile]

    stopActor(readerActor.ref)
    val unresolvedMsg = expectMsgType[UnresolvedMetadataFiles]
    checkMetadataPersistenceSink(unresolvedMsg)

  it should "remove a processed medium from the in-progress map" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2).createTestActor()
    actor ! enhancedScanResult(1, 2)
    val readerActors = helper.expectChildReaderActors(2)
    val mid1 = readerActors.head.expectMsgType[ReadMetadataFile]
      .mediumID
    val mid2 = readerActors(1).expectMsgType[ReadMetadataFile]
      .mediumID
    val results = processingResults(mid1) take 4
    helper sendProcessingResults results
    expectProcessingResults(results)
    val actorRef = readerActors.head.ref
    stopActor(actorRef)
    expectMsgType[UnresolvedMetadataFiles]

    helper sendProcessingResults results
    val results2 = processingResults(mid2) take 3
    helper sendProcessingResults results2
    expectProcessingResults(results2)

  it should "handle a Cancel request in the middle of processing" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3, 4, 5).createTestActor()
    actor ! enhancedScanResult(1, 2)
    val readerActors = helper.expectChildReaderActors(2)
    val mid1 = readerActors.head.expectMsgType[ReadMetadataFile]
      .mediumID
    val mid2 = readerActors(1).expectMsgType[ReadMetadataFile]
      .mediumID
    helper sendProcessingResults processingResults(mid1)

    actor ! CloseRequest
    stopActor(readerActors.head.ref)
    actor ! enhancedScanResult(3)
    helper sendProcessingResults processingResults(mid2)
    stopActor(readerActors(1).ref)
    expectProcessingResults(processingResults(mid1))
    expectMsg(CloseAck(actor))
    helper.expectNoChildReaderActor()

  it should "handle a Cancel request after processing" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1).createTestActor()
    actor ! enhancedScanResult(1)
    val reader = helper.expectChildReaderActor()
    val mid = reader.expectMsgType[ReadMetadataFile].mediumID
    val results = processingResults(mid)
    helper sendProcessingResults results
    expectProcessingResults(results)
    stopActor(reader.ref)
    helper.expectNoChildReaderActor()

    actor ! CloseRequest
    expectMsg(CloseAck(actor))

  it should "reset the CloseRequest after sending an Ack" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3, 4, 5, 6).createTestActor()
    actor ! enhancedScanResult(1, 2, 3, 4, 5, 6)
    val readers = helper.expectChildReaderActors(ParallelCount)
    expectMessages[PersistentMetadataReaderActor.ReadMetadataFile](readers: _*)
    actor ! CloseRequest
    readers foreach (a => stopActor(a.ref))
    expectMsg(CloseAck(actor))

    actor ! enhancedScanResult(6)
    val nextReader = helper.expectChildReaderActor()
    val mid = nextReader.expectMsgType[PersistentMetadataReaderActor.ReadMetadataFile].mediumID
    mid should be(mediumID(6))

  it should "return metadata information for assigned media" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2).createTestActor()
    actor.tell(enhancedScanResult(1, 2, 3), TestProbe().ref)

    actor ! PersistentMetadataManagerActor.FetchMetadataFileInfo(testActor)
    val info = expectMsgType[MetadataFileInfo]
    info.unusedFiles shouldBe empty
    info.metadataFiles should have size 2
    info.metadataFiles(mediumID(1)) should be(checksum(1))
    info.metadataFiles(mediumID(2)) should be(checksum(2))
    info.optUpdateActor should be(Some(testActor))

  it should "return metadata information for orphan files" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3, 4).createTestActor()
    actor ! enhancedScanResult(1)

    actor ! PersistentMetadataManagerActor.FetchMetadataFileInfo(testActor)
    val info = expectMsgType[MetadataFileInfo]
    info.unusedFiles should contain only(checksum(2), checksum(3), checksum(4))

  it should "handle a metadata file info request before a file scan" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.createTestActor(startFileScan = false)

    actor ! PersistentMetadataManagerActor.FetchMetadataFileInfo(testActor)
    expectMsg(MetadataFileInfo(Map.empty, Set.empty, None))

  it should "reset the internal checksum mapping when a new scan starts" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3, 4).createTestActor()
    actor ! enhancedScanResult(1, 2)
    helper.expectChildReaderActors(2).foreach { p =>
      p.expectMsgType[ReadMetadataFile]
      stopActor(p.ref)
      expectMsgType[UnresolvedMetadataFiles]
    }

    actor ! ScanForMetadataFiles
    actor ! enhancedScanResult(1)
    actor ! PersistentMetadataManagerActor.FetchMetadataFileInfo(testActor)
    val info = expectMsgType[MetadataFileInfo]
    info.metadataFiles.keySet should contain only mediumID(1)
    info.unusedFiles should contain(checksum(2))

  it should "update the metadata files after a successful metadata extract operation" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1).createTestActor()
    actor ! enhancedScanResult(1, 2)
    expectMsgType[UnresolvedMetadataFiles]

    helper.sendMetadataExtractionCompleted(actor, 2)
    actor ! PersistentMetadataManagerActor.FetchMetadataFileInfo(testActor)
    val info = expectMsgType[MetadataFileInfo]
    info.metadataFiles should contain(mediumID(2) -> checksum(2))
    info.unusedFiles shouldBe empty

  it should "ignore MetadataExtractionCompleted messages for unknown media" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1).createTestActor()
    actor ! enhancedScanResult(1, 2)
    expectMsgType[UnresolvedMetadataFiles]

    helper.sendMetadataExtractionCompleted(actor, 42)
    actor ! PersistentMetadataManagerActor.FetchMetadataFileInfo(testActor)
    val info = expectMsgType[MetadataFileInfo]
    info.metadataFiles should contain only (mediumID(1) -> checksum(1))

  it should "ignore a MetadataExtractionCompleted message before metadata info is available" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.createTestActor(startFileScan = false)

    helper.sendMetadataExtractionCompleted(actor, 1) // Boom

  it should "update the metadata files if a metadata extract operation failed" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3).createTestActor()
    actor ! enhancedScanResult(1, 2)

    helper.sendMetadataExtractionCompleted(actor, 2, success = false)
    actor ! PersistentMetadataManagerActor.FetchMetadataFileInfo(testActor)
    val info = expectMsgType[MetadataFileInfo]
    info.metadataFiles should contain(mediumID(1) -> checksum(1))
    info.metadataFiles should have size 1
    info.unusedFiles should be(Set(checksum(3)))

  it should "pass a remove files request to the remove child actor" in :
    val checksumSet = Set(checksum(3), checksum(4), checksum(5))
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3, 4).createTestActor()

    actor ! RemovePersistentMetadata(checksumSet)
    helper.removeActor.expectMsg(MetadataFileRemoveActor.RemoveMetadataFiles(checksumSet,
      persistentFileMappingStr(1, 2, 3, 4), testActor))

  it should "set the correct path if a metadata file was written successfully" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1).createTestActor()
    actor ! enhancedScanResult(1, 2)
    expectMsgType[UnresolvedMetadataFiles]

    helper.sendMetadataExtractionCompleted(actor, 2)
    val cs = checksum(2)
    actor ! RemovePersistentMetadata(Set(cs))
    val remMsg = helper.removeActor.expectMsgType[MetadataFileRemoveActor.RemoveMetadataFiles]
    val expPath = FilePath.resolve(cs + ".mdt")
    remMsg.pathMapping(cs) should be(expPath)

  it should "ignore a remove files request if metadata files are not available" in :
    val checksumSet = Set(checksum(1), checksum(2))
    val request = RemovePersistentMetadata(checksumSet)
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.createTestActor(startFileScan = false)

    actor ! request
    expectMsg(RemovePersistentMetadataResult(request, Set.empty))

  it should "process a response of a remove metadata files operation" in :
    val checksumSet = Set(checksum(3), checksum(4), checksum(5))
    val successSet = checksumSet - checksum(5)
    val request = MetadataFileRemoveActor.RemoveMetadataFiles(checksumSet,
      persistentFileMappingStr(1, 2, 3, 4), testActor)
    val response = MetadataFileRemoveActor.RemoveMetadataFilesResult(request,
      successSet)
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.createTestActor(startFileScan = false)

    actor receive response
    expectMsg(RemovePersistentMetadataResult(RemovePersistentMetadata(checksumSet),
      successSet))

  it should "update its checksum mapping when receiving a remove response" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3, 4).createTestActor()
    val request = MetadataFileRemoveActor.RemoveMetadataFiles(Set(checksum(1)),
      persistentFileMappingStr(1, 2, 3, 4), testActor)
    val response = MetadataFileRemoveActor.RemoveMetadataFilesResult(request,
      Set(checksum(1)))
    actor ! response
    expectMsgType[RemovePersistentMetadataResult]

    actor ! RemovePersistentMetadata(Set(checksum(2)))
    val request2 = helper.removeActor.expectMsgType[MetadataFileRemoveActor.RemoveMetadataFiles]
    request2.pathMapping should be(persistentFileMappingStr(2, 3, 4))

  it should "trigger a ToC write operation at the end of a scan" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3).createTestActor()
    actor ! enhancedScanResult(1, 2, 3)
    val expMedia = Map(
      mediumID(1) -> mediumChecksum(1),
      mediumID(2) -> mediumChecksum(2),
      mediumID(3) -> mediumChecksum(3)
    )

    helper.initTocWriter()
      .sendScanComplete(Some(TocPath))

    helper.verifyTocWriter(expMedia, Set.empty, Set.empty)

  it should "pass unused media to the ToC writer" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2).createTestActor()
    actor ! enhancedScanResult(2)
    val expMedia = Map(mediumID(2) -> mediumChecksum(2))
    val futToc = Future.successful(Some(TocPath))

    helper.initTocWriter(futToc)
      .sendScanComplete(Some(TocPath))

    helper.verifyTocWriter(expMedia, Set(mediumChecksum(1)), Set.empty)

  it should "pass modified media to the ToC writer" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1).createTestActor()
    actor ! enhancedScanResult(1, 2, 3)
    expectMsgType[UnresolvedMetadataFiles]
    expectMsgType[UnresolvedMetadataFiles]
    val expMedia = Map(
      mediumID(1) -> mediumChecksum(1),
      mediumID(2) -> mediumChecksum(2),
      mediumID(3) -> mediumChecksum(3)
    )
    val expModified = Set(mediumChecksum(2), mediumChecksum(3))
    val futToc: Future[Option[Path]] = Future.failed(new IllegalStateException("Test exception: ToC writer failed."))

    helper.sendScanComplete(Some(TocPath))
      .initTocWriter(futToc)
      .sendMetadataExtractionCompleted(actor, 2)
      .sendMetadataExtractionCompleted(actor, 3)

    helper.verifyTocWriter(expMedia, Set.empty, expModified)
    verifyNoMoreInteractions(helper.tocWriter)

  it should "reset the set of modified media when a new scan starts" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2).createTestActor()
    actor ! enhancedScanResult(1, 2, 3, 4)
    expectMsgType[UnresolvedMetadataFiles]
    helper.sendMetadataExtractionCompleted(actor, 1)

    actor ! ScanForMetadataFiles
    actor ! enhancedScanResult(1, 2)
    val expMedia = Map(
      mediumID(1) -> mediumChecksum(1),
      mediumID(2) -> mediumChecksum(2),
    )
    helper.initTocWriter()
      .sendScanComplete(Some(TocPath))

    helper.verifyTocWriter(expMedia, Set.empty, Set.empty)

  it should "not trigger a ToC write operation if no target path is defined" in :
    val helper = new PersistenceMetaDataManagerActorTestHelper
    val actor = helper.initMediaFiles(1, 2, 3).createTestActor()
    actor ! enhancedScanResult(1, 2, 3)

    helper.sendScanComplete(None)
    verifyNoInteractions(helper.tocWriter)

  /**
    * A test helper class collecting all dependencies of the test actor.
    */
  private class PersistenceMetaDataManagerActorTestHelper:
    /** A mock for the configuration. */
    val config: MediaArchiveConfig = createConfig()

    /** A mock for the file scanner. */
    val fileScanner: PersistentMetadataFileScanner = mock[PersistentMetadataFileScanner]

    /** Test probe for the child remove actor. */
    val removeActor: TestProbe = TestProbe()

    /** Test probe for the metadata union actor. */
    val metadataUnionActor: TestProbe = TestProbe()

    /** The mock for the ToC writer object. */
    val tocWriter: ArchiveTocSerializer.ArchiveTocWriter = mock

    /** The test actor created by this helper. */
    var managerActor: TestActorRef[PersistentMetadataManagerActor] = _

    /** A queue for the child actors created by the mock child actor factory. */
    private val childActorQueue = new LinkedBlockingQueue[TestProbe]

    /** A queue with test probes for reader actors. */
    private val testProbes = createTestProbes()

    /**
      * Prepares the mock file scanner to return the media files derived from
      * the passed in indices. If no indices are provided, the scanner is
      * configured to return a failed future.
      *
      * @param indices the indices
      * @return this test helper
      */
    def initMediaFiles(indices: Int*): PersistenceMetaDataManagerActorTestHelper =
      val futResult = if indices.isEmpty then Future.failed[Map[MediumChecksum, Path]](new IOException)
      else Future.successful(persistentFileMapping(indices: _*))
      when(fileScanner.scanForMetadataFiles(argEq(FilePath), argEq(BlockingDispatcher))(using any()))
        .thenReturn(futResult)
      this

    /**
      * Creates a test actor instance. This method should be called after the
      * scanner mock had been initialized.
      *
      * @param startFileScan a flag whether the file scan should be triggered
      * @return the test actor reference
      */
    def createTestActor(startFileScan: Boolean = true): TestActorRef[PersistentMetadataManagerActor] =
      managerActor = TestActorRef[PersistentMetadataManagerActor](createProps())
      if startFileScan then
        managerActor ! ScanForMetadataFiles
      managerActor

    /**
      * Sends the given collection of processing results to the test actor.
      *
      * @param results the result objects to be sent
      * @return this test helper
      */
    def sendProcessingResults(results: Iterable[MetadataProcessingSuccess]):
    PersistenceMetaDataManagerActorTestHelper =
      results foreach managerActor.receive
      this

    /**
      * Expects that the test actor created a new reader actor as child. The
      * probe representing the child is returned.
      *
      * @param timeout a timeout when waiting for the child creation
      * @param unit    the unit for the timeout
      * @return the test probe for the new child actor
      */
    def expectChildReaderActor(timeout: Long = 3, unit: TimeUnit = TimeUnit.SECONDS): TestProbe =
      val probe = childActorQueue.poll(timeout, unit)
      probe should not be null
      probe

    /**
      * Expects the given number of child reader actors to be created. The
      * corresponding probe objects are returned in a sequence.
      *
      * @param count   the number of child actors
      * @param timeout a timeout when waiting for the child creation
      * @param unit    the unit for the timeout
      * @return a sequence with the probes for the child actors
      */
    def expectChildReaderActors(count: Int, timeout: Long = 3, unit: TimeUnit = TimeUnit.SECONDS): Seq[TestProbe] =
      @tailrec
      def go(i: Int, children: List[TestProbe]): List[TestProbe] =
        if i >= count then children
        else go(i + 1, expectChildReaderActor(timeout, unit) :: children)

      go(0, Nil)

    /**
      * Expects that no new child reader actor has been created in the
      * specified timeout.
      *
      * @param timeout a timeout when waiting for the child creation
      * @param unit    the unit for the timeout
      */
    def expectNoChildReaderActor(timeout: Long = 500, unit: TimeUnit = TimeUnit.MILLISECONDS):
    Unit =
      childActorQueue.poll(timeout, unit) should be(null)

    /**
      * Generates and sends a ''MetadataExtractionCompleted'' test message for
      * the specified medium to the given test actor.
      *
      * @param actor   the test actor
      * @param medIdx  the medium index
      * @param success the success flag for the operation
      * @return the test helper
      */
    def sendMetadataExtractionCompleted(actor: TestActorRef[_], medIdx: Int, success: Boolean = true):
    PersistenceMetaDataManagerActorTestHelper =
      actor.receive(
        PersistentMetadataManagerActor.MetadataExtractionCompleted(mediumID(medIdx), success),
        testActor
      )
      this

    /**
      * Sends a ScanComplete message to the test actor and optionally a request
      * to write the ToC.
      *
      * @param tocPath the path to the file with the ToC
      * @return this test helper
      */
    def sendScanComplete(tocPath: Option[Path]): PersistenceMetaDataManagerActorTestHelper =
      when(config.contentFile).thenReturn(tocPath)
      managerActor receive PersistentMetadataManagerActor.ScanCompleted
      this

    /**
      * Initializes the mock for the ToC writer to expect an invocation and
      * sets the return value.
      *
      * @param futPath the future to return
      * @return this test helper
      */
    def initTocWriter(futPath: Future[Option[Path]] = Future.successful(None)):
    PersistenceMetaDataManagerActorTestHelper =
      given ActorSystem = any[ActorSystem]()

      when(tocWriter.writeToc(any(), any(), any(), any())).thenReturn(futPath)
      this

    /**
      * Verifies that the ToC writer was called with the expected arguments.
      *
      * @param expectedMedia    the expected media data
      * @param expectedUnused   the expected unused media
      * @param expectedModified the expected modified media
      * @return this test helper
      */
    def verifyTocWriter(expectedMedia: Map[MediumID, Checksums.MediumChecksum],
                        expectedUnused: Set[Checksums.MediumChecksum],
                        expectedModified: Set[Checksums.MediumChecksum]): PersistenceMetaDataManagerActorTestHelper =
      given ActorSystem = argEq(system)

      verify(tocWriter).writeToc(argEq(TocPath), argEq(expectedMedia), argEq(expectedUnused), argEq(expectedModified))
      this

    /**
      * Creates a mock for the configuration.
      *
      * @return the configuration mock
      */
    private def createConfig(): MediaArchiveConfig =
      val config = mock[MediaArchiveConfig]
      when(config.metadataPersistencePath).thenReturn(FilePath)
      when(config.metadataPersistenceParallelCount).thenReturn(ParallelCount)
      when(config.metadataPersistenceChunkSize).thenReturn(ChunkSize)
      when(config.blockingDispatcherName).thenReturn(BlockingDispatcher)
      when(config.contentFile).thenReturn(None)
      config

    /**
      * Creates the properties for creating a test actor. Here a child actor
      * factory is specified which returns test probes for reader actors. These
      * children are stored in the queue from where they can be queried.
      *
      * @return creation properties for a test actor instance
      */
    private def createProps(): Props =
      Props(new PersistentMetadataManagerActor(config, metadataUnionActor.ref,
        fileScanner, Converter, tocWriter) with ChildActorFactory:
        override def createChildActor(p: Props): ActorRef =
          p.actorClass() match
            case ClassReaderChildActor =>
              p.args should have length 2
              p.args.head should be(managerActor)
              p.args(1) should be(ChunkSize)
              val probe = testProbes.poll()
              childActorQueue put probe
              probe.ref

            case ClassRemoveChildActor =>
              p.args should have length 0
              removeActor.ref
      )

    /**
      * Creates test probes for reader actors. For some strange reasons, the
      * creation of a test probe in the test failed. So they are created
      * beforehand.
      *
      * @return a queue with test probes
      */
    private def createTestProbes(): ArrayBlockingQueue[TestProbe] =
      val probes = new ArrayBlockingQueue[TestProbe](8)
      for _ <- 1 to 8 do probes put TestProbe()
      probes

