/*
 * Copyright 2015 The Developers Team.
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
import de.oliver_heger.linedj.media._
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.concurrent.duration._

object MetaDataManagerActorSpec {
  /** ID of a test medium. */
  private val TestMediumID = DefinedMediumID(path("medium1"))

  /** A list of medium IDs used by the tests. */
  private val MediaIDs = List(TestMediumID, DefinedMediumID(path("otherMedium")),
    DefinedMediumID(path("coolMusic")))

  /** A test scan result object. */
  private val ScanResult = createScanResult()

  /**
   * Helper method for generating a path.
   * @param s the name of this path
   * @return the path
   */
  private def path(s: String): Path = Paths get s

  /**
   * Creates a test meta data object for the specified path.
   * @param path the path
   * @return the meta data for this path (following conventions)
   */
  private def metaDataFor(path: Path): MediaMetaData =
    MediaMetaData(title = Some(path.getFileName.toString))

  /**
   * Generates a number of media files that belong to the specified test
   * medium.
   * @param mediumPath the path of the medium
   * @param count the number of files to generate
   * @return the resulting list
   */
  private def generateMediaFiles(mediumPath: Path, count: Int): List[MediaFile] = {
    @tailrec
    def loop(current: List[MediaFile], index: Int): List[MediaFile] = {
      if (index == 0) current
      else loop(MediaFile(mediumPath.resolve(s"TestFile_$index.mp3"), 20) :: current, index - 1)
    }

    loop(Nil, count)
  }

  /**
   * Creates a test scan result object.
   */
  private def createScanResult(): MediaScanResult = {
    val numbersOfSongs = List(3, 8, 4)
    val fileData = MediaIDs zip numbersOfSongs map { e =>
      (e._1: MediumID, generateMediaFiles(e._1.path, e._2))
    }
    val fileMap = Map(fileData: _*) + (UndefinedMediumID -> generateMediaFiles(path("noMedium"),
      11))
    MediaScanResult(path("Root"), fileMap)
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
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A MetaDataManagerActor" should "send an answer for an unknown medium ID" in {
    val actor = system.actorOf(MetaDataManagerActor(mock[ServerConfig]))

    val mediumID = "unknown medium ID"
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
   * expected data.
   * @param msg the chunk message to be checked
   * @param mediumID the medium ID
   * @param expectedFiles the expected files
   * @param expComplete the expected complete flag
   */
  private def checkMetaDataChunk(msg: MetaDataChunk, mediumID: DefinedMediumID,
                                 expectedFiles: List[MediaFile], expComplete: Boolean): Unit = {
    checkMetaDataChunk(msg, mediumID.path.toString, expectedFiles, expComplete)
  }

  /**
   * Checks whether a meta data chunk received from the test actor contains the
   * expected data for the given medium ID as string. This method also works
   * for the undefined medium ID.
   * @param msg the chunk message to be checked
   * @param mediumID the medium ID as string
   * @param expectedFiles the expected files
   * @param expComplete the expected complete flag
   */
  private def checkMetaDataChunk(msg: MetaDataChunk, mediumID: String,
                                 expectedFiles: List[MediaFile], expComplete: Boolean): Unit = {
    msg.mediumID should be(mediumID)
    msg.data should have size expectedFiles.size
    expectedFiles foreach { m =>
      msg.data(m.path.toString) should be(metaDataFor(m.path))
    }
    msg.complete shouldBe expComplete
  }

  it should "return a partial result for a medium when processing is not yet complete" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    val msg = helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false)
    msg.mediumID should be(TestMediumID.path.toString)
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

  it should "handle the undefined medium even over multiple scan results" in {
    val helper = new MetaDataManagerActorTestHelper(checkChildActorProps = false)
    helper.startProcessing()
    val filesForChunk1 = ScanResult.mediaFiles(UndefinedMediumID) dropRight 1
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk1)
    helper.actor ! GetMetaData("", registerAsListener = true)
    checkMetaDataChunk(expectMsgType[MetaDataChunk], "", filesForChunk1, expComplete = false)
    val filesForChunk2 = List(ScanResult.mediaFiles(UndefinedMediumID).last)
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk2)
    checkMetaDataChunk(expectMsgType[MetaDataChunk], "", filesForChunk2, expComplete = true)

    val filesForChunk3 = generateMediaFiles(path("fileOnOtherMedium"), 4)
    val scanResult2 = MediaScanResult(path("anotherMedium"), Map(UndefinedMediumID ->
      filesForChunk3))
    helper.actor ! scanResult2
    helper.sendProcessingResults(UndefinedMediumID, filesForChunk3)
    helper.actor ! GetMetaData("", registerAsListener = false)
    val allFiles = ScanResult.mediaFiles(UndefinedMediumID) ::: filesForChunk3
    checkMetaDataChunk(expectMsgType[MetaDataChunk], "", allFiles, expComplete = true)
  }

  it should "allow removing a medium listener" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()
    helper.queryAndExpectMetaData(TestMediumID, registerAsListener = true).data shouldBe 'empty

    helper.actor ! RemoveMediumListener(TestMediumID.path.toString, testActor)
    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))
    checkMetaDataChunk(helper.queryAndExpectMetaData(TestMediumID, registerAsListener = false),
      TestMediumID, ScanResult.mediaFiles(TestMediumID), expComplete = true)
  }

  it should "ignore an unknown medium when removing a medium listener" in {
    val actor = TestActorRef[MediaManagerActor](MetaDataManagerActor(mock[ServerConfig]))
    actor receive RemoveMediumListener("someMedium", testActor)
  }

  it should "support completion listeners" in {
    val helper = new MetaDataManagerActorTestHelper
    helper.startProcessing()

    helper.actor ! AddCompletionListener(testActor)
    helper.sendProcessingResults(TestMediumID, ScanResult.mediaFiles(TestMediumID))
    helper.sendProcessingResults(UndefinedMediumID, ScanResult.mediaFiles(UndefinedMediumID))
    expectMsg(MediumMetaDataCompleted(TestMediumID.path.toString))
    expectMsg(MediumMetaDataCompleted(""))
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
     * @return the test actor
     */
    def startProcessing(): ActorRef = {
      actor ! ScanResult
      actor
    }

    /**
     * Sends a request for meta data to the test actor.
     * @param mediumID the medium ID
     * @param registerAsListener the register as listener flag
     */
    def queryMetaData(mediumID: MediumID, registerAsListener: Boolean): Unit = {
      actor ! GetMetaData(mediumID.mediumDescriptionPath.get.toString,
        registerAsListener)
    }

    /**
     * Sends a request for meta data for the test actor and expects a response.
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
     * @param mediumID the medium ID
     * @param files the list of files
     */
    def sendProcessingResults(mediumID: MediumID, files: List[MediaFile]): Unit = {
      files foreach { m =>
        actor ! MetaDataProcessingResult(m.path, mediumID, metaDataFor(m.path))
      }
    }

    /**
     * Creates the standard test actor.
     * @return the test actor
     */
    private def createTestActor(): ActorRef = system.actorOf(creationProps())

    private def creationProps(): Props =
      Props(new MetaDataManagerActor(config) with ChildActorFactory {
        /**
         * Creates a child actor based on the specified ''Props'' object. This
         * implementation uses the actor's context to actually create the child.
         * @param p the ''Props'' defining the actor to be created
         * @return the ''ActorRef'' to the new child actor
         */
        override def createChildActor(p: Props): ActorRef = {
          if (checkChildActorProps) {
            val sampleProps = MediumProcessorActor(ScanResult, config)
            p.actorClass() should be(sampleProps.actorClass())
            p.args should be(sampleProps.args)
          }
          processorProbe.ref
        }
      })

    /**
     * Creats a mock for the central configuration object.
     * @return the mock configuration
     */
    private def createConfig() = {
      val config = mock[ServerConfig]
      when(config.metaDataUpdateChunkSize).thenReturn(2)
      config
    }
  }

}
