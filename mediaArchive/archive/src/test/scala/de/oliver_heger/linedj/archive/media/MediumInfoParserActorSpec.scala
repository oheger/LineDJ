/*
 * Copyright 2015-2023 The Developers Team.
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
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.stream.DelayOverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.{ImplicitSender, TestKit}
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyString, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Path, Paths}
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.{Success, Try}

object MediumInfoParserActorSpec {
  /** Constant for a medium ID. */
  private val TestMediumID = MediumID("test://TestMedium", None)

  /** An object with test medium info data. */
  private val TestInfo = MediumInfo(name = "TestMedium", description = "Some desc",
    mediumID = TestMediumID, orderMode = "Directories", checksum = "")

  /** The maximum size restriction for description files. */
  private val MaxFileSize = 4 * 8192

  /** A test sequence number. */
  private val SeqNo = 42
}

/**
  * Test class for ''MediumInfoParserActor''.
  */
class MediumInfoParserActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar
  with FileTestHelper {

  import MediumInfoParserActorSpec._

  def this() = this(ActorSystem("MediumInfoParserActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Creates a test actor which operates on the specified parser.
    *
    * @param parser the parser
    * @return the test actor reference
    */
  private def parserActor(parser: MediumInfoParser): ActorRef =
    system.actorOf(Props(classOf[MediumInfoParserActor], parser, MaxFileSize))

  /**
    * Checks whether a ''MediumInfo'' object contains only dummy data.
    *
    * @param settings the object to be checked
    */
  private def verifyDummyMediumInfo(settings: MediumInfo): Unit = {
    settings.name should be("unknown")
    settings.description should have length 0
    settings.mediumID should be(TestMediumID)
    settings.orderMode should have length 0
    settings.checksum should have length 0
  }

  /**
    * Produces a file with test content of the specified size.
    *
    * @param size the size of the test file
    * @return the path to the newly created file
    */
  private def createLargeTestFile(size: Int): Path = {
    val testByteStr = ByteString(FileTestHelper.testBytes())

    @tailrec def generateContent(current: ByteString): ByteString =
      if (current.length >= size) current.take(size)
      else generateContent(current ++ testByteStr)

    createDataFile(generateContent(ByteString.empty).utf8String)
  }

  "A MediumInfoParserActor" should "handle a successful parse operation" in {
    val parser = mock[MediumInfoParser]
    val file = createDataFile()
    when(parser.parseMediumInfo(FileTestHelper.testBytes(), TestMediumID))
      .thenReturn(Success(TestInfo))

    val actor = parserActor(parser)
    val parseRequest = ParseMediumInfo(file, TestMediumID, SeqNo)
    actor ! parseRequest
    expectMsg(ParseMediumInfoResult(parseRequest, TestInfo))
  }

  it should "return a default description if a parsing error occurs" in {
    val parser = mock[MediumInfoParser]
    when(parser.parseMediumInfo(any(), argEq(TestMediumID), anyString()))
      .thenReturn(Try[MediumInfo](throw new Exception))

    val actor = parserActor(parser)
    val parseRequest = ParseMediumInfo(createDataFile(), TestMediumID, SeqNo)
    actor ! parseRequest
    val settings = expectMsgType[ParseMediumInfoResult].info
    verifyDummyMediumInfo(settings)
  }

  it should "return a default description if a stream processing error occurs" in {
    val parser = mock[MediumInfoParser]
    val actor = parserActor(parser)
    val parseRequest = ParseMediumInfo(Paths get "nonExistingFile.xxx", TestMediumID, SeqNo)

    actor ! parseRequest
    val result = expectMsgType[ParseMediumInfoResult]
    result.request should be(parseRequest)
    verifyDummyMediumInfo(result.info)
    verifyNoInteractions(parser)
  }

  it should "support cancellation of stream processing" in {
    val parser = mock[MediumInfoParser]
    when(parser.parseMediumInfo(any(), argEq(TestMediumID), any()))
      .thenReturn(Success(TestInfo))
    val FileSize = 3 * 8192
    val file = createLargeTestFile(FileSize)
    val actor = system.actorOf(Props(new MediumInfoParserActor(parser, MaxFileSize) {
      override private[media] def createSource(path: Path): Source[ByteString, Any] =
        super.createSource(path).delay(1.second, DelayOverflowStrategy.backpressure)
    }))
    actor ! ParseMediumInfo(file, TestMediumID, SeqNo)

    actor ! AbstractStreamProcessingActor.CancelStreams
    expectMsgType[ParseMediumInfoResult]
    val captor = ArgumentCaptor.forClass(classOf[Array[Byte]])
    verify(parser).parseMediumInfo(captor.capture(), argEq(TestMediumID), any())
    captor.getValue.length should be < FileSize
  }

  it should "apply a size restriction" in {
    val parser = mock[MediumInfoParser]
    val file = createLargeTestFile(MaxFileSize + 8192)
    val parseRequest = ParseMediumInfo(file, TestMediumID, SeqNo)
    val actor = parserActor(parser)

    actor ! parseRequest
    val result = expectMsgType[ParseMediumInfoResult]
    result.request should be(parseRequest)
    verifyDummyMediumInfo(result.info)
    verifyNoInteractions(parser)
  }
}
