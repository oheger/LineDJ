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

package de.oliver_heger.linedj.archiveunion

import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.shared.archive.union._
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.immutable.IndexedSeq

object MetaDataUnionActorSpec:
  /** The maximum message size. */
  private val MaxMessageSize = 24

  /** The sizes of chunk for sending updates to listeners. */
  private val UpdateChunkSize = 2

  /** Constant for a default archive component ID. */
  private val ArchiveCompID = "testArchiveComponent"

  /** Constant for an alternative archive component ID. */
  private val OtherArchiveCompID = "otherArchiveComponent"

  /** ID of a test medium. */
  private val TestMediumID = mediumID("medium1")

  /** A test registration ID. */
  private val TestRegistrationID = 27

  /** A list of medium IDs used by the tests. */
  private val MediaIDs = List(TestMediumID, mediumID("otherMedium"),
    mediumID("coolMusic"))

  /** The root path for the files of the test contribution. */
  private val RootPath = path("Root")

  /** An undefined medium ID in the test contribution. */
  private val UndefinedMediumID = MediumID(RootPath.toString, None)

  /** A special test message sent to actors. */
  private val TestMessage = new Object

  /** Constant for a default media contribution. */
  private val Contribution = createContribution()

  /**
    * Constant for a fishing function which does no processing of messages.
    * This is used by ''findScanCompletedEvent()'' if the original behavior
    * does not have to be modified.
    */
  private val NoFishing: PartialFunction[Any, Boolean] =
    new PartialFunction[Any, Boolean]:
      override def isDefinedAt(x: Any): Boolean = false

      override def apply(v1: Any): Boolean = false

  /**
    * Constant for a fishing function for finding the scan completed event.
    */
  private val FishForScanComplete: PartialFunction[Any, Boolean] =
    case MetaDataScanStarted => false
    case _: MetaDataStateUpdated => false
    case _: MediumMetaDataCompleted => false
    case MetaDataScanCompleted => true

  /**
    * Helper method for generating a path.
    *
    * @param s the name of this path
    * @return the path
    */
  private def path(s: String): Path = Paths get s

  /**
    * Generates a medium ID.
    *
    * @param name a unique name for the ID
    * @return the medium ID
    */
  private def mediumID(name: String, componentID: String = ArchiveCompID): MediumID =
    val settingsPath = Paths.get(name, "playlist.settings")
    MediumID(name, Some(settingsPath.toString), componentID)

  /**
    * Extracts an index from the given URI. All test URIs produced by
    * ''generateMediaFiles()'' end on a numeric index (before the file
    * extension). This function extracts this index. (Note: it is not very
    * robust for other URIs.)
    *
    * @param uri the URI
    * @return the index of this path
    */
  private def extractUriIndex(uri: MediaFileUri): Int =
    val uriStr = uri.uri takeWhile (_ != '.')

    @tailrec def loop(index: Int): Int =
      if Character isDigit uriStr(index) then loop(index - 1)
      else uriStr.substring(index + 1).toInt

    loop(uriStr.length - 1)

  /**
    * Creates a test meta data object for the specified URI.
    *
    * @param uri the URI
    * @return the meta data for this path (following conventions)
    */
  private def metaDataFor(uri: MediaFileUri): MediaMetaData =
    val index = extractUriIndex(uri)
    MediaMetaData(title = Some(UriHelper.extractName(uri.uri)),
      duration = Some(index * 10),
      size = index * 100)

  /**
    * Generates a meta data processing result for the specified parameters.
    *
    * @param mediumID the medium ID
    * @param uri      the URI of the media file
    * @return a successful processing result for this file
    */
  private def processingResult(mediumID: MediumID, uri: MediaFileUri): MetaDataProcessingSuccess =
    MetaDataProcessingSuccess(mediumID, uri, metaDataFor(uri))

  /**
    * Creates a test media contribution object.
    *
    * @return the contribution object
    */
  private def createContribution(): MediaContribution =
    val numbersOfSongs = List(3, 8, 4)
    val fileData = MediaIDs zip numbersOfSongs map { e =>
      (e._1, generateMediaFiles(path(e._1.mediumDescriptionPath.get), e._2))
    }
    val fileMap = Map(fileData: _*) + (UndefinedMediumID -> generateMediaFiles(path("noMedium"), 11))
    MediaContribution(fileMap)

  /**
    * Creates a test contribution with media from another archive component.
    *
    * @return
    */
  private def createContributionFromOtherComponent(): MediaContribution =
    val mid1 = MediumID("someURI", Some("desc1"), OtherArchiveCompID)
    val mid2 = MediumID("otherURI", Some("desc2"), OtherArchiveCompID)
    val mid3 = MediumID("oneMoreURI", None, OtherArchiveCompID)
    val data = Map(mid1 -> generateMediaFiles(path(mid1.mediumURI), 4),
      mid2 -> generateMediaFiles(path(mid2.mediumURI), 8),
      mid3 -> generateMediaFiles(path(mid3.mediumURI), 16))
    MediaContribution(data)

  /**
    * Generates a number of URIs for media files that belong to the specified
    * test medium.
    *
    * @param mediumPath the path of the medium
    * @param count      the number of files to generate
    * @return the resulting list
    */
  private def generateMediaFiles(mediumPath: Path, count: Int): List[MediaFileUri] =
    val basePath = Option(mediumPath.getParent) getOrElse mediumPath
    (1 to count).map { index =>
      MediaFileUri(basePath.resolve(s"TestFile_$index.mp3").toString)
    }.toList

  /**
    * Extracts the first ''MediumID'' found in the specified contribution.
    *
    * @param contribution the contribution
    * @return the extracted medium ID
    */
  private def extractMediumID(contribution: MediaContribution): MediumID =
    contribution.files.keys.head

  /**
    * Finds all undefined media in the specified contribution and returns a
    * collection with all URIs of their files.
    *
    * @param contrib the contribution
    * @return all URIs of matched files as they appear in the global undefined
    *         list
    */
  private def undefinedMediumUris(contrib: MediaContribution): Iterable[MediaFileUri] =
    val undef = contrib.files.filter(e => e._1.mediumDescriptionPath.isEmpty)
    undef.toList.flatMap { e => e._2 }

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

  /**
    * Fishes for the scan completed event on the specified test probe. If the
    * probe is registered as meta data state listener, the end of a scan
    * operation can be determined. A custom fishing function can be provided to
    * react on specific events.
    *
    * @param probe the listener probe
    * @param f     a custom fishing function
    */
  private def findScanCompletedEvent(probe: TestProbe)
                                    (f: PartialFunction[Any, Boolean] = NoFishing): Unit =
    probe.fishForMessage()(f orElse FishForScanComplete)

/**
  * Test class for ''MetaDataUnionActor''.
  */
class MetaDataUnionActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:

  import MetaDataUnionActorSpec._

  def this() = this(ActorSystem("MetaDataUnionActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Checks whether a meta data chunk received from the test actor contains the
    * expected data for the given medium ID and verifies the URIs in the chunk.
    *
    * @param msg          the chunk message to be checked
    * @param mediumID     the medium ID as string
    * @param expectedUris the URIs of the expected files
    * @param expComplete  the expected complete flag
    */
  private def checkMetaDataChunk(msg: MetaDataChunk, mediumID: MediumID,
                                 expectedUris: Iterable[MediaFileUri], expComplete: Boolean): Unit =
    msg.mediumID should be(mediumID)
    msg.data should have size expectedUris.size
    expectedUris foreach { uri =>
      msg.data(uri.uri) should be(metaDataFor(uri))
    }
    msg.complete shouldBe expComplete

  /**
    * Generates an alternative contribution with a single medium and the
    * specified files.
    *
    * @param files the list of files for the contribution
    * @return the contribution
    */
  private def createOtherContribution(files: Iterable[MediaFileUri]): MediaContribution =
    val root = path("anotherRootDirectory")
    val medID = MediumID(root.toString, Some("someDescFile.txt"), OtherArchiveCompID)
    MediaContribution(Map(medID -> files))

  /**
    * Generates an alternative contribution with a single medium of the
    * specified content and tells the test actor to process it.
    *
    * @param helper the test helper
    * @param files  the files to be found in this contribution
    * @return the alternative contribution
    */
  private def processAnotherContribution(helper: MetaDataUnionActorTestHelper,
                                         files: Iterable[MediaFileUri]): MediaContribution =
    val contribution: MediaContribution = createOtherContribution(files)
    helper.processContribution(contribution)
    contribution

  "A MetaDataUnionActor" should "send an answer for an unknown medium ID" in:
    val helper = new MetaDataUnionActorTestHelper
    val mediumID = MediumID("unknown medium ID", None)

    helper.queryAndExpectUnknownMedium(mediumID)

  it should "send an unknown answer for the undefined medium if it does not exist" in:
    val helper = new MetaDataUnionActorTestHelper

    helper.queryAndExpectUnknownMedium(MediumID.UndefinedMediumID)

  it should "allow querying a complete medium" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
      .sendProcessingResults(TestMediumID, Contribution.files(TestMediumID))
    val msg = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false)
    checkMetaDataChunk(msg, TestMediumID, Contribution.files(TestMediumID), expComplete = true)

  it should "return a partial result for a medium when processing is not yet complete" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()

    val msg = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false)
    msg.mediumID should be(TestMediumID)
    msg.data shouldBe empty
    msg.complete shouldBe false

  it should "notify medium listeners when new results become available" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()

    val msgEmpty = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = true)
    msgEmpty.data shouldBe empty
    msgEmpty.complete shouldBe false

    val filesForChunk1 = Contribution.files(TestMediumID).take(2)
    helper.sendProcessingResults(TestMediumID, filesForChunk1)
    checkMetaDataChunk(helper.expectMetaDataResponse(), TestMediumID, filesForChunk1, expComplete = false)

    val filesForChunk2 = List(Contribution.files(TestMediumID).last)
    helper.sendProcessingResults(TestMediumID, filesForChunk2)
    checkMetaDataChunk(helper.expectMetaDataResponse(), TestMediumID, filesForChunk2, expComplete = true)

  it should "handle queries for unknown media while a scan is in progress with a listener registration" in:
    val contribution2 = createContributionFromOtherComponent()
    val testID = contribution2.files.keys.iterator.next()
    val expChunkCount = contribution2.files(testID).size / UpdateChunkSize
    val helper = new MetaDataUnionActorTestHelper

    val chunks = helper.sendContribution()
      .queryMetaData(testID, registerAsListener = true)
      .sendContribution(contribution2)
      .sendAllProcessingResults(Contribution)
      .sendAllProcessingResults(contribution2)
      .expectMetaDataResponsesFor(TestRegistrationID, List.fill(expChunkCount)(testID): _*)
    chunks.checkCompletedFlags()
    checkMetaDataChunk(chunks.mergeChunks(testID), testID, contribution2.files(testID), expComplete = true)

  it should "handle queries for unknown media while a scan is in progress without a listener registration" in:
    val testID = MediumID("unknownMedium", Some("somePath"), "someComponent")
    val helper = new MetaDataUnionActorTestHelper

    helper.sendContribution()
      .queryMetaData(testID, registerAsListener = false)
      .expectUnknownMedium(testID)

  it should "allow querying files not assigned to a medium" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val files = Contribution.files(UndefinedMediumID)
    helper.sendProcessingResults(UndefinedMediumID, files)

    helper.queryMetaData(UndefinedMediumID, registerAsListener = false, 0)
    checkMetaDataChunk(helper.expectMetaDataResponse(0), UndefinedMediumID, files, expComplete = true)

  it should "handle the undefined medium even over multiple contributions" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val filesForChunk1 = Contribution.files(UndefinedMediumID) dropRight 1
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk1)
    helper.actor ! GetMetaData(MediumID.UndefinedMediumID, registerAsListener = true, TestRegistrationID)
    checkMetaDataChunk(helper.expectMetaDataResponse(), UndefinedMediumID, filesForChunk1,
      expComplete = false)
    val filesForChunk2 = List(Contribution.files(UndefinedMediumID).last)
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk2)
    Contribution.files.filterNot(_._1.mediumDescriptionPath.isEmpty)
      .foreach(e => helper.sendProcessingResults(e._1, e._2))
    checkMetaDataChunk(helper.expectMetaDataResponse(), UndefinedMediumID, filesForChunk2,
      expComplete = false)
    helper.expectUndefinedMediumCompletionResponse()

    val filesForChunk3 = generateMediaFiles(path("fileOnOtherMedium"), 4)
    val root2 = path("anotherRootDirectory")
    val UndefinedMediumID2 = MediumID(root2.toString, None)
    val contribution2 = MediaContribution(Map(UndefinedMediumID2 -> filesForChunk3))
    helper.sendContribution(contribution2)
      .sendProcessingResults(UndefinedMediumID2, filesForChunk3)
    helper.actor ! GetMetaData(MediumID.UndefinedMediumID, registerAsListener = false, 0)
    val chunks = helper.expectMetaDataResponsesFor(0, UndefinedMediumID, UndefinedMediumID2)
    checkMetaDataChunk(chunks(UndefinedMediumID), UndefinedMediumID, Contribution.files(UndefinedMediumID),
      chunks.isComplete(UndefinedMediumID))
    checkMetaDataChunk(chunks(UndefinedMediumID2), UndefinedMediumID2, filesForChunk3,
      chunks.isComplete(UndefinedMediumID2))
    chunks.checkCompletedFlags()

  it should "handle a query for the undefined medium while a scan is in progress with a listener registration" in:
    val data = Map(TestMediumID -> generateMediaFiles(path(TestMediumID.mediumURI), 4))
    val contribution = MediaContribution(data)
    val helper = new MetaDataUnionActorTestHelper

    helper.sendContribution(contribution)
      .queryMetaData(MediumID.UndefinedMediumID, registerAsListener = true)
      .processContribution(contribution)
      .expectUndefinedMediumCompletionResponse()

  it should "handle a query for the undefined medium while a scan is in progress without a listener registration" in:
    val data = Map(TestMediumID -> generateMediaFiles(path(TestMediumID.mediumURI), 1))
    val contribution = MediaContribution(data)
    val helper = new MetaDataUnionActorTestHelper

    helper.sendContribution(contribution)
      .queryMetaData(MediumID.UndefinedMediumID, registerAsListener = false)
      .expectUnknownMedium(MediumID.UndefinedMediumID)

  it should "not register a listener for the undefined medium if no scan is in progress" in:
    val contribution2 = createContributionFromOtherComponent()
    val probe = TestProbe()
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution(Contribution)
      .processContribution(Contribution)
    helper.actor.tell(GetMetaData(MediumID.UndefinedMediumID, registerAsListener = true, TestRegistrationID),
      probe.ref)
    probe.expectMsgType[MetaDataResponse]

    helper.sendContribution(contribution2)
      .processContribution(contribution2)
    expectNoMoreMessage(probe)

  it should "split large chunks of meta data into multiple ones" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val files = generateMediaFiles(path("fileOnOtherMedium"), MaxMessageSize + 4)
    val medID = extractMediumID(processAnotherContribution(helper, files))

    helper.queryMetaData(medID, registerAsListener = true)
    checkMetaDataChunk(helper.expectMetaDataResponse(), medID, files take MaxMessageSize, expComplete = false)
    checkMetaDataChunk(helper.expectMetaDataResponse(), medID, files drop MaxMessageSize, expComplete = true)

  it should "allow removing a medium listener" in:
    val helper = new MetaDataUnionActorTestHelper
    val data = helper.sendContribution()
      .queryAndExpectMetaData(TestMediumID, registerAsListener = true).data
    data shouldBe empty

    helper.actor ! RemoveMediumListener(TestMediumID, testActor)
    helper.sendProcessingResults(TestMediumID, Contribution.files(TestMediumID))
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false), TestMediumID, Contribution.files(TestMediumID), expComplete = true)

  it should "ignore an unknown medium when removing a medium listener" in:
    val helper = new MetaDataUnionActorTestHelper

    helper.actor receive RemoveMediumListener(mediumID("someMedium"), testActor)

  it should "ignore a scan start message if a scan is in progress" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
      .sendProcessingResults(TestMediumID, Contribution.files(TestMediumID))

    helper.sendScanStartsMessage()
      .queryAndExpectMetaData(TestMediumID,
        registerAsListener = false).data should not be empty

  it should "remove all medium listeners when the scan is canceled" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val probe = TestProbe()
    helper.actor.tell(GetMetaData(TestMediumID, registerAsListener = true, 0), probe.ref)
    probe.expectMsgType[MetaDataResponse]
    helper.sendCloseRequest()

    helper.processContribution(Contribution)
    expectNoMoreMessage(probe)

  it should "pass the current meta data state to a newly registered state listener" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener(expectStateMsg = false)

    listener.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 0,
      size = 0, duration = 0, scanInProgress = false, updateInProgress = false, archiveCompIDs = Set.empty)))

  it should "correctly update the scan in progress state when a scan starts" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = TestProbe()
    helper.sendContribution()

    helper.addStateListener(listener)
    listener.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 0,
      size = 0, duration = 0, scanInProgress = true, updateInProgress = false, archiveCompIDs = Set.empty)))

  it should "correctly update the meta data state during a scan operation" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener1, listener2 = TestProbe()
    helper.sendContribution()
      .sendProcessingResults(TestMediumID, Contribution.files(TestMediumID) take 1)
      .addStateListener(listener1)
    listener1.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 1,
      size = 100, duration = 10, scanInProgress = true, updateInProgress = false,
      archiveCompIDs = Set(ArchiveCompID))))

    helper.sendProcessingResults(TestMediumID, Contribution.files(TestMediumID).slice(1, 2))
    helper addStateListener listener2
    listener2.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 2,
      size = 300, duration = 30, scanInProgress = true, updateInProgress = false,
      archiveCompIDs = Set(ArchiveCompID))))

  it should "correctly update the meta data state for multiple archive components" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = TestProbe()
    helper.processContribution(Contribution)

    processAnotherContribution(helper, generateMediaFiles(Paths get "foo", 4))
    helper.addStateListener(listener)
    val stateUpdate = listener.expectMsgType[MetaDataStateUpdated]
    stateUpdate.state.archiveCompIDs should contain allOf(ArchiveCompID, OtherArchiveCompID)

  it should "send messages during a scan operation" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener(expectStateMsg = false)
    helper.sendContribution()
    listener.expectMsgType[MetaDataStateUpdated].state.scanInProgress shouldBe false

    listener.expectMsg(MetaDataScanStarted)
    helper.sendAllProcessingResults(Contribution)
    val (completedMedia, updates) = Contribution.files.keys.map { _ =>
      (listener.expectMsgType[MediumMetaDataCompleted].mediumID,
        listener.expectMsgType[MetaDataStateUpdated])
    }.unzip
    listener.expectMsg(MediumMetaDataCompleted(MediumID.UndefinedMediumID))
    val lastUpdate = listener.expectMsgType[MetaDataStateUpdated]
    listener.expectMsg(MetaDataScanCompleted)
    completedMedia.toSet should be(Contribution.files.keySet)
    val mediaCounts = updates map (u => u.state.mediaCount)
    val expMediaCounts = 1 to Contribution.files.size
    mediaCounts.toSeq should contain theSameElementsInOrderAs expMediaCounts
    updates forall (_.state.scanInProgress) shouldBe true
    lastUpdate.state.scanInProgress shouldBe false
    lastUpdate.state.mediaCount should be > 0

  it should "send a message of the undefined medium only if such media occur" in:
    val mid = MediumID("someMedium", Some("someSettings"))
    val files = generateMediaFiles(path("somePath"), 8)
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()
    val contrib2 = MediaContribution(Map(mid -> files))
    helper.processContribution(contrib2)

    listener.expectMsg(MetaDataScanStarted)
    listener.expectMsg(MediumMetaDataCompleted(mid))
    listener.expectMsgType[MetaDataStateUpdated]
    listener.expectMsgType[MetaDataStateUpdated]
    listener.expectMsg(MetaDataScanCompleted)

  it should "support meta data without a duration" in:
    val helper = new MetaDataUnionActorTestHelper
    val uri = Contribution.files(TestMediumID).head
    helper.sendContribution()
    helper.actor receive MetaDataProcessingSuccess(TestMediumID, uri, metaDataFor(uri).copy(duration = None))

    val listener = helper.newStateListener(expectStateMsg = false)
    listener.expectMsgType[MetaDataStateUpdated].state.duration should be(0)

  it should "reset statistics when another scan starts" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.processContribution(Contribution)
    val listener1 = helper.newStateListener(expectStateMsg = false)
    listener1.expectMsgType[MetaDataStateUpdated].state.scanInProgress shouldBe false

    helper.sendScanStartsMessage()
    val listener2 = TestProbe()
    helper addStateListener listener2
    listener2.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0,
      songCount = 0, size = 0, duration = 0, scanInProgress = true, updateInProgress = false,
      archiveCompIDs = Set.empty)))

  it should "reset internal data when another scan starts" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.processContribution(Contribution)

    val results = Contribution.files(TestMediumID) take 2
    helper.sendScanStartsMessage().sendContribution()
      .sendProcessingResults(TestMediumID, results)
      .sendProcessingResults(MediaIDs(1), Contribution.files(MediaIDs(1)))
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false), TestMediumID, results, expComplete = false)

  it should "send an event if the scan is canceled" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val listener = helper.newStateListener()
    helper.sendCloseRequest()

    listener.expectMsg(MetaDataScanCanceled)
    listener.expectMsgType[MetaDataStateUpdated]
    listener.expectMsg(MetaDataScanCompleted)

  it should "send only a single event if the scan is canceled multiple times" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val listener = helper.newStateListener()
    helper.sendCloseRequest()

    helper.sendCloseRequest()
    listener.expectMsg(MetaDataScanCanceled)
    listener.expectMsgType[MetaDataStateUpdated]

  it should "send only a cancel event on receiving CloseRequest if a scan is running" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()

    helper.sendCloseRequest()
    expectNoMoreMessage(listener)

  it should "support removing state listeners" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener1 = helper.newStateListener()
    val listener2 = helper.newStateListener()

    helper.actor ! RemoveMetaDataStateListener(listener2.ref)
    helper.actor receive Contribution
    listener1.expectMsg(MetaDataScanStarted)
    expectNoMoreMessage(listener2)

  it should "support only a single listener registration per actor" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()

    helper addStateListener listener // add a 2nd time
    listener.expectMsgType[MetaDataStateUpdated]
    helper.sendContribution()
    helper.sendCloseRequest()
    listener.expectMsg(MetaDataScanStarted)
    listener.expectMsg(MetaDataScanCanceled)

  it should "remove a state listener if this actor dies" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()

    system stop listener.ref
    awaitCond(helper.actor.underlyingActor.registeredStateListeners.isEmpty)

  it should "process data from different sources in a single scan operation" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()
    helper.sendContribution()

    val contrib2 = processAnotherContribution(helper, generateMediaFiles(path("other"), 8))
    val startEventCount = new AtomicInteger
    helper.sendAllProcessingResults(Contribution)
    findScanCompletedEvent(listener):
      case MetaDataScanStarted =>
        startEventCount.incrementAndGet()
        false
    val mid = extractMediumID(contrib2)
    checkMetaDataChunk(helper.queryAndExpectMetaData(mid, registerAsListener = false), mid, contrib2.files(mid), expComplete = true)
    startEventCount.get() should be(1)

  it should "ignore processing results when no scan is in progress" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution().sendCloseRequest()

    val chunk = helper.sendAllProcessingResults(Contribution)
      .queryAndExpectMetaData(TestMediumID, registerAsListener = false)
    checkMetaDataChunk(chunk, TestMediumID, Iterable.empty, expComplete = false)

  it should "remove meta data when the responsible actor is removed" in:
    val helper = new MetaDataUnionActorTestHelper
    val otherContrib = createContributionFromOtherComponent()
    helper.sendContribution().sendContribution(otherContrib)
      .sendAllProcessingResults(Contribution).sendAllProcessingResults(otherContrib)

    helper.sendArchiveComponentRemoved()
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false), TestMediumID, Contribution.files(TestMediumID), expComplete = true)
    otherContrib.files.keys foreach helper.queryAndExpectUnknownMedium

  it should "update statistics when an archive component is removed" in:
    val helper = new MetaDataUnionActorTestHelper
    val otherContrib = createContributionFromOtherComponent()
    helper.sendContribution(Contribution).sendContribution(otherContrib)
      .sendAllProcessingResults(Contribution)
    val listener = helper.newStateListener(expectStateMsg = false)
    val stateOriginal = listener.expectMsgType[MetaDataStateUpdated]

    helper.sendAllProcessingResults(otherContrib)
      .sendArchiveComponentRemoved()
    val listener2 = helper.newStateListener(expectStateMsg = false)
    val state = listener2.expectMsgType[MetaDataStateUpdated]
    state.state.copy(scanInProgress = true) should be(stateOriginal.state)

  it should "only add a valid results to the global undefined list" in:
    val helper = new MetaDataUnionActorTestHelper
    val unknownId = MediumID("anotherUndefinedMedium", None)
    helper.sendContribution()
      .sendProcessingResults(unknownId, generateMediaFiles(RootPath, 4))
      .sendAllProcessingResults(Contribution)

    val chunk = helper.queryAndExpectMetaData(MediumID.UndefinedMediumID,
      registerAsListener = false)
    val expectedMetaData = Contribution.files(UndefinedMediumID) map metaDataFor
    expectedMetaData should contain theSameElementsAs chunk.data.values

  it should "update statistics only for valid results" in:
    val helper = new MetaDataUnionActorTestHelper
    val Count = 8
    val contrib = MediaContribution(Map(TestMediumID -> generateMediaFiles(RootPath, Count)))
    helper.sendContribution(contrib)
      .sendProcessingResults(MediumID("unknown medium", Some("path")),
        generateMediaFiles(path("somePath"), 1))
      .sendAllProcessingResults(contrib)

    val listener = helper.newStateListener(expectStateMsg = false)
    listener.expectMsgType[MetaDataStateUpdated].state.songCount should be(Count)

  it should "adapt the global undefined list if an archive component is removed" in:
    val helper = new MetaDataUnionActorTestHelper
    val contrib = createContributionFromOtherComponent()
    helper.processContribution(Contribution)
      .processContribution(contrib)
      .sendArchiveComponentRemoved()

    val chunk = helper.queryAndExpectMetaData(MediumID.UndefinedMediumID,
      registerAsListener = false)
    chunk.complete shouldBe true
    val uris = undefinedMediumUris(contrib)
    uris foreach (chunk.data.keys should not contain _)

  it should "not restructure the undefined medium if not affected by a removed component" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.processContribution(Contribution)
    val chunk = helper.queryAndExpectMetaData(MediumID.UndefinedMediumID,
      registerAsListener = false)

    helper.sendArchiveComponentRemoved("some unknown archive component")
    val chunk2 = helper.queryAndExpectMetaData(MediumID.UndefinedMediumID,
      registerAsListener = false)
    chunk2 should be theSameInstanceAs chunk

  it should "handle multiple chunks of the undefined medium if a component is removed" in:
    val helper = new MetaDataUnionActorTestHelper
    val mid = MediumID("alternativeMedium", None, "alternativeComponent")
    val alternativeContribution =
      MediaContribution(Map(mid -> generateMediaFiles(path("alternative"), 8)))
    val otherContrib = createContributionFromOtherComponent()
    helper.processContribution(Contribution)
      .processContribution(otherContrib)
      .processContribution(alternativeContribution)
      .sendArchiveComponentRemoved(mid.archiveComponentID)

    val undefinedIDs = List(Contribution, otherContrib).flatMap(_.files.keys)
      .filter(_.mediumDescriptionPath.isEmpty)
    helper.queryMetaData(MediumID.UndefinedMediumID, registerAsListener = false)
    val chunks = helper.expectMetaDataResponsesFor(TestRegistrationID, undefinedIDs: _*)
    chunks.checkCompletedFlags()
    val allKeys = chunks.chunks.flatMap(_._2.data.keys)
    val uris = (undefinedMediumUris(Contribution).toSeq ++ undefinedMediumUris(otherContrib)).map(_.uri)
    allKeys should contain theSameElementsAs uris

  it should "remove the undefined medium if it is no longer present" in:
    val contrib = MediaContribution(Map(
      mediumID("someMedium") -> generateMediaFiles(path("somePath"), 4)))
    val helper = new MetaDataUnionActorTestHelper
    helper.processContribution(contrib)
      .processContribution(createContributionFromOtherComponent())
      .sendArchiveComponentRemoved()

    helper.queryAndExpectUnknownMedium(MediumID.UndefinedMediumID)

  it should "send events when an archive component is removed" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.processContribution(Contribution)
    val listener1 = helper.newStateListener(expectStateMsg = false)
    val orgState = listener1.expectMsgType[MetaDataStateUpdated]
    helper.processContribution(createContributionFromOtherComponent())
    val listener2 = helper.newStateListener()

    helper.sendArchiveComponentRemoved()
    listener2.expectMsg(MetaDataScanStarted)
    listener2.expectMsg(orgState)
    listener2.expectMsg(MetaDataScanCompleted)

  it should "reset the undefined medium when another scan starts" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.processContribution(createContributionFromOtherComponent())
      .sendScanStartsMessage()
      .processContribution(Contribution)

    val chunk = helper.queryAndExpectMetaData(MediumID.UndefinedMediumID,
      registerAsListener = false)
    chunk.data should have size undefinedMediumUris(Contribution).size

  it should "process a removed archive component during a scan operation" in:
    val helper = new MetaDataUnionActorTestHelper
    val orgState = helper.processContribution(Contribution).readCurrentMetaDataState()
    helper.sendScanStartsMessage().sendContribution()
    val SongCount = 4
    processAnotherContribution(helper, generateMediaFiles(path("somePath"), SongCount))

    val stateInProgress = helper.sendArchiveComponentRemoved(expectResponse = false)
      .readCurrentMetaDataState()
    val listener = helper.newStateListener()
    helper.sendAllProcessingResults(Contribution)
    findScanCompletedEvent(listener)()
    stateInProgress.scanInProgress shouldBe true
    stateInProgress.songCount should be(SongCount)
    listener.expectMsg(MetaDataScanStarted)
    val endState = listener.expectMsgType[MetaDataStateUpdated]
    listener.expectMsg(MetaDataScanCompleted)
    endState.state should be(orgState)
    helper.expectRemovedConfirmation()

  it should "reset information about removed archive components" in:
    val helper = new MetaDataUnionActorTestHelper
    val orgState = helper.processContribution(Contribution).readCurrentMetaDataState()
    helper.sendScanStartsMessage().sendContribution()
      .processContribution(createContributionFromOtherComponent())
      .sendArchiveComponentRemoved(expectResponse = false)
      .sendAllProcessingResults(Contribution)
      .expectRemovedConfirmation()

    helper.sendScanStartsMessage().processContribution(Contribution)
    helper.readCurrentMetaDataState() should be(orgState)

  it should "complete a scan when only data from removed components is missing" in:
    val helper = new MetaDataUnionActorTestHelper
    val contrib = createContributionFromOtherComponent()
    val mid = extractMediumID(contrib)
    helper.sendContribution(contrib).processContribution(Contribution)
      .sendProcessingResults(mid, contrib.files(mid))
    val listener = helper.newStateListener()

    helper.sendArchiveComponentRemoved()
    findScanCompletedEvent(listener)()
    findScanCompletedEvent(listener)()

  it should "handle media listeners for removed components" in:
    val helper = new MetaDataUnionActorTestHelper
    val contrib = createContributionFromOtherComponent()
    val mid = extractMediumID(contrib)
    val files = contrib.files(mid) take 2
    helper.sendContribution(contrib).processContribution(Contribution)
      .sendProcessingResults(mid, files)
    checkMetaDataChunk(helper.queryAndExpectMetaData(mid, registerAsListener = true), mid, files, expComplete = false)

    val chunk = helper.sendArchiveComponentRemoved(expectResponse = false)
      .expectMetaDataResponse()
    helper.expectRemovedConfirmation()
    checkMetaDataChunk(chunk, mid, List.empty, expComplete = true)
    // check whether media listeners have been cleared
    helper.sendScanStartsMessage().processContribution(contrib)
    helper.queryAndExpectUnknownMedium(TestMediumID)

  it should "handle processing error results" in:
    val files = generateMediaFiles(path("someRootPath"), 8)
    val contribution = createOtherContribution(files)
    val mid = extractMediumID(contribution)
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()

    val successFiles = helper.sendContribution(contribution)
      .sendProcessingResultsAndError(mid, files)
    findScanCompletedEvent(listener)()
    val msg = helper.queryAndExpectMetaData(mid, registerAsListener = false)
    checkMetaDataChunk(msg, mid, successFiles, expComplete = true)

  it should "notify medium listeners correctly even in case of an error" in:
    val files = generateMediaFiles(path("someRootPathWithError"), 3)
    val contribution = createOtherContribution(files)
    val mid = extractMediumID(contribution)
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()

    val successFiles = helper.sendContribution(contribution)
      .queryMetaData(mid, registerAsListener = true)
      .sendProcessingResultsAndError(mid, files)
    findScanCompletedEvent(listener)()

    @tailrec def findCompletedMessage(fileUris: List[String]): List[String] =
      val chunk = helper.expectMetaDataResponse()
      val allUris = chunk.data.keys.toList ::: fileUris
      if chunk.complete then allUris
      else findCompletedMessage(allUris)

    val receivedUris = findCompletedMessage(Nil)
    val expUris = successFiles map (_.uri)
    receivedUris should contain theSameElementsAs expUris

  it should "handle a request for file meta data" in:
    val mid = MediaIDs(1)
    val files = Contribution.files(mid)
    val unkMediumFile = MediaFileID(MediumID("unknownMedium", Some("unknown")), "unknown")
    val unkUriFile = MediaFileID(mid, "nonExistingUri")
    val exFiles = files map (f => MediaFileID(mid, f.uri))
    val request = GetFilesMetaData(exFiles.toSet + unkMediumFile + unkUriFile, seqNo = 42)
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
      .sendProcessingResults(mid, files drop 1)

    helper.actor ! request
    val resp = expectMsgType[FilesMetaDataResponse]
    resp.request should be(request)
    val expKeys = exFiles.drop(1).toSeq
    val data = resp.data.toMap
    data.keys should contain theSameElementsAs expKeys
    files.drop(1) foreach { f =>
      val id = MediaFileID(mid, f.uri)
      data(id) should be(metaDataFor(f))
    }

  it should "handle a request for file meta data with a medium mapping" in:
    val mid = MediaIDs(1)
    val midMapped = MediumID("otherMedium", Some("other"), "other")
    val files = Contribution.files(mid)
    val reqFile1 = MediaFileID(mid, files.head.uri)
    val fileMapped = files.drop(1).head
    val reqFile2 = MediaFileID(midMapped, fileMapped.uri)
    val request = MetaDataUnionActor.GetFilesMetaDataWithMapping(GetFilesMetaData(seqNo = 11,
      files = Seq(reqFile1, reqFile2)), Map(reqFile2 -> mid))
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
      .sendProcessingResults(mid, files)

    helper.actor ! request
    val resp = expectMsgType[FilesMetaDataResponse]
    resp.request should be(request.request)
    val data = resp.data.toMap
    data.keys should contain only(reqFile1, reqFile2)
    data(reqFile2) should be(metaDataFor(fileMapped))

  it should "handle messages indicating start and end of update operations" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()

    helper.actor ! UpdateOperationStarts(None)
    listener.expectMsg(MetaDataUpdateInProgress)
    helper.actor ! UpdateOperationCompleted(None)
    listener.expectMsg(MetaDataUpdateCompleted)

  it should "ignore an update completed message from an unknown processor actor" in:
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()
    helper.actor ! UpdateOperationStarts(None)
    listener.expectMsg(MetaDataUpdateInProgress)

    helper.actor receive UpdateOperationCompleted(Some(TestProbe().ref))
    expectNoMoreMessage(listener)

  it should "deal with multiple concurrent update operations" in:
    val otherProcessor = TestProbe().ref
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()
    helper.actor ! UpdateOperationStarts(None)
    listener.expectMsg(MetaDataUpdateInProgress)

    helper.actor receive UpdateOperationStarts(Some(otherProcessor))
    expectNoMoreMessage(listener)
    helper.actor ! UpdateOperationCompleted(None)
    helper.actor receive UpdateOperationCompleted(Some(otherProcessor))
    listener.expectMsg(MetaDataUpdateCompleted)
    expectNoMoreMessage(listener)

  it should "monitor processor actors to remove them when they die" in:
    val processor = TestProbe().ref
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()
    helper.actor ! UpdateOperationStarts(Some(processor))
    listener.expectMsg(MetaDataUpdateInProgress)

    system stop processor
    listener.expectMsg(MetaDataUpdateCompleted)

  it should "update the operation in progress flag in the meta data state" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.actor ! UpdateOperationStarts(None)
    val listener1 = helper.newStateListener(expectStateMsg = false)

    listener1.expectMsgType[MetaDataStateUpdated].state.updateInProgress shouldBe true
    helper.actor ! UpdateOperationCompleted(None)
    val listener2 = helper.newStateListener(expectStateMsg = false)
    listener2.expectMsgType[MetaDataStateUpdated].state.updateInProgress shouldBe false

  it should "support querying statistics for archive components" in:
    val expStats = ArchiveComponentStatistics(archiveComponentID = ArchiveCompID, mediaCount = 3,
      songCount = 15, size = 5200, duration = 520)
    val helper = new MetaDataUnionActorTestHelper
    helper.processContribution(Contribution)

    helper.actor ! GetArchiveComponentStatistics(ArchiveCompID)
    expectMsg(expStats)
    expStats.isValid shouldBe true

  it should "handle a statistics request for an unknown archive component" in:
    val helper = new MetaDataUnionActorTestHelper
    helper.processContribution(Contribution)

    helper.actor ! GetArchiveComponentStatistics(OtherArchiveCompID)
    val stats = expectMsgType[ArchiveComponentStatistics]
    stats should be(ArchiveComponentStatistics(OtherArchiveCompID, -1, -1, -1, -1))
    stats.isValid shouldBe false

  /**
    * Test helper class which manages a test actor instance and offers some
    * convenience methods for test cases.
    */
  private class MetaDataUnionActorTestHelper:
    /** Reference to the test actor. */
    val actor: TestActorRef[MetaDataUnionActor] = createTestActor()

    /**
      * Sends the specified ''MediaContribution'' to the test actor.
      *
      * @param contr the ''MediaContribution''
      * @return this test helper
      */
    def sendContribution(contr: MediaContribution = Contribution): MetaDataUnionActorTestHelper =
      actor ! contr
      this

    /**
      * Sends a request for meta data to the test actor.
      *
      * @param mediumID           the medium ID
      * @param registerAsListener the register as listener flag
      * @param registrationID     the registration ID
      * @return this test helper
      */
    def queryMetaData(mediumID: MediumID, registerAsListener: Boolean,
                      registrationID: Int = TestRegistrationID): MetaDataUnionActorTestHelper =
      actor ! GetMetaData(mediumID, registerAsListener, registrationID)
      this

    /**
      * Expects a meta data response message with the specified registration
      * ID. The chunk data of this message is returned.
      *
      * @param registrationID the registration ID
      * @return the meta data chunk from the response message
      */
    def expectMetaDataResponse(registrationID: Int = TestRegistrationID): MetaDataChunk =
      val response = expectMsgType[MetaDataResponse]
      response.registrationID should be(registrationID)
      response.chunk

    /**
      * Expects a number of meta data chunks for different media and returns a
      * map with the received results.
      *
      * @param registrationID the registration ID
      * @param ids            the IDs of the media in the expected chunks
      * @return an object with the received chunks
      */
    def expectMetaDataResponsesFor(registrationID: Int, ids: MediumID*): MultiChunkResponse =
      val chunks = (1 to ids.size) map { _ =>
        val chunk = expectMetaDataResponse(registrationID)
        chunk.mediumID -> chunk
      }
      chunks.map(_._1) should contain theSameElementsAs ids
      MultiChunkResponse(chunks)

    /**
      * Expects that a completion chunk for the undefined medium is received.
      *
      * @param registrationID the registration ID
      * @return this test helper
      */
    def expectUndefinedMediumCompletionResponse(registrationID: Int = TestRegistrationID):
    MetaDataUnionActorTestHelper =
      expectMetaDataResponse(registrationID) should be(MetaDataChunk(MediumID.UndefinedMediumID,
        Map.empty, complete = true))
      this

    /**
      * Expects an unknown medium response for the given medium.
      *
      * @param mediumID the medium ID
      * @return this test helper
      */
    def expectUnknownMedium(mediumID: MediumID): MetaDataUnionActorTestHelper =
      expectMsg(UnknownMedium(mediumID))
      this

    /**
      * Sends a request for meta data for the test actor and expects a response.
      *
      * @param mediumID           the medium ID
      * @param registerAsListener the register as listener flag
      * @param registrationID     the registration ID
      * @return the chunk message received from the test actor
      */
    def queryAndExpectMetaData(mediumID: MediumID, registerAsListener: Boolean,
                               registrationID: Int = TestRegistrationID): MetaDataChunk =
      queryMetaData(mediumID, registerAsListener)
      expectMetaDataResponse(registrationID)

    /**
      * Sends a request for meta data for a medium and expects an unknown
      * medium response.
      *
      * @param mediumID the medium ID
      * @return this test helper
      */
    def queryAndExpectUnknownMedium(mediumID: MediumID): MetaDataUnionActorTestHelper =
      queryMetaData(mediumID, registerAsListener = false)
      expectUnknownMedium(mediumID)

    /**
      * Sends processing result objects for the given files to the test actor.
      *
      * @param mediumID the medium ID
      * @param files    the list of files
      * @return this test helper
      */
    def sendProcessingResults(mediumID: MediumID, files: Iterable[MediaFileUri]): MetaDataUnionActorTestHelper =
      files foreach { m =>
        actor receive processingResult(mediumID, m)
      }
      this

    /**
      * Sends processing results for all files contained in the specified
      * contribution.
      *
      * @param contribution the contribution
      * @return this test helper
      */
    def sendAllProcessingResults(contribution: MediaContribution): MetaDataUnionActorTestHelper =
      contribution.files.foreach(t => sendProcessingResults(t._1, t._2))
      this

    /**
      * Sends messages to the test actor to process the specified contribution.
      * The contribution is sent, and then processing results for all contained
      * files.
      *
      * @param contribution the contribution
      * @return this test helper
      */
    def processContribution(contribution: MediaContribution): MetaDataUnionActorTestHelper =
      actor ! contribution
      sendAllProcessingResults(contribution)

    /**
      * Sends a list with processing results to the test actor, but replaces
      * the last elements by an error result. This is used to test error
      * handling.
      *
      * @param mediumID the medium ID
      * @param files    the list of URIs for media files
      * @return the list with successful results sent to the test actor
      */
    def sendProcessingResultsAndError(mediumID: MediumID, files: Iterable[MediaFileUri]): Iterable[MediaFileUri] =
      val successFiles = files dropRight 1
      val errResult = processingResult(mediumID, files.last).toError(new Exception("Error"))
      sendProcessingResults(mediumID, successFiles)
      actor receive errResult
      successFiles

    /**
      * Sends a message to the test actor indicating the start of a new media
      * scan.
      *
      * @return this test helper
      */
    def sendScanStartsMessage(): MetaDataUnionActorTestHelper =
      actor ! ScanAllMedia
      this

    /**
      * Adds a test probe as meta data state listener to the test actor.
      *
      * @param probe the test probe to be registered
      * @return this test helper
      */
    def addStateListener(probe: TestProbe): MetaDataUnionActorTestHelper =
      actor ! AddMetaDataStateListener(probe.ref)
      this

    /**
      * Creates a test probe for a state listener and registers it at the test
      * actor.
      *
      * @param expectStateMsg a flag whether the state message should be
      *                       expected
      * @return the test probe for the listener
      */
    def newStateListener(expectStateMsg: Boolean = true): TestProbe =
      val probe = TestProbe()
      addStateListener(probe)
      if expectStateMsg then
        probe.expectMsgType[MetaDataStateUpdated]
      probe

    /**
      * Registers a temporary state listener for obtaining the current meta
      * data state.
      *
      * @return the current meta data state
      */
    def readCurrentMetaDataState(): MetaDataState =
      val listener = newStateListener(expectStateMsg = false)
      val stateMsg = listener.expectMsgType[MetaDataStateUpdated]
      actor ! RemoveMetaDataStateListener(listener.ref)
      stateMsg.state

    /**
      * Sends a close request to the test actor and expects the acknowledge.
      *
      * @return this test helper
      */
    def sendCloseRequest(): MetaDataUnionActorTestHelper =
      actor ! CloseRequest
      expectMsg(CloseAck(actor))
      this

    /**
      * Sends a message to the test actor that an archive component has been
      * removed.
      *
      * @param componentID    the component ID
      * @param expectResponse flag whether a response from the test actor
      *                       should be expected
      * @return this test helper
      */
    def sendArchiveComponentRemoved(componentID: String = OtherArchiveCompID,
                                    expectResponse: Boolean = true):
    MetaDataUnionActorTestHelper =
      actor ! ArchiveComponentRemoved(componentID)
      if expectResponse then
        expectRemovedConfirmation(componentID)
      this

    /**
      * Expects a confirmation message for a removed archive component.
      *
      * @param componentID the component ID
      * @return this test helper
      */
    def expectRemovedConfirmation(componentID: String = OtherArchiveCompID):
    MetaDataUnionActorTestHelper =
      expectMsg(RemovedArchiveComponentProcessed(componentID))
      this

    /**
      * Creates an instance of the test actor.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[MetaDataUnionActor] =
      TestActorRef[MetaDataUnionActor](Props(classOf[MetaDataUnionActor], createConfig()))

  /**
    * Creates a mock for the central configuration object.
    *
    * @return the mock configuration
    */
  private def createConfig() =
    val config = mock[MediaArchiveConfig]
    when(config.metaDataUpdateChunkSize).thenReturn(UpdateChunkSize)
    when(config.metaDataMaxMessageSize).thenReturn(MaxMessageSize)
    config

  /**
    * A data class that represents multiple received meta data chunks and
    * offers some functionality to test them. This is mainly used for tests
    * related to the global undefined medium where chunks from different media
    * are involved.
    *
    * @param chunks the list with chunks (in the order they were received)
    */
  private case class MultiChunkResponse(chunks: IndexedSeq[(MediumID, MetaDataChunk)]):
    /**
      * Returns the chunk for the given medium ID or throws an exception if
      * there is no such chunk.
      *
      * @param mid the ''MediumID''
      * @return the chunk associated with this medium ID
      */
    def apply(mid: MediumID): MetaDataChunk = chunks.find(_._1 == mid).map(_._2).get

    /**
      * Checks the complete flags of the chunks contained in this object. The
      * function expects that only for the last chunk the complete flag is
      * '''true'''. Note: This function expects that the list of chunks is not
      * empty.
      */
    def checkCompletedFlags(): Unit =
      chunks.init foreach (_._2.complete shouldBe false)
      chunks.last._2.complete shouldBe true

    /**
      * Convenience function to return the ''complete'' flag of the chunk for
      * the given medium ID.
      *
      * @param mid the ''MediumID''
      * @return the ''complete'' flag of this chunk
      */
    def isComplete(mid: MediumID): Boolean = apply(mid).complete

    /**
      * Returns a merged chunk that contains all the data of chunks with the
      * given ''MediumID''.
      *
      * @param mid      the ''MediumID''
      * @param complete the complete flag
      * @return the merged ''MetaDataChunk''
      */
    def mergeChunks(mid: MediumID, complete: Boolean = true): MetaDataChunk =
      val data = chunks.filter(_._1 == mid)
        .flatMap(_._2.data)
        .toMap
      MetaDataChunk(mid, data, complete)
