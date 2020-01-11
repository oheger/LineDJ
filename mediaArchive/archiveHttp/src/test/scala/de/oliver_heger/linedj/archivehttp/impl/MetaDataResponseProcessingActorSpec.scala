/*
 * Copyright 2015-2020 The Developers Team.
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

import akka.actor.{ActorSystem, Props, Status}
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Source
import akka.stream.{DelayOverflowStrategy, KillSwitch}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivecommon.uri.{UriMapper, UriMappingSpec}
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UriMappingConfig}
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.concurrent.duration._

object MetaDataResponseProcessingActorSpec {
  /** Path prefix for the test online archive. */
  private val ArchivePath = "/test/music"

  /** The relative path to the test medium. */
  private val MediumPath = "medium"

  /** The path to the test songs. */
  private val SongPath = "/artist/album"

  /** Test medium ID. */
  private val TestMediumID = MediumID("testMedium",
    Some(s"$ArchivePath/$MediumPath/test.settings"), "HTTPArchive")

  /** A test mapping configuration. */
  private val MappingConfig = UriMappingConfig(removePrefix = "audio://",
    pathSeparator = null, urlEncode = false, uriTemplate = "${uri}",
    removeComponents = 0)

  /** Test configuration for the archive. */
  private val DefaultArchiveConfig = HttpArchiveConfig(Uri("https://music.arc" + ArchivePath),
    "Test", processorCount = 3, processorTimeout = Timeout(2.seconds), maxContentSize = 256, propagationBufSize = 4,
    downloadConfig = null, downloadBufferSize = 100, downloadMaxInactivity = 1.minute,
    downloadReadChunkSize = 500, timeoutReadSize = 250, metaMappingConfig = MappingConfig,
    contentMappingConfig = null, requestQueueSize = 4, cryptUriCacheSize = 512,
    needsCookieManagement = false, protocol = null, authFunc = null)

  /** The sequence number used for requests. */
  private val SeqNo = 42

  /**
    * Generates a URI for a test media file.
    *
    * @param idx    the index of the test file
    * @param mapped flag whether the URI should be mapped
    * @return the new URI
    */
  private def createUri(idx: Int, mapped: Boolean): String =
    if (mapped) s"$MediumPath$SongPath/song$idx.mp3"
    else s"audio://$MediumPath$SongPath/song$idx.mp3"

  /**
    * Creates a meta data processing result object for the specified index.
    *
    * @param idx    the index
    * @param mapped flag whether the URI should be mapped
    * @return the test processing result
    */
  private def processingResult(idx: Int, mapped: Boolean): MetaDataProcessingSuccess =
    MetaDataProcessingSuccess(s"songs/song$idx.mp3", TestMediumID, createUri(idx, mapped),
      MediaMetaData(title = Some(s"Song$idx"), size = (idx + 1) * 100))

  /**
    * Creates a sequence with test meta data of the specified size.
    *
    * @param count  the number of meta data objects
    * @param mapped flag whether the URIs of files should be mapped
    * @return the sequence with the produced meta data
    */
  private def createProcessingResults(count: Int, mapped: Boolean):
  IndexedSeq[MetaDataProcessingSuccess] =
    (1 to count) map (processingResult(_, mapped))

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

  "A MetaDataResponseProcessingActor" should "handle a successful response" in {
    val metaDataResults = createProcessingResults(8, mapped = false)
    val mappedResults = createProcessingResults(8, mapped = true)
    val response = createResponse(generateJson(metaDataResults))
    val actor = system.actorOf(Props[MetaDataResponseProcessingActor])

    actor ! ProcessResponse(TestMediumID, null, response, DefaultArchiveConfig, SeqNo)
    val result = expectMsgType[MetaDataResponseProcessingResult]
    result.mediumID should be(TestMediumID)
    result.metaData should contain theSameElementsAs mappedResults
    result.seqNo should be(SeqNo)
  }

  it should "filter out results rejected by the URI mapper" in {
    val MaxIndex = 6
    val mapper = new UriMapper {
      /**
        * @inheritdoc This implementation rejects all test files with an index
        *             bigger than the specified maximum index.
        */
      override def mapUri(config: UriMappingSpec, mid: MediumID, uriOrg: String):
      Option[String] = {
        val extPos = uriOrg.indexOf(".mp3")
        val fileIdx = uriOrg.substring(extPos - 1, extPos).toInt
        if (fileIdx <= MaxIndex) super.mapUri(config, mid, uriOrg)
        else None
      }
    }
    val metaDataResults = createProcessingResults(8, mapped = false)
    val mappedResults = createProcessingResults(MaxIndex, mapped = true)
    val response = createResponse(generateJson(metaDataResults))
    val actor = system.actorOf(Props(classOf[MetaDataResponseProcessingActor], mapper))

    actor ! ProcessResponse(TestMediumID, null, response, DefaultArchiveConfig, SeqNo)
    val result = expectMsgType[MetaDataResponseProcessingResult]
    result.metaData should contain theSameElementsAs mappedResults
  }

  it should "apply a size restriction when processing a response" in {
    val response = createResponse(generateJson(createProcessingResults(32, mapped = false)))
    val actor = system.actorOf(Props[MetaDataResponseProcessingActor])

    actor ! ProcessResponse(TestMediumID, null, response,
      DefaultArchiveConfig.copy(maxContentSize = 1), SeqNo)
    expectMsgType[Status.Failure]
  }

  it should "allow cancellation of the current stream" in {
    val responseData = generateJson(createProcessingResults(64, mapped = false))
    val jsonStrings = responseData.grouped(64)
      .map(ByteString(_))
    val source = Source[ByteString](jsonStrings.toList).delay(1.second,
      DelayOverflowStrategy.backpressure)
    val actor = system.actorOf(Props(new MetaDataResponseProcessingActor {
      override def createResponseDataSource(mid: MediumID, response: HttpResponse,
                                            config: HttpArchiveConfig):
      Source[ByteString, Any] = source
    }))

    actor ! ProcessResponse(TestMediumID, null, createResponse(responseData),
      DefaultArchiveConfig, SeqNo)
    actor ! CancelStreams
    expectMsgType[MetaDataResponseProcessingResult]
  }

  it should "unregister kill switches after stream completion" in {
    val killSwitch = mock[KillSwitch]
    val Result = 42
    val props = Props(new MetaDataResponseProcessingActor {
      override protected def processSource(source: Source[ByteString, Any], mid: MediumID,
                                           desc: HttpMediumDesc, config: HttpArchiveConfig,
                                           seqNo: Int): (Future[Any], KillSwitch) =
        (Future.successful(Result), killSwitch)
    })
    val actor = TestActorRef[MetaDataResponseProcessingActor](props)
    actor ! ProcessResponse(TestMediumID, null,
      createResponse(generateJson(createProcessingResults(2, mapped = false))),
      DefaultArchiveConfig, SeqNo)
    expectMsg(Result)

    actor receive CancelStreams
    verify(killSwitch, never()).shutdown()
  }
}
