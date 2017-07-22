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

package de.oliver_heger.linedj.archivehttp.impl

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes, Uri}
import akka.stream.{DelayOverflowStrategy, KillSwitch}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Try}

object MetaDataResponseProcessingActorSpec {
  /** Test medium ID. */
  private val TestMediumID = MediumID("testMedium", Some("test.settings"),
    "HTTPArchive")

  private val DefaultArchiveConfig = HttpArchiveConfig(Uri("https://music.arc"),
    "Test", UserCredentials("scott", "tiger"), processorCount = 3,
    processorTimeout = Timeout(2.seconds), maxContentSize = 256,
    downloadConfig = null, downloadBufferSize = 100, downloadMaxInactivity = 1.minute,
    downloadReadChunkSize = 500, timeoutReadChunkSize = 250)

  /** The sequence number used for requests. */
  private val SeqNo = 42

  /**
    * Creates a meta data processing result object for the specified index.
    *
    * @param idx the index
    * @return the test processing result
    */
  private def processingResult(idx: Int): MetaDataProcessingSuccess =
    MetaDataProcessingSuccess(s"songs/song$idx.mp3", TestMediumID, s"audio://song$idx.mp3",
      MediaMetaData(title = Some(s"Song$idx"), size = (idx + 1) * 100))

  /**
    * Creates a sequence with test meta data of the specified size.
    *
    * @param count the number of meta data objects
    * @return the sequence with the produced meta data
    */
  private def createProcessingResults(count: Int): IndexedSeq[MetaDataProcessingSuccess] =
    (1 to count) map processingResult

  /**
    * Generates a JSON representation for the specified meta data.
    *
    * @param data the meta data
    * @return the JSON representation for this data
    */
  private def jsonMetaData(data: MetaDataProcessingSuccess): String =
    s"""{
       |"title":"${data.metaData.title.get}",
       |"size":"${data.metaData.size}",
       |"uri":"${data.uri}",
       |"path":"${data.path}"
       |}
   """.stripMargin

  /**
    * Generates the JSON representation for a whole sequence of meta data
    * objects. This method produces a JSON array with the single elements
    * as object content.
    *
    * @param data the sequence of data objects
    * @return the JSON representation for this sequence
    */
  private def generateJson(data: Iterable[MetaDataProcessingSuccess]): String =
    data.map(jsonMetaData).mkString("[", ",\n", "]")

  /**
    * Creates a successful HTTP response with the given entity string.
    *
    * @param body the body of the response as string
    * @return the response
    */
  private def createResponse(body: String): HttpResponse =
    HttpResponse(entity = body)
}

/**
  * Test class for ''MetaDataResponseProcessingActor''.
  */
class MetaDataResponseProcessingActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers
  with MockitoSugar {

  import MetaDataResponseProcessingActorSpec._

  def this() = this(ActorSystem("MetaDataResponseProcessingActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A MetaDataResponseProcessingActor" should "directly react on a failed response" in {
    val actor = system.actorOf(Props[MetaDataResponseProcessingActor])
    val response = HttpResponse(status = StatusCodes.BadRequest)

    actor ! ProcessResponse(TestMediumID, Success(response), DefaultArchiveConfig, SeqNo)
    val errMsg = expectMsgType[ResponseProcessingError]
    errMsg.mediumID should be(TestMediumID)
    errMsg.fileType should be(MetaDataResponseProcessingActor.FileType)
    errMsg.exception shouldBe a[IllegalStateException]
    errMsg.exception.getMessage contains StatusCodes.BadRequest.toString() shouldBe true
  }

  it should "directly react on a Failure for the response" in {
    val actor = system.actorOf(Props[MetaDataResponseProcessingActor])
    val exception = new Exception("Failed response")
    val triedResponse = Try[HttpResponse](throw exception)

    actor ! ProcessResponse(TestMediumID, triedResponse, DefaultArchiveConfig, SeqNo)
    val errMsg = expectMsgType[ResponseProcessingError]
    errMsg.mediumID should be(TestMediumID)
    errMsg.fileType should be(MetaDataResponseProcessingActor.FileType)
    errMsg.exception should be(exception)
  }

  it should "handle a successful response" in {
    val metaDataResults = createProcessingResults(8)
    val response = createResponse(generateJson(metaDataResults))
    val actor = system.actorOf(Props[MetaDataResponseProcessingActor])

    actor ! ProcessResponse(TestMediumID, Try(response), DefaultArchiveConfig, SeqNo)
    val result = expectMsgType[MetaDataResponseProcessingResult]
    result.mediumID should be(TestMediumID)
    result.metaData should contain theSameElementsAs metaDataResults
    result.seqNo should be(SeqNo)
  }

  it should "apply a size restriction when processing a response" in {
    val response = createResponse(generateJson(createProcessingResults(32)))
    val actor = system.actorOf(Props[MetaDataResponseProcessingActor])

    actor ! ProcessResponse(TestMediumID, Try(response),
      DefaultArchiveConfig.copy(maxContentSize = 1), SeqNo)
    val errMsg = expectMsgType[ResponseProcessingError]
    errMsg.mediumID should be(TestMediumID)
    errMsg.fileType should be(MetaDataResponseProcessingActor.FileType)
  }

  it should "allow cancellation of the current stream" in {
    val responseData = generateJson(createProcessingResults(64))
    val jsonStrings = responseData.grouped(64)
      .map(ByteString(_))
    val source = Source[ByteString](jsonStrings.toList).delay(1.second,
      DelayOverflowStrategy.backpressure)
    val actor = system.actorOf(Props(new MetaDataResponseProcessingActor {
      override def createResponseDataSource(mid: MediumID, response: HttpResponse,
                                            config: HttpArchiveConfig):
      Source[ByteString, Any] = source
    }))

    actor ! ProcessResponse(TestMediumID, Try(createResponse(responseData)),
      DefaultArchiveConfig, SeqNo)
    actor ! CancelStreams
    expectMsgType[MetaDataResponseProcessingResult]
  }

  it should "unregister kill switches after stream completion" in {
    val killSwitch = mock[KillSwitch]
    val Result = 42
    val props = Props(new MetaDataResponseProcessingActor {
      override protected def processSource(source: Source[ByteString, Any], mid: MediumID,
                                          seqNo: Int): (Future[Any], KillSwitch) =
        (Future.successful(Result), killSwitch)
    })
    val actor = TestActorRef[MetaDataResponseProcessingActor](props)
    actor ! ProcessResponse(TestMediumID,
      Try(createResponse(generateJson(createProcessingResults(2)))), DefaultArchiveConfig,
      SeqNo)
    expectMsg(Result)

    actor receive CancelStreams
    verify(killSwitch, never()).shutdown()
  }
}
