/*
 * Copyright 2015-2016 The Developers Team.
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
package de.oliver_heger.linedj.archive.metadata

import java.nio.file.{Path, Paths}
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media._
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec

object MetaDataManagerActorSpec {
  /** The maximum message size. */
  private val MaxMessageSize = 24

  /** ID of a test medium. */
  private val TestMediumID = mediumID("medium1")

  /** A list of medium IDs used by the tests. */
  private val MediaIDs = List(TestMediumID, mediumID("otherMedium"),
    mediumID("coolMusic"))

  /** A test scan result object. */
  private val ScanResult = createScanResult()

  /** A test enhanced scan result object. */
  private val EnhancedScanResult = createEnhancedScanResult(ScanResult)

  /** The undefined medium ID for the scan result. */
  private val UndefinedMediumID = MediumID(ScanResult.root.toString, None)

  /** A special test message sent to actors. */
  private val TestMessage = new Object

  /**
   * Helper method for generating a path.
    *
    * @param s the name of this path
   * @return the path
   */
  private def path(s: String): Path = Paths get s

  /**
    * Generates the URI for a path. This is used to construct a URI mapping.
    *
    * @param path the path
    * @return the URI for this path
    */
  private def uriFor(path: Path): String = "song://" + path.toString

  /**
   * Generates a medium ID.
    *
    * @param name a unique name for the ID
   * @return the medium ID
   */
  private def mediumID(name: String): MediumID = {
    val settingsPath = Paths.get(name, "playlist.settings")
    MediumID fromDescriptionPath settingsPath
  }

  /**
   * Creates a test meta data object for the specified path.
    *
    * @param path the path
   * @return the meta data for this path (following conventions)
   */
  private def metaDataFor(path: Path): MediaMetaData = {
    val index = extractPathIndex(path)
    MediaMetaData(title = Some(path.getFileName.toString),
      duration = Some(index * 10),
      size = index * 100)
  }

  /**
   * Generates a number of media files that belong to the specified test
   * medium.
    *
    * @param mediumPath the path of the medium
   * @param count the number of files to generate
   * @return the resulting list
   */
  private def generateMediaFiles(mediumPath: Path, count: Int): List[FileData] = {
    val basePath = Option(mediumPath.getParent) getOrElse mediumPath
    @tailrec
    def loop(current: List[FileData], index: Int): List[FileData] = {
      if (index == 0) current
      else loop(FileData(basePath.resolve(s"TestFile_$index.mp3"), 20) :: current, index - 1)
    }

    loop(Nil, count)
  }

  /**
    * Extracts an index from the given path. All test paths produced by
    * ''generateMediaFiles()'' end on a numeric index (before the file
    * extension). This function extracts this index. (Note: it is not very
    * robust for other paths.)
    *
    * @param path the path
    * @return the index of this path
    */
  private def extractPathIndex(path: Path): Int = {
    val pathStr = path.toString takeWhile(_ != '.')
    @tailrec def loop(index: Int): Int =
      if (Character isDigit pathStr(index)) loop(index - 1)
      else pathStr.substring(index + 1).toInt

    loop(pathStr.length - 1)
  }

  /**
   * Creates a test scan result object.
   */
  private def createScanResult(): MediaScanResult = {
    val numbersOfSongs = List(3, 8, 4)
    val rootPath = path("Root")
    val fileData = MediaIDs zip numbersOfSongs map { e =>
      (e._1, generateMediaFiles(path(e._1.mediumDescriptionPath.get), e._2))
    }
    val fileMap = Map(fileData: _*) + (MediumID(rootPath.toString, None) -> generateMediaFiles
      (path("noMedium"), 11))
    MediaScanResult(rootPath, fileMap)
  }

  /**
   * Creates an enhanced scan result. This method adds checksum information.
    *
    * @param result the plain result
   * @return the enhanced result
   */
  private def createEnhancedScanResult(result: MediaScanResult): EnhancedMediaScanResult = {
    EnhancedMediaScanResult(result, result.mediaFiles map (e => (e._1, "checksum_" + e._1
      .mediumURI)), createFileUriMapping(result))
  }

  /**
    * Generates an ''AvailableMedia'' message for the specified scan result.
    *
    * @param result the scan result
    * @return the corresponding available message
    */
  private def createAvailableMedia(result: MediaScanResult): AvailableMedia = {
    val mediaInfo = result.mediaFiles.keys.map { mid =>
      (mid, MediumInfo(mediumID = mid, name = "Medium " + mid.mediumURI,
        description = "", orderMode = "", orderParams = "", checksum = "c" + mid.mediumURI))
    }.toMap
    AvailableMedia(mediaInfo)
  }

  /**
    * Generates a global URI to file mapping for the given result object.
    *
    * @param result the ''MediaScanResult''
    * @return the URI to file mapping for this result
    */
  private def createFileUriMapping(result: MediaScanResult): Map[String, FileData] =
    result.mediaFiles.values.flatten.map(f => (uriFor(f.path), f)).toMap

  /**
    * Helper method to ensure that no more messages are sent to a test probe.
    * This message sends a special message to the probe and checks whether it is
    * immediately received.
    *
    * @param probe the probe to be checked
    */
  private def expectNoMoreMessage(probe: TestProbe): Unit = {
    probe.ref ! TestMessage
    probe.expectMsg(TestMessage)
  }
}

/**
 * Test class for ''MetaDataManagerActor''.
 */
class MetaDataManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import MetaDataManagerActorSpec._

  def this() = this(ActorSystem("MetaDataManagerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A MetaDataManagerActor" should "send an answer for an unknown medium ID" in {
    val actor = system.actorOf(MetaDataManagerActor(mock[MediaArchiveConfig], TestProbe().ref))

    val mediumID = MediumID("unknown medium ID", None)
    actor ! GetMetaData(mediumID, registerAsListener = false)
    expectMsg(UnknownMedium(mediumID))
  }

  it should "pass a scan result to the meta data persistence manager actor" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.startProcessing()
    helper.persistenceManager.expectMsg(EnhancedScanResult)
  }

  it should "ignore a scan result before the scan is started" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.actor receive EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)
  }

  it should "allow querying a complete medium" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))
    val msg = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false)
    checkMetaDataChunk(msg, TestMediumID, ScanResult.mediaFiles(TestMediumID), expComplete = true)
  }

  /**
   * Checks whether a meta data chunk received from the test actor contains the
   * expected data for the given medium ID. File URIs are expected to follow
   * default conventions.
    *
    * @param msg the chunk message to be checked
   * @param mediumID the medium ID as string
   * @param expectedFiles the expected files
   * @param expComplete the expected complete flag
   */
  private def checkMetaDataChunk(msg: MetaDataChunk, mediumID: MediumID,
                                 expectedFiles: List[FileData], expComplete: Boolean): Unit = {
    checkMetaDataChunkWithUris(msg, mediumID, expectedFiles, expComplete)(uriFor)
  }

  /**
    * Checks whether a meta data chunk received from the test actor contains the
    * expected data for the given medium ID and verifies the URIs in the chunk.
    *
    * @param msg the chunk message to be checked
    * @param mediumID the medium ID as string
    * @param expectedFiles the expected files
    * @param expComplete the expected complete flag
    * @param uriGen the URI generator to be used
    */
  private def checkMetaDataChunkWithUris(msg: MetaDataChunk, mediumID: MediumID,
                                         expectedFiles: List[FileData], expComplete: Boolean)
                                        (uriGen: Path => String): Unit = {
    msg.mediumID should be(mediumID)
    msg.data should have size expectedFiles.size
    expectedFiles foreach { m =>
      msg.data(uriGen(m.path)) should be(metaDataFor(m.path))
    }
    msg.complete shouldBe expComplete
  }

  it should "return a partial result for a medium when processing is not yet complete" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    val msg = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false)
    msg.mediumID should be(TestMediumID)
    msg.data shouldBe 'empty
    msg.complete shouldBe false
  }

  it should "notify medium listeners when new results become available" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    val msgEmpty = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = true)
    msgEmpty.data shouldBe 'empty
    msgEmpty.complete shouldBe false

    val filesForChunk1 = ScanResult.mediaFiles(TestMediumID).take(2)
    helper.sendProcessingResults(TestMediumID, filesForChunk1)
    checkMetaDataChunk(expectMsgType[MetaDataChunk], TestMediumID, filesForChunk1, expComplete =
      false)

    val filesForChunk2 = List(ScanResult.mediaFiles(TestMediumID).last)
    helper.sendProcessingResults(TestMediumID, filesForChunk2)
    checkMetaDataChunk(expectMsgType[MetaDataChunk], TestMediumID, filesForChunk2, expComplete =
      true)
  }

  it should "allow querying files not assigned to a medium" in {
    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val files = ScanResult.mediaFiles(UndefinedMediumID)
    helper.sendProcessingResults(UndefinedMediumID, files)

    helper.actor ! GetMetaData(UndefinedMediumID, registerAsListener = false)
    checkMetaDataChunk(expectMsgType[MetaDataChunk], UndefinedMediumID, files, expComplete = true)
  }

  it should "handle the undefined medium even over multiple scan results" in {
    def refUri(mediumID: MediumID)(path: Path): String =
      MediaFileUriHandler.PrefixReference + mediumID.mediumURI + ":" + uriFor(path)

    def findUrisInChunk(mediumID: MediumID, chunk: MetaDataChunk, files: Seq[FileData]): Unit = {
      files.map(d => refUri(mediumID)(d.path)).filterNot(chunk.data.contains) should have length 0
    }

    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val filesForChunk1 = ScanResult.mediaFiles(UndefinedMediumID) dropRight 1
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk1)
    helper.actor ! GetMetaData(MediumID.UndefinedMediumID, registerAsListener = true)
    checkMetaDataChunkWithUris(expectMsgType[MetaDataChunk], MediumID.UndefinedMediumID,
      filesForChunk1, expComplete = false)(refUri(UndefinedMediumID))
    val filesForChunk2 = List(ScanResult.mediaFiles(UndefinedMediumID).last)
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk2)
    checkMetaDataChunkWithUris(expectMsgType[MetaDataChunk], MediumID.UndefinedMediumID,
      filesForChunk2, expComplete = true)(refUri(UndefinedMediumID))

    val filesForChunk3 = generateMediaFiles(path("fileOnOtherMedium"), 4)
    val root2 = path("anotherRootDirectory")
    val UndefinedMediumID2 = MediumID(root2.toString, None)
    val scanResult2 = MediaScanResult(root2, Map(UndefinedMediumID2 ->
      filesForChunk3))
    helper.actor ! EnhancedMediaScanResult(scanResult2, Map(UndefinedMediumID2 -> "testCheckSum"),
      createFileUriMapping(scanResult2))
    helper.sendProcessingResults(UndefinedMediumID2, filesForChunk3)
    helper.actor ! GetMetaData(MediumID.UndefinedMediumID, registerAsListener = false)
    val chunk = expectMsgType[MetaDataChunk]
    findUrisInChunk(UndefinedMediumID, chunk, ScanResult.mediaFiles(UndefinedMediumID))
    findUrisInChunk(UndefinedMediumID2, chunk, filesForChunk3)
  }

  /**
    * Generates an alternative scan result and tells the test actor to process
    * it.
    * @param helper the test helper
    * @param files the files to be found in this scan result
    * @return the alternative scan result
    */
  private def processAnotherScanResult(helper: MetaDataManagerActorTestHelper, files: List[FileData]): EnhancedMediaScanResult = {
    val root = path("anotherRootDirectory")
    val medID = MediumID(root.toString, Some("someDescFile.txt"))
    val scanResult2 = MediaScanResult(root, Map(medID -> files))
    val esr = EnhancedMediaScanResult(scanResult2, Map(medID -> "testCheckSum"),
      createFileUriMapping(scanResult2))
    helper.actor ! esr
    helper.sendProcessingResults(medID, files)
    esr
  }

  /**
    * Tells the test actor to process another medium with the specified
    * content.
    *
    * @param helper the test helper
    * @param files the files to be found on this medium
    * @return a generated ID for the other medium
    */
  private def processAnotherMedium(helper: MetaDataManagerActorTestHelper, files: List[FileData])
  : MediumID =
    processAnotherScanResult(helper, files).scanResult.mediaFiles.keys.head

  it should "split large chunks of meta data into multiple ones" in {
    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val files = generateMediaFiles(path("fileOnOtherMedium"), MaxMessageSize + 4)
    val medID = processAnotherMedium(helper, files)

    helper.actor ! GetMetaData(medID, registerAsListener = true)
    checkMetaDataChunk(expectMsgType[MetaDataChunk], medID, files take MaxMessageSize,
      expComplete = false)
    checkMetaDataChunk(expectMsgType[MetaDataChunk], medID, files drop MaxMessageSize,
      expComplete = true)
  }

  it should "not split a chunk when processing of this medium is complete" in {
    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val files = generateMediaFiles(path("fileOnOtherMedium"), MaxMessageSize)
    val medID = processAnotherMedium(helper, files)

    helper.actor ! GetMetaData(medID, registerAsListener = true)
    checkMetaDataChunk(expectMsgType[MetaDataChunk], medID, files take MaxMessageSize,
      expComplete = true)
  }

  it should "allow removing a medium listener" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    val data = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = true).data
    data shouldBe 'empty

    helper.actor ! RemoveMediumListener(TestMediumID, testActor)
    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false),
      TestMediumID, ScanResult.mediaFiles(TestMediumID), expComplete = true)
  }

  it should "ignore an unknown medium when removing a medium listener" in {
    val actor = TestActorRef[MediaManagerActor](MetaDataManagerActor(mock[MediaArchiveConfig],
      TestProbe().ref))
    actor receive RemoveMediumListener(mediumID("someMedium"), testActor)
  }

  it should "extract meta data from files that could not be resolved" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    val unresolved1 = UnresolvedMetaDataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head) drop 1, EnhancedScanResult)
    val unresolved2 = UnresolvedMetaDataFiles(MediaIDs(1), ScanResult.mediaFiles(MediaIDs(1)),
      EnhancedScanResult)
    helper.actor ! unresolved1
    val processor = helper.nextChild()
    processor.expectMsg(ProcessMediaFiles(unresolved1.mediumID, unresolved1.files))
    helper.actor ! unresolved2
    processor.expectMsg(ProcessMediaFiles(unresolved2.mediumID, unresolved2.files))
    helper.numberOfChildActors should be(1)
  }

  it should "create different processor actors for different media roots" in {
    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val files = generateMediaFiles(path("otherPath"), 2)
    val otherResult = processAnotherScanResult(helper, files)
    val unresolved1 = UnresolvedMetaDataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head), EnhancedScanResult)
    val unresolved2 = UnresolvedMetaDataFiles(otherResult.scanResult.mediaFiles.keys.head, files, otherResult)
    helper.actor ! unresolved1
    helper.nextChild().expectMsgType[ProcessMediaFiles]

    helper.actor ! unresolved2
    helper.nextChild().expectMsg(ProcessMediaFiles(unresolved2.mediumID, unresolved2.files))
    helper.numberOfChildActors should be(2)
  }

  it should "reset the scanInProgress flag if all data is available" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendAllProcessingResults(ScanResult)
    helper.sendAvailableMedia()

    helper.actor ! EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)
  }

  it should "not reset the scanInProgress flag before all processing results arrived" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendAvailableMedia().
      sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))

    helper.actor ! EnhancedScanResult
    helper.persistenceManager.expectMsg(EnhancedScanResult)
  }

  it should "reset internal data before starting another scan" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendAllProcessingResults(ScanResult).sendAvailableMedia()

    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    val results = ScanResult.mediaFiles(TestMediumID) take 2
    helper.sendProcessingResults(TestMediumID, results)
    helper.sendProcessingResults(MediaIDs(1), ScanResult.mediaFiles(MediaIDs(1)))
    helper.actor ! EnhancedScanResult
    helper.persistenceManager.expectMsg(EnhancedScanResult)
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false),
      TestMediumID, results, expComplete = false)
  }

  it should "handle a Cancel request in the middle of processing" in {
    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    val files = generateMediaFiles(path("otherPath"), 2)
    val otherResult = processAnotherScanResult(helper, files)
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.actor ! UnresolvedMetaDataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head), EnhancedScanResult)
    val processor1 = helper.nextChild()
    processor1.expectMsgType[ProcessMediaFiles]
    helper.actor ! UnresolvedMetaDataFiles(otherResult.scanResult.mediaFiles.keys.head, files,
      otherResult)
    val processor2 = helper.nextChild()
    processor2.expectMsgType[ProcessMediaFiles]

    helper.actor ! CloseRequest
    helper.persistenceManager.expectMsg(CloseRequest)
    processor1.expectMsg(CloseRequest)
    processor2.expectMsg(CloseRequest)

    helper.actor ! CloseAck(processor1.ref)
    helper.actor receive EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)
    helper.actor ! CloseAck(helper.persistenceManager.ref)
    helper.sendAvailableMedia()
    helper.actor ! CloseAck(processor2.ref)
    expectMsg(CloseAck(helper.actor))
  }

  it should "not complete a Cancel request before all media have been received" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    val probe = TestProbe()
    helper.actor.tell(CloseRequest, probe.ref)

    helper.actor receive CloseAck(helper.persistenceManager.ref)
    expectNoMoreMessage(probe)
  }

  it should "ignore another Cancel request while one is pending" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.actor ! CloseRequest
    helper.persistenceManager.expectMsg(CloseRequest)

    helper.actor ! CloseRequest
    expectNoMoreMessage(helper.persistenceManager)
  }

  it should "ignore CloseAck messages if no cancel request is pending" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.sendAvailableMedia()

    helper.actor receive CloseAck(helper.persistenceManager.ref)
  }

  it should "send a CloseAck when available media are received at last" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    helper.actor ! CloseRequest
    helper.actor ! CloseAck(helper.persistenceManager.ref)
    helper.sendAvailableMedia()
    expectMsg(CloseAck(helper.actor))
  }

  it should "reset the cancel request after the scan has been aborted" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.actor ! CloseRequest
    helper.persistenceManager.expectMsg(CloseRequest)
    helper.actor ! CloseAck(helper.persistenceManager.ref)
    helper.sendAvailableMedia()
    expectMsg(CloseAck(helper.actor))

    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
  }

  it should "reset received CloseAck messages after the scan has been aborted" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.actor ! CloseRequest
    helper.sendAvailableMedia()
    helper.actor ! CloseAck(helper.persistenceManager.ref)
    expectMsg(CloseAck(helper.actor))

    helper.startProcessing(checkPersistenceMan = false)
    val probe = TestProbe()
    helper.actor.tell(CloseRequest, probe.ref)
    helper.sendAvailableMedia()
    expectNoMoreMessage(probe)
  }

  it should "correctly terminate the scan operation when it is canceled" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.actor ! CloseRequest
    helper.persistenceManager.expectMsg(CloseRequest)
    helper.sendAvailableMedia()
    helper.actor ! CloseAck(helper.persistenceManager.ref)
    expectMsg(CloseAck(helper.actor))

    helper.actor receive EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)
  }

  it should "ignore processing results while a Cancel request is pending" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    val results = ScanResult.mediaFiles(TestMediumID) take 2
    helper.sendProcessingResults(TestMediumID, results)
    helper.actor ! CloseRequest

    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID) drop 2)
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false),
      TestMediumID, results, expComplete = false)
  }

  it should "handle a Cancel request if no scan is in progress" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.actor ! CloseRequest
    expectMsg(CloseAck(helper.actor))
  }

  it should "ignore a scan start message if a scan is in progress" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))

    helper.actor ! MediaScanStarts
    helper.queryAndExpectMetaData(TestMediumID,
      registerAsListener = false).data should not be 'empty
  }

  it should "remove all medium listeners when the scan is canceled" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    val probe = TestProbe()
    helper.actor.tell(GetMetaData(TestMediumID, registerAsListener = true), probe.ref)
    probe.expectMsgType[MetaDataChunk]
    helper.actor ! CloseRequest
    helper.actor ! CloseAck(helper.persistenceManager.ref)
    helper.sendAvailableMedia()
    expectMsg(CloseAck(helper.actor))

    helper.startProcessing(checkPersistenceMan = false)
    helper.sendAllProcessingResults(ScanResult)
    expectNoMoreMessage(probe)
  }

  it should "pass the current meta data state to a newly registered state listener" in {
    val helper = new MetaDataManagerActorTestHelper
    val listener = helper.newStateListener(expectStateMsg = false)

    listener.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 0,
      size = 0, duration = 0, scanInProgress = false)))
  }

  it should "correctly update the scan in progress state when a scan starts" in {
    val helper = new MetaDataManagerActorTestHelper
    val listener = TestProbe()
    helper.startProcessing()

    helper.addStateListener(listener)
    listener.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 0,
      size = 0, duration = 0, scanInProgress = true)))
  }

  it should "correctly update the meta data state during a scan operation" in {
    val helper = new MetaDataManagerActorTestHelper
    val listener1, listener2 = TestProbe()
    helper.startProcessing()

    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID) take 1)
    helper.addStateListener(listener1)
    listener1.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 1,
      size = 100, duration = 10, scanInProgress = true)))

    helper.sendProcessingResults(TestMediumID,
      ScanResult.mediaFiles(TestMediumID).slice(1, 2))
    helper addStateListener listener2
    listener2.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 2,
      size = 300, duration = 30, scanInProgress = true)))
  }

  it should "send messages during a scan operation" in {
    val helper = new MetaDataManagerActorTestHelper
    val listener = helper.newStateListener(expectStateMsg = false)
    helper.startProcessing()
    listener.expectMsgType[MetaDataStateUpdated].state.scanInProgress shouldBe false

    listener.expectMsg(MetaDataScanStarted)
    helper.sendAllProcessingResults(ScanResult).sendAvailableMedia()
    val (completedMedia, updates) = ScanResult.mediaFiles.keys.map{ _ =>
      (listener.expectMsgType[MediumMetaDataCompleted].mediumID,
        listener.expectMsgType[MetaDataStateUpdated])
    }.unzip
    listener.expectMsg(MediumMetaDataCompleted(MediumID.UndefinedMediumID))
    listener.expectMsg(MetaDataScanCompleted)
    completedMedia.toSet should be(ScanResult.mediaFiles.keySet)
    val mediaCounts = updates map(u => u.state.mediaCount)
    val expMediaCounts = 1 to ScanResult.mediaFiles.size
    mediaCounts.toSeq should contain theSameElementsInOrderAs expMediaCounts
  }

  it should "send a message of the undefined medium only if such media occur" in {
    val mid = MediumID("someMedium", Some("someSettings"))
    val files = generateMediaFiles(path("somePath"), 8)
    val sr = MediaScanResult(path("someRoot"), Map(mid -> files))
    val helper = new MetaDataManagerActorTestHelper
    val listener = helper.newStateListener()
    helper.startProcessing(createEnhancedScanResult(sr))

    helper.sendAllProcessingResults(sr).sendAvailableMedia(sr)
    listener.expectMsg(MetaDataScanStarted)
    listener.expectMsg(MediumMetaDataCompleted(mid))
    listener.expectMsgType[MetaDataStateUpdated]
    listener.expectMsg(MetaDataScanCompleted)
  }

  it should "support meta data without a duration" in {
    val helper = new MetaDataManagerActorTestHelper
    val file = ScanResult.mediaFiles(TestMediumID).head
    helper.startProcessing()
    helper.actor receive MetaDataProcessingResult(file.path, TestMediumID, uriFor(file.path),
      metaDataFor(file.path).copy(duration = None))

    val listener = helper.newStateListener(expectStateMsg = false)
    listener.expectMsgType[MetaDataStateUpdated].state.duration should be(0)
  }

  it should "reset statistics when another scan starts" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.sendAllProcessingResults(ScanResult).sendAvailableMedia()
    val listener1 = helper.newStateListener(expectStateMsg = false)
    listener1.expectMsgType[MetaDataStateUpdated].state.scanInProgress shouldBe false

    helper.startProcessing(checkPersistenceMan = false)
    val listener2 = TestProbe()
    helper addStateListener listener2
    listener2.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0,
      songCount = 0, size = 0, duration = 0, scanInProgress = true)))
  }

  it should "send an event if the scan is canceled" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    val listener = helper.newStateListener()
    helper.sendAvailableMedia()
    helper.actor ! CloseRequest

    listener.expectMsg(MetaDataScanCanceled)
    helper.actor ! CloseAck(helper.persistenceManager.ref)
    listener.expectMsg(MetaDataScanCompleted)
  }

  it should "support removing state listeners" in {
    val helper = new MetaDataManagerActorTestHelper
    val listener1 = helper.newStateListener()
    val listener2 = helper.newStateListener()

    helper.actor ! RemoveMetaDataStateListener(listener2.ref)
    helper.actor receive MediaScanStarts
    listener1.expectMsg(MetaDataScanStarted)
    expectNoMoreMessage(listener2)
  }

  /**
   * A test helper class that manages a couple of helper objects needed for
   * more complex tests of a meta data manager actor.
    *
    * @param checkChildActorProps flag whether the properties passed to child
   *                             actors should be checked
   */
  private class MetaDataManagerActorTestHelper(checkChildActorProps: Boolean = true) {
    /**
      * A test probe that simulates the persistence manager actor.
      */
    val persistenceManager = TestProbe()

    /** The configuration. */
    val config = createConfig()

    /** The test actor reference. */
    val actor = createTestActor()

    /** A counter for the number of child actors created by the test actor. */
    private val childActorCounter = new AtomicInteger

    /**
      * A queue that tracks the child actors created by the test actor. It can
      * be used to access the corresponding test probes and check whether the
      * expected messages have been sent to child actors.
      */
    private val childActorQueue = new LinkedBlockingQueue[TestProbe]

    /**
      * Convenience function for sending a message to the test actor that starts
      * processing.
      *
      * @param esr                 the enhanced scan result to be sent
      * @param checkPersistenceMan flag whether the persistence manager actor
      *                            is to be checked
      * @return the test actor
      */
    def startProcessing(esr: EnhancedMediaScanResult = EnhancedScanResult,
                        checkPersistenceMan: Boolean = true): ActorRef = {
      actor ! MediaScanStarts
      if (checkPersistenceMan) {
        persistenceManager.expectMsg(ScanForMetaDataFiles)
      }
      actor ! esr
      actor
    }

    /**
     * Sends a request for meta data to the test actor.
      *
      * @param mediumID the medium ID
     * @param registerAsListener the register as listener flag
     */
    def queryMetaData(mediumID: MediumID, registerAsListener: Boolean): Unit = {
      actor ! GetMetaData(mediumID, registerAsListener)
    }

    /**
     * Sends a request for meta data for the test actor and expects a response.
      *
      * @param mediumID the medium ID
     * @param registerAsListener the register as listener flag
     * @return the chunk message received from the test actor
     */
    def queryAndExpectMetaData(mediumID: MediumID, registerAsListener: Boolean):
    MetaDataChunk = {
      queryMetaData(mediumID, registerAsListener)
      expectMsgType[MetaDataChunk]
    }

    /**
     * Sends processing result objects for the given files to the test actor.
      *
      * @param mediumID the medium ID
     * @param files the list of files
     */
    def sendProcessingResults(mediumID: MediumID, files: List[FileData]): Unit = {
      files foreach { m =>
        actor receive MetaDataProcessingResult(m.path, mediumID, uriFor(m.path),
          metaDataFor(m.path))
      }
    }

    /**
      * Sends complete processing results for all files of the provided scan
      * result.
      *
      * @param result the scan result
      * @return this test helper
      */
    def sendAllProcessingResults(result: MediaScanResult): MetaDataManagerActorTestHelper = {
      result.mediaFiles foreach { e => sendProcessingResults(e._1, e._2) }
      this
    }

    /**
      * Returns the number of child actors created by the test actor.
      * @return the number of child actors
      */
    def numberOfChildActors: Int = childActorCounter.get()

    /**
      * Returns the next child actor that has been created or fails if no
      * child creation took place.
      *
      * @return the probe for the next child actor
      */
    def nextChild(): TestProbe = {
      awaitCond(!childActorQueue.isEmpty)
      childActorQueue.poll()
    }

    /**
      * Sends an ''AvailableMedia'' message for the given scan result to the
      * test actor.
      *
      * @param sr the scan result
      * @return this test helper
      */
    def sendAvailableMedia(sr: MediaScanResult = ScanResult): MetaDataManagerActorTestHelper = {
      actor ! createAvailableMedia(sr)
      this
    }

    /**
      * Adds a test probe as meta data state listener to the test actor.
      *
      * @param probe the test probe to be registered
      * @return this test helper
      */
    def addStateListener(probe: TestProbe): MetaDataManagerActorTestHelper = {
      actor ! AddMetaDataStateListener(probe.ref)
      this
    }

    /**
      * Creates a test probe for a state listener and registers it at the test
      * actor.
      *
      * @param expectStateMsg a flag whether the state message should be
      *                       expected
      * @return the test probe for the listener
      */
    def newStateListener(expectStateMsg: Boolean = true): TestProbe = {
      val probe = TestProbe()
      addStateListener(probe)
      if (expectStateMsg) {
        probe.expectMsgType[MetaDataStateUpdated]
      }
      probe
    }

    /**
     * Creates the standard test actor.
      *
      * @return the test actor
     */
    private def createTestActor(): TestActorRef[MetaDataManagerActor] =
    TestActorRef(creationProps())

    private def creationProps(): Props =
      Props(new MetaDataManagerActor(config, persistenceManager.ref) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          childActorCounter.incrementAndGet()
          if (checkChildActorProps) {
            val sampleProps = MediumProcessorActor(EnhancedScanResult, config)
            p.actorClass() should be(sampleProps.actorClass())
            p.args should be(sampleProps.args)
          }
          val probe = TestProbe()
          childActorQueue offer probe
          probe.ref
        }
      })

    /**
     * Creates a mock for the central configuration object.
      *
      * @return the mock configuration
     */
    private def createConfig() = {
      val config = mock[MediaArchiveConfig]
      when(config.metaDataUpdateChunkSize).thenReturn(2)
      when(config.metaDataMaxMessageSize).thenReturn(MaxMessageSize)
      config
    }
  }

}
