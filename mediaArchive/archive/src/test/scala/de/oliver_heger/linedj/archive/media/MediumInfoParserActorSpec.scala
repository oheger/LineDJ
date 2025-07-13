/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.media.MediumInfoParserActor.{ParseMediumInfo, ParseMediumInfoResult}
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor
import de.oliver_heger.linedj.shared.archive.media.{MediumDescription, MediumID, MediumInfo}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Path, Paths}
import scala.annotation.tailrec

object MediumInfoParserActorSpec:
  /** Constant for a medium ID. */
  private val TestMediumID = MediumID("test://TestMedium", None)

  /** An object with test medium info data. */
  private val TestInfo = MediumInfo(
    mediumID = TestMediumID,
    mediumDescription = MediumDescription(
      name = "TestMedium",
      description = "Some desc",
      orderMode = "Directories"
    ),
    checksum = ""
  )

  /** The maximum size restriction for description files. */
  private val MaxFileSize = 4 * 8192

  /** A test sequence number. */
  private val SeqNo = 42
end MediumInfoParserActorSpec

/**
  * Test class for ''MediumInfoParserActor''.
  */
class MediumInfoParserActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar
  with FileTestHelper:

  import MediumInfoParserActorSpec.*

  def this() = this(ActorSystem("MediumInfoParserActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    tearDownTestFile()

  /**
    * Creates a test actor with default settings.
    *
    * @return the test actor reference
    */
  private def parserActor(maxFileSize: Int = MaxFileSize): ActorRef =
    system.actorOf(Props(classOf[MediumInfoParserActor], maxFileSize))

  /**
    * Checks whether a ''MediumInfo'' object contains only dummy data.
    *
    * @param settings the object to be checked
    */
  private def verifyDummyMediumInfo(settings: MediumInfo): Unit =
    settings.name should be("unknown")
    settings.description should have length 0
    settings.mediumID should be(TestMediumID)
    settings.orderMode should have length 0
    settings.checksum should have length 0

  /**
    * Produces a file with test content greater than the specified size. The
    * resulting file is valid JSON. It has a number of synthetic properties
    * to reach the desired size.
    *
    * @param size the size of the test file
    * @return the path to the newly created file
    */
  private def createLargeTestFile(size: Int): Path =
    val dummyPropertyValue = FileTestHelper.TestData.replace('\n', ' ')

    @tailrec def generateContent(current: ByteString, propertyIndex: Int): ByteString =
      if current.length >= size then
        current ++ ByteString("\n}")
      else
        val property = s",\n  \"prop$propertyIndex\": \"$dummyPropertyValue\""
        generateContent(current ++ ByteString(property), propertyIndex + 1)

    val initialJson =
      """
        |{
        |  "name": "testMedium",
        |  "description": "Some test medium",
        |  "orderMode": "random"
        |""".stripMargin
    createDataFile(generateContent(ByteString(initialJson), 1).utf8String)

  "A MediumInfoParserActor" should "handle a successful parse operation" in :
    val testInfoUri = getClass.getResource("/testMediumInfo.json")
    val expectedDescription = MediumDescription(
      name = "testMedium",
      description = "This is a test medium used for testing",
      orderMode = "artists"
    )
    val file = Paths.get(testInfoUri.toURI)

    val actor = parserActor()
    val parseRequest = ParseMediumInfo(file, TestMediumID, SeqNo)
    actor ! parseRequest
    
    val response = expectMsgType[ParseMediumInfoResult]
    response.request should be(parseRequest)
    response.info.mediumID should be(TestMediumID)
    response.info.mediumDescription should be(expectedDescription)
    response.info.checksum should be("")
  
  it should "return a default description if a parsing error occurs" in :
    val parseRequest = ParseMediumInfo(createDataFile(), TestMediumID, SeqNo)
    
    val actor = parserActor()
    actor ! parseRequest
    
    val info = expectMsgType[ParseMediumInfoResult].info
    verifyDummyMediumInfo(info)

  it should "return a default description if a stream processing error occurs" in :
    val parseRequest = ParseMediumInfo(Paths get "nonExistingFile.xxx", TestMediumID, SeqNo)
    
    val actor = parserActor()
    actor ! parseRequest
    
    val result = expectMsgType[ParseMediumInfoResult]
    result.request should be(parseRequest)
    verifyDummyMediumInfo(result.info)

  it should "support cancellation of stream processing" in :
    val FileSize = 20 * 8192
    val file = createLargeTestFile(FileSize)
    val actor = parserActor(2 * FileSize)
    actor ! ParseMediumInfo(file, TestMediumID, SeqNo)

    actor ! AbstractStreamProcessingActor.CancelStreams
    val result = expectMsgType[ParseMediumInfoResult]
    verifyDummyMediumInfo(result.info)

  it should "apply a size restriction" in :
    val file = createLargeTestFile(MaxFileSize + 8192)
    val parseRequest = ParseMediumInfo(file, TestMediumID, SeqNo)
    
    val actor = parserActor()
    actor ! parseRequest
    
    val result = expectMsgType[ParseMediumInfoResult]
    result.request should be(parseRequest)
    verifyDummyMediumInfo(result.info)
