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
package de.oliver_heger.linedj.metadata

import java.nio.file.{Path, Paths}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.config.ServerConfig
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.media._
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.concurrent.duration._

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
  private def metaDataFor(path: Path): MediaMetaData =
    MediaMetaData(title = Some(path.getFileName.toString))

  /**
   * Generates a number of media files that belong to the specified test
   * medium.
    *
    * @param mediumPath the path of the medium
   * @param count the number of files to generate
   * @return the resulting list
   */
  private def generateMediaFiles(mediumPath: Path, count: Int): List[FileData] = {
    @tailrec
    def loop(current: List[FileData], index: Int): List[FileData] = {
      if (index == 0) current
      else loop(FileData(mediumPath.resolve(s"TestFile_$index.mp3"), 20) :: current, index - 1)
    }

    loop(Nil, count)
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
    * Generates a global URI to file mapping for the given result object.
    *
    * @param result the ''MediaScanResult''
    * @return the URI to file mapping for this result
    */
  private def createFileUriMapping(result: MediaScanResult): Map[String, FileData] =
    result.mediaFiles.values.flatten.map(f => (uriFor(f.path), f)).toMap
}

/**
 * Test class for ''MetaDataManagerActor''.
 */
class MetaDataManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import MetaDataManagerActorSpec._

  def this() = this(ActorSystem("MetaDataManagerActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A MetaDataManagerActor" should "send an answer for an unknown medium ID" in {
    val actor = system.actorOf(MetaDataManagerActor(mock[ServerConfig]))

    val mediumID = MediumID("unknown medium ID", None)
    actor ! GetMetaData(mediumID, registerAsListener = false)
    expectMsg(UnknownMedium(mediumID))
  }

  it should "create a child actor for processing a scan result" in {
    val helper = new MetaDataManagerActorTestHelper

    helper.startProcessing()
    helper.processorProbe.expectMsg(ProcessMediaFiles)
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
    * Tells the test actor to process another medium with the specified
    * content.
    * @param helper the test helper
    * @param files the files to be found on this medium
    * @return a generated ID for the other medium
    */
  private def processAnotherMedium(helper: MetaDataManagerActorTestHelper, files: List[FileData])
  : MediumID = {
    val root = path("anotherRootDirectory")
    val medID = MediumID(root.toString, Some("someDescFile.txt"))
    val scanResult2 = MediaScanResult(root, Map(medID -> files))
    helper.actor ! EnhancedMediaScanResult(scanResult2, Map(medID -> "testCheckSum"),
      createFileUriMapping(scanResult2))
    helper.sendProcessingResults(medID, files)
    medID
  }

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
    val actor = TestActorRef[MediaManagerActor](MetaDataManagerActor(mock[ServerConfig]))
    actor receive RemoveMediumListener(mediumID("someMedium"), testActor)
  }

  it should "support completion listeners" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    helper.actor ! AddCompletionListener(testActor)
    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))
    helper.sendProcessingResults(UndefinedMediumID, ScanResult.mediaFiles(UndefinedMediumID))
    expectMsg(MediumMetaDataCompleted(TestMediumID))
    expectMsg(MediumMetaDataCompleted(UndefinedMediumID))
    expectMsg(MediumMetaDataCompleted(MediumID.UndefinedMediumID))
  }

  it should "support removing completion listeners" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.actor ! AddCompletionListener(testActor)

    helper.actor ! RemoveCompletionListener(testActor)
    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false),
      TestMediumID, ScanResult.mediaFiles(TestMediumID), expComplete = true)
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
     * A test probe that simulates a child actor. This object is returned by the stub
     * implementation of the child actor factory.
     */
    val processorProbe = TestProbe()

    /** The configuration. */
    val config = createConfig()

    /** The test actor reference. */
    val actor = createTestActor()

    /**
     * Convenience function for sending a message to the test actor that starts
     * processing.
      *
      * @return the test actor
     */
    def startProcessing(): ActorRef = {
      actor ! EnhancedScanResult
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
        actor ! MetaDataProcessingResult(m.path, mediumID, uriFor(m.path), metaDataFor(m.path))
      }
    }

    /**
     * Creates the standard test actor.
      *
      * @return the test actor
     */
    private def createTestActor(): ActorRef = system.actorOf(creationProps())

    private def creationProps(): Props =
      Props(new MetaDataManagerActor(config) with ChildActorFactory {
        /**
         * Creates a child actor based on the specified ''Props'' object. This
         * implementation uses the actor's context to actually create the child.
          *
          * @param p the ''Props'' defining the actor to be created
         * @return the ''ActorRef'' to the new child actor
         */
        override def createChildActor(p: Props): ActorRef = {
          if (checkChildActorProps) {
            val sampleProps = MediumProcessorActor(EnhancedScanResult, config)
            p.actorClass() should be(sampleProps.actorClass())
            p.args should be(sampleProps.args)
          }
          processorProbe.ref
        }
      })

    /**
     * Creates a mock for the central configuration object.
      *
      * @return the mock configuration
     */
    private def createConfig() = {
      val config = mock[ServerConfig]
      when(config.metaDataUpdateChunkSize).thenReturn(2)
      when(config.metaDataMaxMessageSize).thenReturn(MaxMessageSize)
      config
    }
  }

}
