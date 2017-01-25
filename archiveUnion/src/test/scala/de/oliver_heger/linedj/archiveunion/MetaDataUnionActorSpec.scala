/*
 * Copyright 2015-2017 The Developers Team.
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

import java.nio.file.{Path, Paths}

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{MediumID, ScanAllMedia}
import de.oliver_heger.linedj.shared.archive.metadata._
import org.mockito.Mockito.when
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec

object MetaDataUnionActorSpec {
  /** The maximum message size. */
  private val MaxMessageSize = 24

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

  /** Constant for an alternative archive component ID. */
  private val ArchiveCompID = "otherArchiveComponent"

  /** A special test message sent to actors. */
  private val TestMessage = new Object

  /** Constant for a default media contribution. */
  private val Contribution = createContribution()

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
    * Extracts an index from the given path. All test paths produced by
    * ''generateMediaFiles()'' end on a numeric index (before the file
    * extension). This function extracts this index. (Note: it is not very
    * robust for other paths.)
    *
    * @param path the path
    * @return the index of this path
    */
  private def extractPathIndex(path: Path): Int = {
    val pathStr = path.toString takeWhile (_ != '.')

    @tailrec def loop(index: Int): Int =
      if (Character isDigit pathStr(index)) loop(index - 1)
      else pathStr.substring(index + 1).toInt

    loop(pathStr.length - 1)
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
    * Creates a test media contribution object.
    *
    * @return the contribution object
    */
  private def createContribution(): MediaContribution = {
    val numbersOfSongs = List(3, 8, 4)
    val fileData = MediaIDs zip numbersOfSongs map { e =>
      (e._1, generateMediaFiles(path(e._1.mediumDescriptionPath.get), e._2))
    }
    val fileMap = Map(fileData: _*) + (MediumID(RootPath.toString, None) -> generateMediaFiles
    (path("noMedium"), 11))
    MediaContribution(fileMap)
  }

  /**
    * Generates a number of media files that belong to the specified test
    * medium.
    *
    * @param mediumPath the path of the medium
    * @param count      the number of files to generate
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
    * Extracts the first ''MediumID'' found in the specified contribution.
    *
    * @param contribution the contribution
    * @return the extracted medium ID
    */
  private def extractMediumID(contribution: MediaContribution): MediumID =
    contribution.files.keys.head

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
  * Test class for ''MetaDataUnionActor''.
  */
class MetaDataUnionActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import MetaDataUnionActorSpec._

  def this() = this(ActorSystem("MetaDataUnionActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Checks whether a meta data chunk received from the test actor contains the
    * expected data for the given medium ID. File URIs are expected to follow
    * default conventions.
    *
    * @param msg           the chunk message to be checked
    * @param mediumID      the medium ID as string
    * @param expectedFiles the expected files
    * @param expComplete   the expected complete flag
    */
  private def checkMetaDataChunk(msg: MetaDataChunk, mediumID: MediumID,
                                 expectedFiles: Iterable[FileData], expComplete: Boolean): Unit = {
    checkMetaDataChunkWithUris(msg, mediumID, expectedFiles, expComplete)(uriFor)
  }

  /**
    * Checks whether a meta data chunk received from the test actor contains the
    * expected data for the given medium ID and verifies the URIs in the chunk.
    *
    * @param msg           the chunk message to be checked
    * @param mediumID      the medium ID as string
    * @param expectedFiles the expected files
    * @param expComplete   the expected complete flag
    * @param uriGen        the URI generator to be used
    */
  private def checkMetaDataChunkWithUris(msg: MetaDataChunk, mediumID: MediumID,
                                         expectedFiles: Iterable[FileData], expComplete: Boolean)
                                        (uriGen: Path => String): Unit = {
    msg.mediumID should be(mediumID)
    msg.data should have size expectedFiles.size
    expectedFiles foreach { m =>
      msg.data(uriGen(m.path)) should be(metaDataFor(m.path))
    }
    msg.complete shouldBe expComplete
  }

  /**
    * Generates an alternative contribution with a single medium of the
    * specified content and tells the test actor to process it.
    *
    * @param helper the test helper
    * @param files  the files to be found in this contribution
    * @return the alternative contribution
    */
  private def processAnotherContribution(helper: MetaDataUnionActorTestHelper,
                                         files: Iterable[FileData]): MediaContribution = {
    val root = path("anotherRootDirectory")
    val medID = MediumID(root.toString, Some("someDescFile.txt"), ArchiveCompID)
    val contribution = MediaContribution(Map(medID -> files))
    helper.processContribution(contribution)
    contribution
  }

  "A MetaDataUnionActor" should "send an answer for an unknown medium ID" in {
    val helper = new MetaDataUnionActorTestHelper
    val mediumID = MediumID("unknown medium ID", None)

    helper.queryMetaData(mediumID, registerAsListener = false)
    expectMsg(UnknownMedium(mediumID))
  }

  it should "allow querying a complete medium" in {
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
      .sendProcessingResults(TestMediumID, Contribution.files(TestMediumID))
    val msg = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false)
    checkMetaDataChunk(msg, TestMediumID, Contribution.files(TestMediumID), expComplete = true)
  }

  it should "return a partial result for a medium when processing is not yet complete" in {
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()

    val msg = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false)
    msg.mediumID should be(TestMediumID)
    msg.data shouldBe 'empty
    msg.complete shouldBe false
  }

  it should "notify medium listeners when new results become available" in {
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()

    val msgEmpty = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = true)
    msgEmpty.data shouldBe 'empty
    msgEmpty.complete shouldBe false

    val filesForChunk1 = Contribution.files(TestMediumID).take(2)
    helper.sendProcessingResults(TestMediumID, filesForChunk1)
    checkMetaDataChunk(helper.expectMetaDataResponse(), TestMediumID, filesForChunk1,
      expComplete = false)

    val filesForChunk2 = List(Contribution.files(TestMediumID).last)
    helper.sendProcessingResults(TestMediumID, filesForChunk2)
    checkMetaDataChunk(helper.expectMetaDataResponse(), TestMediumID, filesForChunk2,
      expComplete = true)
  }

  it should "allow querying files not assigned to a medium" in {
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val files = Contribution.files(UndefinedMediumID)
    helper.sendProcessingResults(UndefinedMediumID, files)

    helper.actor ! GetMetaData(UndefinedMediumID, registerAsListener = false, 0)
    checkMetaDataChunk(helper.expectMetaDataResponse(0), UndefinedMediumID, files,
      expComplete = true)
  }

  it should "handle the undefined medium even over multiple contributions" in {
    def refUri(mediumID: MediumID)(path: Path): String =
      s"ref://${mediumID.mediumURI}:${uriFor(path)}"

    def findUrisInChunk(mediumID: MediumID, chunk: MetaDataChunk, files: Iterable[FileData]):
    Unit = {
      files.map(d => refUri(mediumID)(d.path)).filterNot(chunk.data.contains) shouldBe 'empty
    }

    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val filesForChunk1 = Contribution.files(UndefinedMediumID) dropRight 1
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk1)
    helper.actor ! GetMetaData(MediumID.UndefinedMediumID, registerAsListener = true,
      TestRegistrationID)
    checkMetaDataChunkWithUris(helper.expectMetaDataResponse(), MediumID.UndefinedMediumID,
      filesForChunk1, expComplete = false)(refUri(UndefinedMediumID))
    val filesForChunk2 = List(Contribution.files(UndefinedMediumID).last)
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk2)
    checkMetaDataChunkWithUris(helper.expectMetaDataResponse(), MediumID.UndefinedMediumID,
      filesForChunk2, expComplete = true)(refUri(UndefinedMediumID))

    val filesForChunk3 = generateMediaFiles(path("fileOnOtherMedium"), 4)
    val root2 = path("anotherRootDirectory")
    val UndefinedMediumID2 = MediumID(root2.toString, None)
    val contribution2 = MediaContribution(Map(UndefinedMediumID2 -> filesForChunk3))
    helper.actor ! contribution2
    helper.sendProcessingResults(UndefinedMediumID2, filesForChunk3)
    helper.actor ! GetMetaData(MediumID.UndefinedMediumID, registerAsListener = false, 0)
    val chunk = helper.expectMetaDataResponse(0)
    findUrisInChunk(UndefinedMediumID, chunk, Contribution.files(UndefinedMediumID))
    findUrisInChunk(UndefinedMediumID2, chunk, filesForChunk3)
  }

  it should "split large chunks of meta data into multiple ones" in {
    val helper = new MetaDataUnionActorTestHelper
    helper.sendContribution()
    val files = generateMediaFiles(path("fileOnOtherMedium"), MaxMessageSize + 4)
    val medID = extractMediumID(processAnotherContribution(helper, files))

    helper.queryMetaData(medID, registerAsListener = true)
    checkMetaDataChunk(helper.expectMetaDataResponse(), medID, files take MaxMessageSize,
      expComplete = false)
    checkMetaDataChunk(helper.expectMetaDataResponse(), medID, files drop MaxMessageSize,
      expComplete = true)
  }

  it should "allow removing a medium listener" in {
    val helper = new MetaDataUnionActorTestHelper
    val data = helper.sendContribution()
      .queryAndExpectMetaData(TestMediumID, registerAsListener = true).data
    data shouldBe 'empty

    helper.actor ! RemoveMediumListener(TestMediumID, testActor)
    helper.sendProcessingResults(TestMediumID, Contribution.files(TestMediumID))
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false),
      TestMediumID, Contribution.files(TestMediumID), expComplete = true)
  }

  it should "ignore an unknown medium when removing a medium listener" in {
    val helper = new MetaDataUnionActorTestHelper

    helper.actor receive RemoveMediumListener(mediumID("someMedium"), testActor)
  }

  it should "pass the current meta data state to a newly registered state listener" in {
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener(expectStateMsg = false)

    listener.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 0,
      size = 0, duration = 0, scanInProgress = false)))
  }

  it should "correctly update the scan in progress state when a scan starts" in {
    val helper = new MetaDataUnionActorTestHelper
    val listener = TestProbe()
    helper.sendContribution()

    helper.addStateListener(listener)
    listener.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 0,
      size = 0, duration = 0, scanInProgress = true)))
  }

  it should "correctly update the meta data state during a scan operation" in {
    val helper = new MetaDataUnionActorTestHelper
    val listener1, listener2 = TestProbe()
    helper.sendContribution()
      .sendProcessingResults(TestMediumID, Contribution.files(TestMediumID) take 1)
      .addStateListener(listener1)
    listener1.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 1,
      size = 100, duration = 10, scanInProgress = true)))

    helper.sendProcessingResults(TestMediumID, Contribution.files(TestMediumID).slice(1, 2))
    helper addStateListener listener2
    listener2.expectMsg(MetaDataStateUpdated(MetaDataState(mediaCount = 0, songCount = 2,
      size = 300, duration = 30, scanInProgress = true)))
  }

  it should "send messages during a scan operation" in {
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
  }

  it should "send a message of the undefined medium only if such media occur" in {
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
  }

  it should "support meta data without a duration" in {
    val helper = new MetaDataUnionActorTestHelper
    val file = Contribution.files(TestMediumID).head
    helper.sendContribution()
    helper.actor receive MetaDataProcessingResult(file.path, TestMediumID, uriFor(file.path),
      metaDataFor(file.path).copy(duration = None))

    val listener = helper.newStateListener(expectStateMsg = false)
    listener.expectMsgType[MetaDataStateUpdated].state.duration should be(0)
  }

  it should "support removing state listeners" in {
    val helper = new MetaDataUnionActorTestHelper
    val listener1 = helper.newStateListener()
    val listener2 = helper.newStateListener()

    helper.actor ! RemoveMetaDataStateListener(listener2.ref)
    helper.actor receive Contribution
    listener1.expectMsg(MetaDataScanStarted)
    expectNoMoreMessage(listener2)
  }

  it should "remove a state listener if this actor dies" in {
    val helper = new MetaDataUnionActorTestHelper
    val listener = helper.newStateListener()

    system stop listener.ref
    awaitCond(helper.actor.underlyingActor.registeredStateListeners.isEmpty)
  }

  /**
    * Test helper class which manages a test actor instance and offers some
    * convenience methods for test cases.
    */
  private class MetaDataUnionActorTestHelper {
    /** Reference to the test actor. */
    val actor: TestActorRef[MetaDataUnionActor] = createTestActor()

    /**
      * Sends the specified ''MediaContribution'' to the test actor.
      *
      * @param contr the ''MediaContribution''
      * @return this test helper
      */
    def sendContribution(contr: MediaContribution = Contribution): MetaDataUnionActorTestHelper = {
      actor ! contr
      this
    }

    /**
      * Sends a request for meta data to the test actor.
      *
      * @param mediumID           the medium ID
      * @param registerAsListener the register as listener flag
      * @param registrationID     the registration ID
      * @return this test helper
      */
    def queryMetaData(mediumID: MediumID, registerAsListener: Boolean,
                      registrationID: Int = TestRegistrationID): MetaDataUnionActorTestHelper = {
      actor ! GetMetaData(mediumID, registerAsListener, registrationID)
      this
    }

    /**
      * Expects a meta data response message with the specified registration
      * ID. The chunk data of this message is returned.
      *
      * @param registrationID the registration ID
      * @return the meta data chunk from the response message
      */
    def expectMetaDataResponse(registrationID: Int = TestRegistrationID): MetaDataChunk = {
      val response = expectMsgType[MetaDataResponse]
      response.registrationID should be(registrationID)
      response.chunk
    }

    /**
      * Sends a request for meta data for the test actor and expects a response.
      *
      * @param mediumID           the medium ID
      * @param registerAsListener the register as listener flag
      * @param registrationID     the registration ID
      * @return the chunk message received from the test actor
      */
    def queryAndExpectMetaData(mediumID: MediumID, registerAsListener: Boolean,
                               registrationID: Int = TestRegistrationID): MetaDataChunk = {
      queryMetaData(mediumID, registerAsListener)
      expectMetaDataResponse(registrationID)
    }

    /**
      * Sends processing result objects for the given files to the test actor.
      *
      * @param mediumID the medium ID
      * @param files    the list of files
      * @return this test helper
      */
    def sendProcessingResults(mediumID: MediumID, files: Iterable[FileData]):
    MetaDataUnionActorTestHelper = {
      files foreach { m =>
        actor receive MetaDataProcessingResult(m.path, mediumID, uriFor(m.path),
          metaDataFor(m.path))
      }
      this
    }

    /**
      * Sends processing results for all files contained in the specified
      * contribution.
      *
      * @param contribution the contribution
      * @return this test helper
      */
    def sendAllProcessingResults(contribution: MediaContribution): MetaDataUnionActorTestHelper = {
      contribution.files.foreach(t => sendProcessingResults(t._1, t._2))
      this
    }

    /**
      * Sends messages to the test actor to process the specified contribution.
      * The contribution is sent, and then processing results for all contained
      * files.
      *
      * @param contribution the contribution
      * @return this test helper
      */
    def processContribution(contribution: MediaContribution): MetaDataUnionActorTestHelper = {
      actor ! contribution
      sendAllProcessingResults(contribution)
    }

    /**
      * Sends a message to the test actor indicating the start of a new media
      * scan.
      *
      * @return this test helper
      */
    def sendScanStartsMessage(): MetaDataUnionActorTestHelper = {
      actor ! ScanAllMedia
      this
    }

    /**
      * Adds a test probe as meta data state listener to the test actor.
      *
      * @param probe the test probe to be registered
      * @return this test helper
      */
    def addStateListener(probe: TestProbe): MetaDataUnionActorTestHelper = {
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
      * Creates an instance of the test actor.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[MetaDataUnionActor] =
      TestActorRef[MetaDataUnionActor](Props(classOf[MetaDataUnionActor], createConfig()))
  }

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
