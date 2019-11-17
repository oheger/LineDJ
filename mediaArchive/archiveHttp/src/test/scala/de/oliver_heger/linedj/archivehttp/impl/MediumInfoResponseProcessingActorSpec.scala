/*
 * Copyright 2015-2019 The Developers Team.
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

import java.nio.charset.StandardCharsets

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.stream.scaladsl.Source
import akka.stream.{DelayOverflowStrategy, KillSwitch}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{any, anyString, eq => eqArg}
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Success

object MediumInfoResponseProcessingActorSpec {
  /** Test medium ID. */
  private val TestMediumID = MediumID("mediumUri", Some("settings"))

  /**
    * A sequence of strings that represent a test medium information file.
    * The actor under test will be invoked with a source that produces
    * corresponding chunks of byte strings.
    */
  private val MediumInfoChunks = List("Medium ", "information ", "in multiple ",
    "chunks to ", "be", " parsed.")

  /** The concatenated content of the test medium info. */
  private val MediumInfoContent = MediumInfoChunks.mkString("")

  /** Checksum for the test medium. */
  private val Checksum = "12345"

  /** A test medium info object to be returned by a mock parser. */
  private val TestMediumInfo = MediumInfo(name = "TestMedium",
    description = "A test medium", mediumID = TestMediumID,
    orderMode = null, orderParams = null, checksum = Checksum)

  /** A test HTTP medium description object. */
  private val TestDesc = HttpMediumDesc(mediumDescriptionPath = "playlist.settings",
    metaDataPath = s"/test/meta-data/$Checksum.mdt")

  /** Test configuration for the archive. */
  private val DefaultArchiveConfig = HttpArchiveConfig(Uri("https://music.arc"),
    "Test", UserCredentials("scott", "tiger"), processorCount = 3,
    processorTimeout = Timeout(2.seconds), maxContentSize = 256, propagationBufSize = 4,
    downloadConfig = null, downloadBufferSize = 100, downloadMaxInactivity = 1.minute,
    downloadReadChunkSize = 500, timeoutReadSize = 250, metaMappingConfig = null,
    contentMappingConfig = null, requestQueueSize = 4, cryptUriCacheSize = 1024)

  /** A timeout value for waiting for async results. */
  private val WaitTimeout = 3.seconds

  /** Sequence number for the current test scan operation. */
  private val SeqNo = 111

  /**
    * Creates a source which produces the chunks of the test medium
    * information.
    *
    * @return the source
    */
  private def mediumInfoSource(): Source[ByteString, NotUsed] =
    Source(MediumInfoChunks) map (ByteString(_))

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
}

/**
  * Test class for ''MediumInfoResponseProcessingActor''.
  */
class MediumInfoResponseProcessingActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import MediumInfoResponseProcessingActorSpec._

  def this() = this(ActorSystem("MediumInfoResponseProcessingActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a test actor reference to a test actor.
    *
    * @param parser the medium info parser
    * @return the test actor reference
    */
  private def createActor(parser: MediumInfoParser):
  TestActorRef[MediumInfoResponseProcessingActorTestImpl] = {
    val props = Props(classOf[MediumInfoResponseProcessingActorTestImpl], parser)
    TestActorRef(props)
  }

  "A MediumInfoResponseProcessingActor" should "create a default parser" in {
    val actor = TestActorRef[MediumInfoResponseProcessingActor](
      Props[MediumInfoResponseProcessingActor])

    actor.underlyingActor.infoParser should not be null
  }

  /**
    * Checks whether the actor produces a correct result based on the
    * provided medium description.
    *
    * @param desc the medium description
    */
  private def checkParseResult(desc: HttpMediumDesc): Unit = {
    val parser = mock[MediumInfoParser]
    when(parser.parseMediumInfo(aryEq(MediumInfoContent.getBytes(StandardCharsets.UTF_8)),
      eqArg(TestMediumID), eqArg(Checksum))).thenReturn(Success(TestMediumInfo))
    val actor = createActor(parser)

    val (futureStream, _) = invoke(actor, desc = desc)
    val result = Await.result(futureStream, WaitTimeout)
    result should be(MediumInfoResponseProcessingResult(TestMediumInfo, SeqNo))
  }

  it should "produce a correct result" in {
    checkParseResult(TestDesc)
  }

  it should "handle a medium description with a strange meta data file name" in {
    val desc = TestDesc.copy(metaDataPath = Checksum)
    checkParseResult(desc)
  }

  it should "handle a parsing error" in {
    val exception = new IllegalStateException("Simulated parsing exception")
    val parser = mock[MediumInfoParser]
    when(parser.parseMediumInfo(any(classOf[Array[Byte]]), eqArg(TestMediumID), anyString()))
      .thenThrow(exception)
    val actor = createActor(parser)

    val (futureStream, _) = invoke(actor)
    intercept[IllegalStateException] {
      Await.result(futureStream, WaitTimeout)
    } should be(exception)
  }

  it should "support canceling the stream" in {
    val parser = mock[MediumInfoParser]
    when(parser.parseMediumInfo(any(classOf[Array[Byte]]), eqArg(TestMediumID), anyString()))
      .thenReturn(Success(TestMediumInfo))
    val source = mediumInfoSource().delay(2.seconds, DelayOverflowStrategy.backpressure)
    val actor = createActor(parser)
    val (futureStream, killSwitch) = invoke(actor, source)

    killSwitch.shutdown()
    Await.ready(futureStream, WaitTimeout)
    val captor = ArgumentCaptor.forClass(classOf[Array[Byte]])
    verify(parser).parseMediumInfo(captor.capture(), eqArg(TestMediumID), anyString())
    captor.getValue.length should be < MediumInfoContent.length
  }

  it should "propagate the medium description correctly" in {
    val parser = mock[MediumInfoParser]
    when(parser.parseMediumInfo(aryEq(MediumInfoContent.getBytes(StandardCharsets.UTF_8)),
      eqArg(TestMediumID), eqArg(Checksum))).thenReturn(Success(TestMediumInfo))
    val actor = createActor(parser)
    val response = HttpResponse(entity = MediumInfoContent)
    val msg = ProcessResponse(TestMediumID, TestDesc, response, DefaultArchiveConfig, SeqNo)

    actor ! msg
    expectMsg(MediumInfoResponseProcessingResult(TestMediumInfo, SeqNo))
  }
}

/**
  * A test actor implementation which exposes the method for processing
  * the source of the response entity.
  *
  * @param parser the ''MediumInfoParser''
  */
class MediumInfoResponseProcessingActorTestImpl(parser: MediumInfoParser)
  extends MediumInfoResponseProcessingActor(parser) {
  /**
    * Overridden to allow access from test code.
    */
  override def processSource(source: Source[ByteString, Any], mid: MediumID, desc: HttpMediumDesc,
                             config: HttpArchiveConfig, seqNo: Int): (Future[Any], KillSwitch) =
    super.processSource(source, mid, desc, config, seqNo)
}
