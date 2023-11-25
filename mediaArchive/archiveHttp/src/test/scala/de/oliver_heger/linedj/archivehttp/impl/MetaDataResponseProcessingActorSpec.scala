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

package de.oliver_heger.linedj.archivehttp.impl

import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess
import org.apache.pekko.actor.{ActorSystem, Props, Status}
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{DelayOverflowStrategy, KillSwitch}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.apache.pekko.util.{ByteString, Timeout}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.immutable.IndexedSeq
import scala.concurrent.Future
import scala.concurrent.duration._

object MetaDataResponseProcessingActorSpec:
  /** Path prefix for the test online archive. */
  private val ArchivePath = "/test/music"

  /** The relative path to the test medium. */
  private val MediumPath = "medium"

  /** The path to the test songs. */
  private val SongPath = "/artist/album"

  /** Test medium ID. */
  private val TestMediumID = MediumID("testMedium",
    Some(s"$ArchivePath/$MediumPath/test.settings"), "HTTPArchive")

  /** Test configuration for the archive. */
  private val DefaultArchiveConfig = HttpArchiveConfig(Uri("https://music.arc" + ArchivePath),
    "Test", processorCount = 3, processorTimeout = Timeout(2.seconds), maxContentSize = 256, propagationBufSize = 4,
    downloadConfig = null, downloadBufferSize = 100, downloadMaxInactivity = 1.minute,
    downloadReadChunkSize = 500, timeoutReadSize = 250, downloader = null, contentPath = Uri.Path(ArchivePath),
    mediaPath = Uri.Path("media"), metaDataPath = Uri.Path("mdt"))

  /** The sequence number used for requests. */
  private val SeqNo = 42

  /**
    * Generates a URI for a test media file.
    *
    * @param idx    the index of the test file
    * @return the new URI
    */
  private def createUri(idx: Int): MediaFileUri =
    MediaFileUri(s"$MediumPath$SongPath/song$idx.mp3")

  /**
    * Creates a meta data processing result object for the specified index.
    *
    * @param idx    the index
    * @return the test processing result
    */
  private def processingResult(idx: Int): MetaDataProcessingSuccess =
    MetaDataProcessingSuccess(TestMediumID, createUri(idx),
      MediaMetaData(title = Some(s"Song$idx"), size = (idx + 1) * 100))

  /**
    * Creates a sequence with test meta data of the specified size.
    *
    * @param count  the number of meta data objects
    * @return the sequence with the produced meta data
    */
  private def createProcessingResults(count: Int): IndexedSeq[MetaDataProcessingSuccess] =
    (1 to count) map ((idx: Int) => processingResult(idx))

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
       |"uri":"${data.uri.uri}"
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
    * Creates a ''Source'' with the data of the given entity string.
    *
    * @param data the data to be emitted by the source
    * @return the source producing this data
    */
  private def createDataSource(data: String): Source[ByteString, Any] =
    Source.single(ByteString(data))

/**
  * Test class for ''MetaDataResponseProcessingActor''.
  */
class MetaDataResponseProcessingActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:

  import MetaDataResponseProcessingActorSpec._

  def this() = this(ActorSystem("MetaDataResponseProcessingActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "A MetaDataResponseProcessingActor" should "handle a successful response" in:
    val metaDataResults = createProcessingResults(8)
    val source = createDataSource(generateJson(metaDataResults))
    val actor = system.actorOf(Props[MetaDataResponseProcessingActor]())

    actor ! ProcessResponse(TestMediumID, null, source, DefaultArchiveConfig, SeqNo)
    val result = expectMsgType[MetaDataResponseProcessingResult]
    result.mediumID should be(TestMediumID)
    result.metaData should contain theSameElementsAs metaDataResults
    result.seqNo should be(SeqNo)

  it should "apply a size restriction when processing a response" in:
    val source = createDataSource(generateJson(createProcessingResults(32)))
    val actor = system.actorOf(Props[MetaDataResponseProcessingActor]())

    actor ! ProcessResponse(TestMediumID, null, source,
      DefaultArchiveConfig.copy(maxContentSize = 1), SeqNo)
    expectMsgType[Status.Failure]

  it should "allow cancellation of the current stream" in:
    val responseData = generateJson(createProcessingResults(64))
    val jsonStrings = responseData.grouped(64)
      .map(ByteString(_))
    val source = Source[ByteString](jsonStrings.toList).delay(1.second,
      DelayOverflowStrategy.backpressure)
    val actor = system.actorOf(Props(new MetaDataResponseProcessingActor {
      override def createResponseDataSource(src: Source[ByteString, Any], config: HttpArchiveConfig):
      Source[ByteString, Any] = source
    }))

    actor ! ProcessResponse(TestMediumID, null, createDataSource(responseData),
      DefaultArchiveConfig, SeqNo)
    actor ! CancelStreams
    expectMsgType[MetaDataResponseProcessingResult]

  it should "unregister kill switches after stream completion" in:
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
      createDataSource(generateJson(createProcessingResults(2))),
      DefaultArchiveConfig, SeqNo)
    expectMsg(Result)

    actor receive CancelStreams
    verify(killSwitch, never()).shutdown()
