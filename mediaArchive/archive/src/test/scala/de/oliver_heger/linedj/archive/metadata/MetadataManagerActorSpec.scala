/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.ForwardTestActor
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.*
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetadataManagerActor
import de.oliver_heger.linedj.extract.metadata.{MetadataExtractionActor, ProcessMediaFiles}
import de.oliver_heger.linedj.io.*
import de.oliver_heger.linedj.shared.archive.media.*
import de.oliver_heger.linedj.shared.archive.metadata.*
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.{FishingOutcome, scaladsl}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, Terminated}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Path, Paths}
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.concurrent.duration.*

object MetadataManagerActorSpec:
  /** The root path of the test media archive. */
  private val ArchiveRootPath = Paths get "archiveRoot"

  /** The number of parallel processors for the test root. */
  private val AsyncCount = 3

  /** The test timeout value for media file processing. */
  private val ProcessingTimeout = Timeout(42.seconds)

  /** ID of a test medium. */
  private val TestMediumID = mediumID("medium1")

  /** A list of medium IDs used by the tests. */
  private val MediaIDs = List(TestMediumID, mediumID("otherMedium"),
    mediumID("coolMusic"))

  /** A test scan result object. */
  private val ScanResult = createScanResult()

  /** A test enhanced scan result object. */
  private val EnhancedScanResult = createEnhancedScanResult(ScanResult)

  /** A special test message sent to actors. */
  private val TestMessage = new Object

  /** The converter for paths and URIs used by the tests. */
  private val Converter = new PathUriConverter(ArchiveRootPath)

  /**
    * A data class used to record the creation of child actors.
    *
    * @param probe the test probe to represent the child
    * @param props the creation Props
    */
  private case class ChildCreation(probe: TestProbe, props: Props)

  /**
    * Helper method for generating a path. All paths are sub paths of the test
    * archive's root path.
    *
    * @param s the name of this path
    * @return the path
    */
  private def path(s: String): Path = ArchiveRootPath resolve s

  /**
    * Generates a medium ID.
    *
    * @param name a unique name for the ID
    * @return the medium ID
    */
  private def mediumID(name: String): MediumID =
    val settingsPath = Paths.get(name, "playlist.settings")
    MediumID(name, Some(settingsPath.toString), "someArchiveComponent")

  /**
    * Creates a test metadata object for the specified path.
    *
    * @param path the path
    * @return the metadata for this path (following conventions)
    */
  private def metadataFor(path: Path): MediaMetadata =
    val index = extractPathIndex(path)
    MediaMetadata(
      title = Some(path.getFileName.toString),
      duration = Some(index * 10),
      size = index * 100,
      checksum = "check" + index
    )

  /**
    * Generates a ''MetaDataProcessingResult'' for the specified parameters.
    *
    * @param mediumID the medium ID
    * @param file     the object with file data
    * @return the processing result
    */
  private def processingResultFor(mediumID: MediumID, file: FileData): MetadataProcessingSuccess =
    MetadataProcessingSuccess(mediumID, fileUri(file), metadataFor(file.path))

  /**
    * Generates a number of media files that belong to the specified test
    * medium.
    *
    * @param mediumPath the path of the medium
    * @param count      the number of files to generate
    * @return the resulting list
    */
  private def generateMediaFiles(mediumPath: Path, count: Int): List[FileData] =
    val basePath = mediumPath.getParent
    (1 to count).toList.map { index =>
      FileData(basePath.resolve(s"TestFile_$index.mp3"), 20)
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
  private def extractPathIndex(path: Path): Int =
    val pathStr = path.toString takeWhile (_ != '.')

    @tailrec def loop(index: Int): Int =
      if Character isDigit pathStr(index) then loop(index - 1)
      else pathStr.substring(index + 1).toInt

    loop(pathStr.length - 1)

  /**
    * Creates a test scan result object.
    */
  private def createScanResult(): MediaScanResult =
    val numbersOfSongs = List(3, 8, 4)
    val fileData = MediaIDs zip numbersOfSongs map { e =>
      (e._1, generateMediaFiles(path(e._1.mediumDescriptionPath.get), e._2))
    }
    val fileMap = Map(fileData: _*) +
      (MediumID(ArchiveRootPath.toString, None) -> generateMediaFiles(path("noMedium"), 11))
    MediaScanResult(ArchiveRootPath, fileMap)

  /**
    * Generates the checksum for a medium based on the given medium ID.
    *
    * @param mid the medium ID
    * @return the checksum for this medium
    */
  private def createMediumChecksum(mid: MediumID): MediumChecksum =
    MediumChecksum("checksum_" + mid.mediumURI)

  /**
    * Creates an enhanced scan result. This method adds checksum information.
    *
    * @param result the plain result
    * @return the enhanced result
    */
  private def createEnhancedScanResult(result: MediaScanResult): EnhancedMediaScanResult =
    EnhancedMediaScanResult(result, result.mediaFiles map (e => (e._1, createMediumChecksum(e._1))))

  /**
    * Generates an enhanced scan result that contains information for a single
    * test medium.
    *
    * @param mid the medium
    * @return the scan result for this medium
    */
  def createResultForMedium(mid: MediumID): EnhancedMediaScanResult =
    val files = generateMediaFiles(path(mid.mediumURI), 4)
    val scanRes = MediaScanResult(path(mid.mediumURI), Map(mid -> files))
    createEnhancedScanResult(scanRes)

  /**
    * Generates an ''AvailableMedia'' message for the specified scan result.
    *
    * @param result the scan result
    * @return the corresponding available message
    */
  private def createAvailableMedia(result: MediaScanResult): AvailableMedia =
    def createMediumInfo(mid: MediumID): MediumInfo =
      MediumInfo(mediumID = mid, name = "Medium " + mid.mediumURI,
        description = "", orderMode = "", checksum = "c" + mid.mediumURI)

    val mediaInfo = result.mediaFiles.keys.map { mid =>
      (mid, createMediumInfo(mid))
    }.toMap + (MediumID.UndefinedMediumID -> createMediumInfo(MediumID.UndefinedMediumID))
    AvailableMedia(mediaInfo.toList)

  /**
    * Generates the URI for the given media file.
    *
    * @param file the file
    * @return the URI for this file
    */
  private def fileUri(file: FileData): MediaFileUri =
    val relativePath = ArchiveRootPath.relativize(file.path)
    MediaFileUri(relativePath.toString)

  /**
    * Converts the medium files in the given scan result to their URIs.
    *
    * @param result the ''MediaScanResult''
    * @return a map with medium IDs mapped to the URIs of contained files
    */
  private def convertToFileUris(result: MediaScanResult): Map[MediumID, List[MediaFileUri]] =
    result.mediaFiles.map { e => e._1 -> (e._2 map fileUri) }

  /**
    * Helper method to ensure that no more messages are sent to a test probe.
    * This message sends a special message to the probe and checks whether it is
    * immediately received.
    *
    * @param probe the probe to be checked
    */
  private def expectNoMoreMessage(probe: TestProbe): Unit =
    probe.ref ! TestMessage
    probe.expectMsg(TestMessage)
end MetadataManagerActorSpec

/**
  * Test class for ''MetaDataManagerActor''.
  */
class MetadataManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar:

  import MetadataManagerActorSpec.*

  def this() = this(ActorSystem("MetadataManagerActorSpec"))

  /** The test kit for testing typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit shutdownActorSystem system

  "A MetadataManagerActor" should "create correct properties" in:
    val config = mock[MediaArchiveConfig]
    val persistenceMan = TestProbe()
    val listener = typedTestKit.createTestProbe[MetadataProcessingEvent]()
    val converter = new PathUriConverter(path("somePath"))

    val props = MetadataManagerActor(config, persistenceMan.ref, listener.ref, converter)
    props.args should contain inOrderOnly(config, persistenceMan.ref, listener.ref, converter)
    classOf[MetadataManagerActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CloseSupport].isAssignableFrom(props.actorClass()) shouldBe true

  it should "pass a scan result to the metadata persistence manager actor" in:
    val helper = new MetadataManagerActorTestHelper

    helper.startProcessing()
    helper.persistenceManager.expectMsg(EnhancedScanResult)

  it should "pass a contribution to the metadata listener for a scan result" in:
    val helper = new MetadataManagerActorTestHelper

    helper.startProcessing()
    helper.expectMediumAvailableEvents()

  it should "ignore a scan result before the scan is started" in:
    val helper = new MetadataManagerActorTestHelper

    helper.actor receive EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)
    helper.expectNoMetadataEvent()

  /**
    * Generates an alternative scan result and tells the test actor to process
    * it.
    *
    * @param helper    the test helper
    * @param files     the files to be found in this scan result
    * @param expectAck flag whether an ACK message is expected
    * @return the alternative scan result
    */
  private def processAnotherScanResult(helper: MetadataManagerActorTestHelper,
                                       files: List[FileData],
                                       expectAck: Boolean): EnhancedMediaScanResult =
    val root = path("anotherRootDirectory")
    val medID = MediumID(root.toString, Some("someDescFile.txt"))
    val scanResult2 = MediaScanResult(root, Map(medID -> files))
    val esr = EnhancedMediaScanResult(scanResult2, Map(medID -> createMediumChecksum(medID)))
    helper.actor ! esr
    if expectAck then
      expectAckFromManager()
    helper.sendProcessingResults(medID, files)
    esr

  /**
    * Expects that an ACK message from the metadata manager actor is received.
    */
  private def expectAckFromManager(): Unit =
    expectMsg(MetadataManagerActor.ScanResultProcessed)

  /**
    * Verifies that a child actor has been stopped.
    *
    * @param probe the probe representing the child actor
    */
  private def assertActorStopped(probe: TestProbe): Unit =
    val watchProbe = TestProbe()
    watchProbe watch probe.ref
    watchProbe.expectMsgType[Terminated]

  /**
    * Checks that the given probe receives a [[ProcessMediaFiles]] message with
    * expected content. Since the message contains a conversion function, a
    * direct comparison is not possible.
    *
    * @param probe     the test probe
    * @param expMedium the expected ''MediumID''
    * @param expFiles  the expected list of files
    */
  private def expectProcessMessage(probe: TestProbe, expMedium: MediumID, expFiles: List[FileData]): Unit =
    val message = probe.expectMsgType[ProcessMediaFiles]
    message.mediumID should be(expMedium)
    message.files should be(expFiles)

    message.files foreach { file =>
      message.uriMappingFunc(file.path) should be(Converter.pathToUri(file.path))
    }

  it should "extract metadata from files that could not be resolved" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()

    val unresolved1 = UnresolvedMetadataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head) drop 1, EnhancedScanResult)
    val unresolved2 = UnresolvedMetadataFiles(MediaIDs(1), ScanResult.mediaFiles(MediaIDs(1)),
      EnhancedScanResult)
    helper.actor ! unresolved1
    val processor = helper.nextChild()
    expectProcessMessage(processor, unresolved1.mediumID, unresolved1.files)
    helper.actor ! unresolved2
    expectProcessMessage(processor, unresolved2.mediumID, unresolved2.files)
    helper.numberOfChildActors should be(1)

  it should "create different processor actors for different media roots" in:
    val helper = new MetadataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val files = generateMediaFiles(path("otherPath"), 2)
    val otherResult = processAnotherScanResult(helper, files, expectAck = false)
    expectAckFromManager()
    val unresolved1 = UnresolvedMetadataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head), EnhancedScanResult)
    val unresolved2 = UnresolvedMetadataFiles(otherResult.scanResult.mediaFiles.keys.head, files, otherResult)
    helper.actor ! unresolved1
    helper.nextChild().expectMsgType[ProcessMediaFiles]

    helper.actor ! unresolved2
    val creation = helper.nextChildCreation()
    expectProcessMessage(creation.probe, unresolved2.mediumID, unresolved2.files)
    creation.props.args(2) should be(AsyncCount)
    helper.numberOfChildActors should be(2)

  it should "stop processor actors when a scan is complete" in:
    val helper = new MetadataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val files = generateMediaFiles(path("otherPath"), 2)
    val otherResult = processAnotherScanResult(helper, files, expectAck = false)
    val unresolved1 = UnresolvedMetadataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head), EnhancedScanResult)
    val unresolved2 = UnresolvedMetadataFiles(otherResult.scanResult.mediaFiles.keys.head,
      files, otherResult)
    helper.actor ! unresolved1
    helper.actor ! unresolved2
    helper.sendAllProcessingResults(ScanResult)
    helper.sendAvailableMedia()

    assertActorStopped(helper.nextChild())
    assertActorStopped(helper.nextChild())
    expectAckFromManager()

  it should "update processed media, so that ACK messages are sent correctly" in:
    val helper = new MetadataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    helper.sendAllProcessingResults(ScanResult)

    val files = generateMediaFiles(path("otherPath"), 1)
    processAnotherScanResult(helper, files, expectAck = true)

  it should "notify the persistence manager actor when a scan is complete" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.sendAvailableMedia()
      .sendAllProcessingResults(ScanResult)

    helper.persistenceManager.fishForMessage():
      case PersistentMetadataManagerActor.ScanCompleted =>
        true
      case _ => // ignore all other messages
        false
    expectNoMoreMessage(helper.persistenceManager)

  it should "notify the metadata listener when a scan is complete" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.sendAvailableMedia()
      .sendAllProcessingResults(ScanResult)

    helper.metadataListener.fishForMessage(3.seconds):
      case MetadataProcessingEvent.UpdateOperationCompleted(proc) if proc == helper.actor =>
        FishingOutcome.Complete
      case _ => FishingOutcome.ContinueAndIgnore
    helper.expectNoMetadataEvent()

  it should "restart processor actors for a new scan" in:
    val unresolved = UnresolvedMetadataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head), EnhancedScanResult)
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.actor ! unresolved
    helper.sendAvailableMedia()
      .sendAllProcessingResults(ScanResult)

    helper.startProcessing(checkPersistenceMan = false)
    helper.actor ! unresolved
    awaitCond(helper.numberOfChildActors == 2)

  it should "reset the scanInProgress flag if all data is available" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendAllProcessingResults(ScanResult)
    helper.sendAvailableMedia()

    helper.actor receive EnhancedScanResult
    helper.expectCompleteNotifications()
    expectNoMoreMessage(helper.persistenceManager)

  it should "not reset the scanInProgress flag before all processing results arrived" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendAvailableMedia().
      sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))

    helper.actor ! EnhancedScanResult
    expectAckFromManager()
    helper.persistenceManager.expectMsg(EnhancedScanResult)

  it should "propagate processing results to the metadata listener actor" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.expectMediumAvailableEvents()
      .sendAvailableMedia()
      .sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))

    helper.expectProcessingResultEvents(TestMediumID, ScanResult.mediaFiles(TestMediumID))

  it should "handle a processing error result" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.expectMediumAvailableEvents()
      .sendAvailableMedia()
    val mediaData = EnhancedScanResult.scanResult.mediaFiles.toList
    mediaData.tail.foreach(t => helper.sendProcessingResults(t._1, t._2))

    val errData = mediaData.head
    val filesOk = errData._2.tail
    helper.sendProcessingResults(errData._1, filesOk)
    val errResult = processingResultFor(errData._1, errData._2.head)
      .toError(new Exception("Failed processing!"))
    helper.actor receive errResult
    helper.actor receive EnhancedScanResult
    helper.expectCompleteNotifications()
    expectNoMoreMessage(helper.persistenceManager)

  it should "handle a Cancel request in the middle of processing" in:
    val helper = new MetadataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    val files = generateMediaFiles(path("otherPath"), 2)
    val otherResult = processAnotherScanResult(helper, files, expectAck = false)
    expectAckFromManager()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.actor ! UnresolvedMetadataFiles(MediaIDs.head,
      ScanResult.mediaFiles(MediaIDs.head), EnhancedScanResult)
    val processor1 = helper.nextChild()
    processor1.expectMsgType[ProcessMediaFiles]
    helper.actor ! UnresolvedMetadataFiles(otherResult.scanResult.mediaFiles.keys.head, files,
      otherResult)
    val processor2 = helper.nextChild()
    processor2.expectMsgType[ProcessMediaFiles]
    helper.sendAvailableMedia()
      .prepareCloseRequest(closing = true)(processor1.ref, processor2.ref)

    helper.actor receive CloseRequest
    helper.numberOfCloseRequests should be(1)

    helper.actor receive EnhancedScanResult
    expectNoMoreMessage(helper.persistenceManager)

  it should "not complete a Cancel request before all media have been received" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.prepareCloseRequest(avMediaPresent = false)()
    helper.actor receive CloseRequest

    helper.numberOfCloseRequests should be(1)

  it should "notify the condition as satisfied when available media are received" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.prepareCloseRequest(avMediaPresent = false)()

    helper.actor ! CloseRequest
    helper.sendAvailableMedia()
    awaitCond(helper.numberOfSatisfiedConditions == 1)

  it should "reset the cancel request after the scan has been aborted" in:
    val helper = new MetadataManagerActorTestHelper

    helper.actor receive CloseHandlerActor.CloseComplete
    helper.numberOfCloseCompleted should be(1)

  it should "ignore processing results while a Cancel request is pending" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    val results = ScanResult.mediaFiles(TestMediumID) take 2
    helper.expectMediumAvailableEvents()
      .sendProcessingResults(TestMediumID, results)
      .expectProcessingResultEvents(TestMediumID, results)
      .prepareCloseRequest(closing = true)()

    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID) drop 2)
      .expectNoMetadataEvent()

  it should "handle a Cancel request if no scan is in progress" in:
    val helper = new MetadataManagerActorTestHelper

    helper.actor ! CloseRequest
    expectMsg(CloseAck(helper.actor))

  it should "send pending ACK messages if a Cancel request is received" in:
    val mid1 = MediumID("mediumRoot1", Some("medium1.settings"))
    val mid2 = MediumID("mediumRoot2", Some("medium2.settings"))
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()

    helper.actor.tell(createResultForMedium(mid1), probe1.ref)
    helper.actor.tell(createResultForMedium(mid2), probe2.ref)
    helper.prepareCloseRequest(avMediaPresent = false)()
    helper.actor ! CloseRequest
    probe1.expectMsg(MetadataManagerActor.ScanResultProcessed)
    probe2.expectMsg(MetadataManagerActor.ScanResultProcessed)

  it should "reset information related to ACK messages when a scan is canceled" in:
    val mid = MediumID("mediumRoot", Some("medium.settings"))
    val probe = TestProbe()
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.sendAvailableMedia()

    helper.actor.tell(createResultForMedium(mid), probe.ref)
    helper.actor ! CloseRequest
    helper.actor ! CloseHandlerActor.CloseComplete
    probe.expectMsg(MetadataManagerActor.ScanResultProcessed)
    helper.startProcessing(checkPersistenceMan = false)
    helper.sendAllProcessingResults(ScanResult)
    expectNoMoreMessage(probe)

  it should "ignore a scan start message if a scan is in progress" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))

    helper.actor receive MediaScanStarts(TestProbe().ref)
    expectNoMoreMessage(helper.persistenceManager)

  it should "not generate a metadata event for a processing result for an unknown medium" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()
    val mid = MediumID("unknown medium", Some("unknown path"))

    helper.expectMediumAvailableEvents()
      .sendProcessingResults(mid, ScanResult.mediaFiles(TestMediumID))
      .expectNoMetadataEvent()

  it should "not generate a metadata event for a processing result for an unknown URI" in:
    val helper = new MetadataManagerActorTestHelper
    helper.startProcessing()

    helper.expectMediumAvailableEvents()
      .sendProcessingResults(TestMediumID, List(FileData(path("unknownPath_42"), 42)))
      .expectNoMetadataEvent()

  it should "forward a GetMetaDataFileInfo message to the persistence manager" in:
    val helper = new MetadataManagerActorTestHelper(
      optPersistenceManager = Some(ForwardTestActor()))

    helper.actor ! GetMetadataFileInfo
    expectMsg(ForwardTestActor.ForwardedMessage(PersistentMetadataManagerActor.FetchMetadataFileInfo(helper.actor)))

  it should "forward a RemovePersistentMetaData message to the persistence manager" in:
    val helper = new MetadataManagerActorTestHelper(
      optPersistenceManager = Some(ForwardTestActor()))
    val removeMsg = RemovePersistentMetadata(Set("someChecksum"))

    helper.actor ! removeMsg
    expectMsg(ForwardTestActor.ForwardedMessage(removeMsg))

  it should "reject a RemovePersistenceMetaData message while a scan is in progress" in:
    val helper = new MetadataManagerActorTestHelper
    val removeMsg = RemovePersistentMetadata(Set("ignored"))

    helper.startProcessing() ! removeMsg
    helper.persistenceManager.expectMsgType[EnhancedMediaScanResult]
    expectMsg(RemovePersistentMetadataResult(removeMsg, Set.empty))
    expectNoMoreMessage(helper.persistenceManager)

  it should "ACK a MediaScanStarts message" in:
    val helper = new MetadataManagerActorTestHelper

    helper.actor ! MediaScanStarts(TestProbe().ref)
    expectAckFromManager()

  it should "ACK a result if no scan is in progress" in:
    val helper = new MetadataManagerActorTestHelper

    helper.actor ! EnhancedScanResult
    expectAckFromManager()

  it should "ACK a result if closing is in progress" in:
    val helper = new MetadataManagerActorTestHelper

    helper.startProcessing()
    helper.prepareCloseRequest(avMediaPresent = false, closing = true)()
    helper.actor ! EnhancedScanResult
    expectAckFromManager()

  /**
    * A test helper class that manages a couple of helper objects needed for
    * more complex tests of a metadata manager actor.
    *
    * @param checkChildActorProps  flag whether the properties passed to child
    *                              actors should be checked
    * @param optPersistenceManager an option for a special persistence manager
    *                              actor; this overrides the test probe passed
    *                              per default
    */
  private class MetadataManagerActorTestHelper(checkChildActorProps: Boolean = true,
                                               optPersistenceManager: Option[ActorRef] = None):
    /**
      * A test probe that simulates the persistence manager actor.
      */
    val persistenceManager: TestProbe = TestProbe()

    /** Test probe for the metadata listener actor. */
    val metadataListener: scaladsl.TestProbe[MetadataProcessingEvent] = 
      typedTestKit.createTestProbe[MetadataProcessingEvent]()

    /** The configuration. */
    private val config: MediaArchiveConfig = createConfig()

    /** The test actor reference. */
    val actor: TestActorRef[MetadataManagerActor] = createTestActor()

    /** A counter for the number of child actors created by the test actor. */
    private val childActorCounter = new AtomicInteger

    /** A counter for close requests that have been triggered. */
    private val closeRequestCounter = new AtomicInteger

    /** A counter for completed close requests. */
    private val closeCompleteCounter = new AtomicInteger

    /** A counter for satisfied condition notifications. */
    private val conditionSatisfiedCounter = new AtomicInteger

    /**
      * A queue that tracks the child actors created by the test actor. It can
      * be used to access the corresponding test probes and check whether the
      * expected messages have been sent to child actors.
      */
    private val childActorQueue = new LinkedBlockingQueue[ChildCreation]

    /**
      * Stores the currently active processor actors. This is used to determine
      * actors to be closed by the CloseSupport implementation.
      */
    private var activeProcessors = Iterable.empty[ActorRef]

    /** Test probe for the client to be notified at the end of the operation. */
    private val scanClient = TestProbe()

    /**
      * Flag whether available media are present. This is needed to check the
      * condition flag passed to the CloseSupport implementation.
      */
    private var availableMediaArrived = true

    /**
      * Flag whether currently a close operation is in progress. This flag is
      * used by the CloseSupport implementation.
      */
    private var closeInProgress = false

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
                        checkPersistenceMan: Boolean = true): ActorRef =
      actor ! MediaScanStarts(scanClient.ref)
      expectAckFromManager()
      if checkPersistenceMan then
        persistenceManager.expectMsg(ScanForMetadataFiles)
      actor ! esr
      expectAckFromManager()
      actor

    /**
      * Sends processing result objects for the given files to the test actor.
      *
      * @param mediumID the medium ID
      * @param files    the list of files
      * @return this test helper
      */
    def sendProcessingResults(mediumID: MediumID, files: List[FileData]):
    MetadataManagerActorTestHelper =
      files foreach { m =>
        actor receive processingResultFor(mediumID, m)
      }
      this

    /**
      * Sends complete processing results for all files of the provided scan
      * result.
      *
      * @param result the scan result
      * @return this test helper
      */
    def sendAllProcessingResults(result: MediaScanResult): MetadataManagerActorTestHelper =
      result.mediaFiles foreach { e => sendProcessingResults(e._1, e._2) }
      this

    /**
      * Expects that medium available events were sent to the metadata listener
      * actor (including the processing start event).
      *
      * @return this test helper
      */
    def expectMediumAvailableEvents(): MetadataManagerActorTestHelper = {
      metadataListener.expectMessage(MetadataProcessingEvent.UpdateOperationStarts(actor))

      val files = convertToFileUris(EnhancedScanResult.scanResult)
      val expectedEvents = files.map { e =>
        MetadataProcessingEvent.MediumAvailable(
          mediumID = e._1,
          checksum = createMediumChecksum(e._1),
          files = e._2
        )
      }
      val events = (1 to expectedEvents.size).map { _ =>
        metadataListener.expectMessageType[MetadataProcessingEvent.MediumAvailable]
      }
      events should contain theSameElementsAs expectedEvents
      this
    }

    /**
      * Checks that no event was received to the metadata listener actor.
      *
      * @return this test helper
      */
    def expectNoMetadataEvent(): MetadataManagerActorTestHelper =
      metadataListener.expectNoMessage(250.millis)
      this

    /**
      * Expects that metadata processing results for the specified files have
      * been sent to the metadata event listener actor.
      *
      * @param mediumID the medium ID
      * @param files    the list of files
      * @return this test helper
      */
    def expectProcessingResultEvents(mediumID: MediumID, files: List[FileData]): MetadataManagerActorTestHelper =
      files.foreach { f =>
        val expectedEvent = MetadataProcessingEvent.ProcessingResultAvailable(
          checksum = createMediumChecksum(mediumID),
          result = processingResultFor(mediumID, f)
        )
        metadataListener.expectMessage(expectedEvent)
      }
      this

    /**
      * Returns the number of child actors created by the test actor.
      *
      * @return the number of child actors
      */
    def numberOfChildActors: Int = childActorCounter.get()

    /**
      * Returns the number of close requests that have been handled.
      *
      * @return the number of close requests
      */
    def numberOfCloseRequests: Int = closeRequestCounter.get()

    /**
      * Returns the number of close requests that have been completed.
      *
      * @return the number of complete close requests
      */
    def numberOfCloseCompleted: Int = closeCompleteCounter.get()

    /**
      * Returns the number of invocations of satisfied conditions.
      *
      * @return the satisfied conditions count
      */
    def numberOfSatisfiedConditions: Int = conditionSatisfiedCounter.get()

    /**
      * Returns information about the next child actor that has been created
      * or fails if no child creation took place.
      *
      * @return data about the next child actor
      */
    def nextChildCreation(): ChildCreation =
      awaitCond(!childActorQueue.isEmpty)
      childActorQueue.poll()

    /**
      * Returns the next child actor that has been created or fails if no
      * child creation took place.
      *
      * @return the probe for the next child actor
      */
    def nextChild(): TestProbe = nextChildCreation().probe

    /**
      * Sends an ''AvailableMedia'' message for the given scan result to the
      * test actor.
      *
      * @param sr the scan result
      * @return this test helper
      */
    def sendAvailableMedia(sr: MediaScanResult = ScanResult): MetadataManagerActorTestHelper =
      actor ! createAvailableMedia(sr)
      this

    /**
      * Returns a reference to the persistence manager actor used by the test
      * actor instance. This is either the actor reference passed to the
      * constructor or a reference from a test probe.
      *
      * @return the persistence manager actor reference
      */
    def persistenceManagerActorRef: ActorRef =
      optPersistenceManager getOrElse persistenceManager.ref

    /**
      * Checks that scan complete notifications have been sent to the
      * interested parties.
      *
      * @return this test helper
      */
    def expectCompleteNotifications(): MetadataManagerActorTestHelper =
      persistenceManager.expectMsg(PersistentMetadataManagerActor.ScanCompleted)
      scanClient.expectMsg(MediaScanCompleted)
      this

    /**
      * Initializes flags required by tests of the close/cancel handling.
      *
      * @param avMediaPresent flag whether available media are set
      * @param closing        flag whether a closing operation is in progress
      * @param processors     the currently active processor actors
      * @return this test helper
      */
    def prepareCloseRequest(avMediaPresent: Boolean = true, closing: Boolean = false)
                           (processors: ActorRef*): MetadataManagerActorTestHelper =
      availableMediaArrived = avMediaPresent
      closeInProgress = closing
      activeProcessors = processors
      this

    /**
      * Creates the standard test actor.
      *
      * @return the test actor
      */
    private def createTestActor(): TestActorRef[MetadataManagerActor] =
      TestActorRef(creationProps())

    private def creationProps(): Props =
      Props(
        new MetadataManagerActor(
          config,
          persistenceManagerActorRef,
          metadataListener.ref,
          Converter
        )
        with ChildActorFactory with CloseSupport {
        override def createChildActor(p: Props): ActorRef = {
          childActorCounter.incrementAndGet()
          if checkChildActorProps then {
            val sampleProps = MetadataExtractionActor(actor, null, 0, null)
            p.actorClass() should be(sampleProps.actorClass())
            p.args.head should be(actor)
            p.args(1) shouldBe a[ExtractorActorFactoryImpl]
            p.args(1).asInstanceOf[ExtractorActorFactoryImpl].config should be(config)
            p.args(2) should be(AsyncCount)
            p.args(3) should be(ProcessingTimeout)
          }
          val probe = TestProbe()
          childActorQueue offer ChildCreation(probe, p)
          probe.ref
        }

        /**
          * @inheritdoc Returns the value of the flag set for the test class.
          */
        override def isCloseRequestInProgress: Boolean = closeInProgress

        /**
          * Checks parameters and records this invocation.
          */
        override def onCloseRequest(subject: ActorRef, deps: => Iterable[ActorRef], target:
        ActorRef, factory: ChildActorFactory, conditionState: => Boolean): Boolean = {
          subject should be(actor)
          target should be(sender())
          factory should be(this)
          conditionState shouldBe availableMediaArrived
          val allDeps = persistenceManagerActorRef :: activeProcessors.toList
          deps should contain theSameElementsAs allDeps
          closeRequestCounter.incrementAndGet() < 2
        }

        /**
          * Records this invocation.
          */
        override def onCloseComplete(): Unit = closeCompleteCounter.incrementAndGet()

        /**
          * Records this invocation.
          */
        override def onConditionSatisfied(): Unit = conditionSatisfiedCounter.incrementAndGet()
      })

    /**
      * Creates a mock for the central configuration object.
      *
      * @return the mock configuration
      */
    private def createConfig(): MediaArchiveConfig =
      val config = mock[MediaArchiveConfig]
      when(config.rootPath).thenReturn(EnhancedScanResult.scanResult.root)
      when(config.processorCount).thenReturn(AsyncCount)
      when(config.processingTimeout).thenReturn(ProcessingTimeout)
      when(config.metadataMediaBufferSize).thenReturn(MediaIDs.size + 1)
      config

