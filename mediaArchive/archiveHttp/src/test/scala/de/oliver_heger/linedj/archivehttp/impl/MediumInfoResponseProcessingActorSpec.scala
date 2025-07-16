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

package de.oliver_heger.linedj.archivehttp.impl

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.shared.archive.media.{MediumDescription, MediumID, MediumInfo}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{DelayOverflowStrategy, KillSwitch}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.apache.pekko.util.{ByteString, Timeout}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, Succeeded}
import org.scalatestplus.mockito.MockitoSugar
import spray.json.JsonParser

import scala.concurrent.Future
import scala.concurrent.duration.*

object MediumInfoResponseProcessingActorSpec:
  /** Test medium ID. */
  private val TestMediumID = MediumID("mediumUri", Some("settings"))

  /** Checksum for the test medium. */
  private val Checksum = "12345"

  /** A test medium info object to be returned by a mock parser. */
  private val TestMediumInfo = MediumInfo(
    mediumID = TestMediumID,
    mediumDescription = MediumDescription(
      name = "TestMedium",
      description = "A test medium",
      orderMode = "fully_random"
    ),
    checksum = Checksum
  )

  /** The JSON representation of the test medium description to be parsed. */
  private val TestMediumDescriptionJson =
    s"""
       |{
       |  "name": "${TestMediumInfo.mediumDescription.name}",
       |  "description": "${TestMediumInfo.mediumDescription.description}",
       |  "orderMode": "${TestMediumInfo.mediumDescription.orderMode}"
       |}""".stripMargin

  /** A test HTTP medium description object. */
  private val TestDesc = HttpMediumDesc(
    mediumDescriptionPath = "medium.json",
    metaDataPath = s"/test/meta-data/$Checksum.mdt"
  )

  /** Test configuration for the archive. */
  private val DefaultArchiveConfig = HttpArchiveConfig(Uri("https://music.arc"),
    "Test", processorCount = 3, processorTimeout = Timeout(2.seconds), maxContentSize = 256, propagationBufSize = 4,
    downloadConfig = null, downloadBufferSize = 100, downloadMaxInactivity = 1.minute,
    downloadReadChunkSize = 500, timeoutReadSize = 250, downloader = null, contentPath = Uri.Path("toc.json"),
    mediaPath = Uri.Path("media"), metadataPath = Uri.Path("metadata"))

  /** A timeout value for waiting for async results. */
  private val WaitTimeout = 3.seconds

  /** Sequence number for the current test scan operation. */
  private val SeqNo = 111

  /**
    * Creates a source based on the given data with multiple small chunks.
    *
    * @param data the content of the source
    * @return the resulting chunked source
    */
  private def chunkedSource(data: String): Source[ByteString, NotUsed] =
    Source(ByteString(data).grouped(16).toList)

  /**
    * Creates a source which produces the chunks of the test medium
    * information.
    *
    * @return the source
    */
  private def mediumInfoSource(): Source[ByteString, NotUsed] =
    chunkedSource(TestMediumDescriptionJson)

  /**
    * Convenience method to invoke the test actor. This method calls the method
    * for source processing and returns the results.
    *
    * @param actor  the test actor
    * @param source the source to be passed to the actor
    * @param desc   the medium description to be used
    * @return the future stream processing result and a kill switch
    */
  private def invoke(actor: TestActorRef[MediumInfoResponseProcessingActorTestImpl],
                     source: Source[ByteString, Any] = mediumInfoSource(),
                     desc: HttpMediumDesc = TestDesc):
  (Future[Any], KillSwitch) =
    actor.underlyingActor.processSource(source, TestMediumID, desc, null, SeqNo)

/**
  * Test class for ''MediumInfoResponseProcessingActor''.
  */
class MediumInfoResponseProcessingActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AsyncFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:

  import MediumInfoResponseProcessingActorSpec.*

  def this() = this(ActorSystem("MediumInfoResponseProcessingActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Creates a test actor reference to a test actor.
    *
    * @return the test actor reference
    */
  private def createActor(): TestActorRef[MediumInfoResponseProcessingActorTestImpl] =
    val props = Props(classOf[MediumInfoResponseProcessingActorTestImpl])
    TestActorRef(props)

  /**
    * Checks whether the actor produces a correct result based on the
    * provided medium description.
    *
    * @param desc the medium description
    */
  private def checkParseResult(desc: HttpMediumDesc): Future[Assertion] =
    val actor = createActor()

    val (futureStream, _) = invoke(actor, desc = desc)
    futureStream map { result =>
      result should be(MediumInfoResponseProcessingResult(TestMediumInfo, SeqNo))
    }

  "A MediumInfoResponseProcessingActor" should "produce a correct result" in :
    checkParseResult(TestDesc)

  it should "handle a medium description with a strange metadata file name" in :
    val desc = TestDesc.copy(metaDataPath = Checksum)
    checkParseResult(desc)

  it should "handle a parsing error" in :
    val actor = createActor()

    recoverToSucceededIf[JsonParser.ParsingException] {
      invoke(actor, chunkedSource(FileTestHelper.TestData))._1
    }

  it should "support canceling the stream" in :
    val source = mediumInfoSource().delay(2.seconds, DelayOverflowStrategy.backpressure)
    val actor = createActor()

    recoverToSucceededIf[JsonParser.ParsingException] {
      val (futureStream, killSwitch) = invoke(actor, source)
      killSwitch.shutdown()
      futureStream
    }

  it should "propagate the medium description correctly" in :
    val actor = createActor()
    val source = Source.single(ByteString(TestMediumDescriptionJson))
    val msg = ProcessResponse(TestMediumID, TestDesc, source, DefaultArchiveConfig, SeqNo)

    actor ! msg
    expectMsg(MediumInfoResponseProcessingResult(TestMediumInfo, SeqNo))
    Succeeded

/**
  * A test actor implementation which exposes the method for processing
  * the source of the response entity.
  */
class MediumInfoResponseProcessingActorTestImpl extends MediumInfoResponseProcessingActor:
  /**
    * Overridden to allow access from test code.
    */
  override def processSource(source: Source[ByteString, Any], mid: MediumID, desc: HttpMediumDesc,
                             config: HttpArchiveConfig, seqNo: Int): (Future[Any], KillSwitch) =
    super.processSource(source, mid, desc, config, seqNo)
