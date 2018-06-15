/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp

import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.ForwardTestActor
import de.oliver_heger.linedj.ForwardTestActor.ForwardedMessage
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.impl._
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadManagementActor
import de.oliver_heger.linedj.archivehttp.impl.io.{FailedRequestException, HttpFlowFactory, HttpRequestSupport}
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved, MediaContribution, MetaDataProcessingSuccess}
import de.oliver_heger.linedj.utils.{ChildActorFactory, SystemPropertyAccess}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

object HttpArchiveManagementActorSpec {
  /** URI to the test music archive. */
  private val ArchiveURIStr = "https://my.music.la/content.json"

  /** Constant for an archive name. */
  private val ArchiveName = "MyMusic"

  /** The test archive configuration. */
  private val ArchiveConfig = createArchiveConfig()

  /** The request to the test archive. */
  private val ArchiveRequest = createRequest()

  /** Class for the content processor actor. */
  private val ClsContentProcessor = classOf[HttpArchiveContentProcessorActor]

  /** Class for the medium info processor actor. */
  private val ClsMediumInfoProcessor = classOf[MediumInfoResponseProcessingActor]

  /** Class for the meta data processor actor. */
  private val ClsMetaDataProcessor = classOf[MetaDataResponseProcessingActor]

  /** Class for the download management actor. */
  private val ClsDownloadManagementAactor =
    HttpDownloadManagementActor(null, null, null, null).actorClass()

  /**
    * Creates a test configuration for a media archive.
    *
    * @return the test configuration
    */
  private def createArchiveConfig(): HttpArchiveConfig =
    HttpArchiveConfig(archiveURI = Uri(ArchiveURIStr), processorCount = 2,
      maxContentSize = 256, processorTimeout = Timeout(1.minute),
      credentials = UserCredentials("scott", "tiger"), downloadConfig = null,
      downloadBufferSize = 1024, downloadMaxInactivity = 1.minute,
      downloadReadChunkSize = 4000, timeoutReadSize = 2222, archiveName = ArchiveName,
      mappingConfig = null)

  /**
    * Checks that no further messages have been sent to the specified test
    * probe.
    *
    * @param probe the test probe
    */
  private def expectNoMoreMsg(probe: TestProbe): Unit = {
    val msg = new Object
    probe.ref ! msg
    probe.expectMsg(msg)
  }

  /**
    * Creates a list with test medium descriptions.
    *
    * @return the list with test descriptions
    */
  private def createMediumDescriptions(): List[HttpMediumDesc] =
    (1 to 4).map(i => HttpMediumDesc("descPath" + i, "metaDataPath" + i)).toList

  /**
    * Returns a JSON representation for a medium description.
    *
    * @param md the description
    * @return the JSON representation
    */
  private def createMediumDescriptionJson(md: HttpMediumDesc): String =
    s"""{ "mediumDescriptionPath": "${md.mediumDescriptionPath}",
       |"metaDataPath": "${md.metaDataPath}"}
   """.stripMargin

  /**
    * Generates a JSON representation of the test medium descriptions.
    *
    * @return the JSON description
    */
  private def createMediumDescriptionsJson(): String =
    createMediumDescriptions().map(md => createMediumDescriptionJson(md))
      .mkString("[", ", ", "]")

  /**
    * Creates a request to the content file of the test archive.
    *
    * @return the request to the test archive
    */
  private def createRequest(): HttpRequest =
    HttpRequest(uri = ArchiveConfig.archiveURI,
      headers = List(Authorization(BasicHttpCredentials(ArchiveConfig.credentials.userName,
        ArchiveConfig.credentials.password))))

  /**
    * Creates a response object for a successful request to the content
    * document of the test archive.
    *
    * @return the success response
    */
  private def createSuccessResponse(): HttpResponse =
    HttpResponse(status = StatusCodes.OK, entity = createMediumDescriptionsJson())

  /**
    * Generates a test medium ID.
    *
    * @param idx the index of the medium
    * @return the test medium ID
    */
  private def mediumID(idx: Int): MediumID =
    MediumID("medium" + idx, Some(s"playlist$idx.settings"), ArchiveURIStr)

  /**
    * Creates a medium info object for a test medium.
    *
    * @param idx the index of the test medium
    * @return the info object for this medium
    */
  private def mediumInfo(idx: Int): MediumInfo =
    MediumInfo(mediumID = mediumID(idx), name = "Medium" + idx, description = "test medium",
      orderMode = null, orderParams = null, checksum = idx.toString)

  /**
    * Creates a result for a medium info processing operation.
    *
    * @param idx   the index of the test medium
    * @param seqNo the sequence number
    * @return the processing result for this medium
    */
  private def mediumInfoResult(idx: Int, seqNo: Int): MediumInfoResponseProcessingResult =
    MediumInfoResponseProcessingResult(mediumInfo(idx), seqNo)

  /**
    * Creates a number of medium info processing results.
    *
    * @param seqNo   the sequence number for the results
    * @param indices the indices of the media
    * @return a sequence with the results
    */
  private def createInfoResults(seqNo: Int, indices: Int*):
  Seq[MediumInfoResponseProcessingResult] =
    indices map (mediumInfoResult(_, seqNo))

  /**
    * Creates a meta data processing result object.
    *
    * @param medium the index of the medium
    * @param song   the index of the song
    * @return the processing result object
    */
  private def metaDataResult(medium: Int, song: Int): MetaDataProcessingSuccess = {
    val metaData = MediaMetaData(title = Some(s"Song $song of $medium"),
      size = 1000 + song * 100)
    val mid = mediumID(medium)
    MetaDataProcessingSuccess(path = mid.mediumURI + "/song" + song,
      uri = s"song://${mid.mediumURI}/song$song.mp3", mediumID = mid, metaData = metaData)
  }

  /**
    * Creates a processing result of a meta data request.
    *
    * @param medium    the index of the medium
    * @param songCount the number of songs in the result
    * @param seqNo     the sequence number
    * @return the processing result
    */
  private def metaDataProcessingResult(medium: Int, songCount: Int, seqNo: Int):
  MetaDataResponseProcessingResult = {
    val metaData = (1 to songCount).map(metaDataResult(medium, _))
    MetaDataResponseProcessingResult(mediumID(medium), metaData, seqNo)
  }

  /**
    * Creates a number of meta data processing results for the specified
    * media.
    *
    * @param seqNo   the sequence number
    * @param indices the indices of the media
    * @return a sequence with the results
    */
  private def createMetaDataProcessingResults(seqNo: Int, indices: Int*):
  Seq[MetaDataResponseProcessingResult] =
    indices.map(i => metaDataProcessingResult(i, i + 1, seqNo))
}

/**
  * Test class for ''HttpArchiveManagementActor''.
  */
class HttpArchiveManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import HttpArchiveManagementActorSpec._

  def this() = this(ActorSystem("HttpArchiveManagementActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A HttpArchiveManagementActor" should "create correct Props" in {
    val mediaManager = TestProbe()
    val metaManager = TestProbe()
    val monitoringActor = TestProbe()
    val removeActor = TestProbe()
    val pathGenerator = new TempPathGenerator(Paths get "temp")
    val props = HttpArchiveManagementActor(ArchiveConfig, pathGenerator, mediaManager.ref,
      metaManager.ref, monitoringActor.ref, removeActor.ref)
    classOf[HttpArchiveManagementActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpFlowFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpRequestSupport[RequestData]].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(ArchiveConfig, pathGenerator, mediaManager.ref, metaManager.ref,
      monitoringActor.ref, removeActor.ref))
  }

  it should "pass a process request to the content processor actor" in {
    val helper = new HttpArchiveManagementActorTestHelper

    val request = helper.triggerScan().expectProcessingRequest()
    request.archiveConfig should be(ArchiveConfig)
    //TODO handle correct sink
    //request.archiveActor should be(helper.manager)
    request.settingsProcessorActor should be(helper.probeMediumInfoProcessor.ref)
    request.metaDataProcessorActor should be(helper.probeMetaDataProcessor.ref)
    implicit val mat: ActorMaterializer = ActorMaterializer()

    val futureDescriptions = request.mediaSource.runFold(
      List.empty[HttpMediumDesc])((lst, md) => md :: lst)
    val descriptions = Await.result(futureDescriptions, 5.seconds)
    val expDescriptions = createMediumDescriptions()
    descriptions should contain only (expDescriptions: _*)
  }

  it should "not send a process request for a response error" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.initArchiveResponse(Future.failed(new Exception)).triggerScan()
      .expectNoProcessingRequest()
  }

  it should "recover from an error, so that a new request can be processed" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.initArchiveResponse(Future.failed(new Exception)).triggerScan()
      .initArchiveResponse(null).triggerScan()
      .expectProcessingRequest()
    helper.expectNoMoreUnionArchiveInteraction()
  }

  it should "send data for processing results to the union archive" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request.seqNo, 1, 2, 3, 4)
    val metaResults = createMetaDataProcessingResults(request.seqNo, 1, 2, 3, 4)

    helper
      .sendMessages(infoResults)
      .sendMessages(metaResults)
      .sendProcessingComplete(request.seqNo)
      .expectAddMedia(infoResults: _*)
      .expectMediaContribution(metaResults: _*)
      .expectMetaData(metaResults: _*)
      .expectNoMoreUnionArchiveInteraction()
  }

  it should "ignore incomplete media when sending results to the union archive" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request.seqNo, 3, 4, 5)
    val metaResults = createMetaDataProcessingResults(request.seqNo, 3, 4, 5)
    val extraInfoResults = createInfoResults(request.seqNo, 1, 2)
    val extraMetaResults = createMetaDataProcessingResults(request.seqNo, 6, 7, 8)

    helper
      .sendMessages(metaResults)
      .sendMessages(extraInfoResults)
      .sendMessages(extraMetaResults)
      .sendMessages(infoResults)
      .sendProcessingComplete(request.seqNo)
      .expectAddMedia(infoResults: _*)
      .expectMediaContribution(metaResults: _*)
      .expectMetaData(metaResults: _*)
      .expectNoMoreUnionArchiveInteraction()
  }

  it should "not send data to the union archive if there are no results" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request.seqNo, 1, 2)
    val metaResults = createMetaDataProcessingResults(request.seqNo, 3, 4)

    helper
      .sendMessages(infoResults)
      .sendMessages(metaResults)
      .sendProcessingComplete(request.seqNo)
      .expectNoMoreUnionArchiveInteraction()
  }

  it should "reset temporary data after a scan is completed" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults1 = createInfoResults(request.seqNo, 1, 2)
    val metaResults1 = createMetaDataProcessingResults(request.seqNo, 3, 4)
    helper
      .sendMessages(infoResults1).sendMessages(metaResults1)
      .sendProcessingComplete(request.seqNo)

    val request2 = helper.triggerScan().expectProcessingRequest()
    val infoResults2 = createInfoResults(request2.seqNo, 3, 4)
    val metaResults2 = createMetaDataProcessingResults(request2.seqNo, 1, 2)
    helper
      .sendMessages(infoResults2).sendMessages(metaResults2)
      .sendProcessingComplete(request2.seqNo)
      .expectNoMoreUnionArchiveInteraction()
  }

  it should "ignore another scan request while a scan is in progress" in {
    val helper = new HttpArchiveManagementActorTestHelper
    helper.triggerScan().expectProcessingRequest()

    helper.triggerScan()
      .expectNoProcessingRequest()
  }

  it should "reset the scan in progress flag on completion" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()

    helper.sendProcessingComplete(request.seqNo)
      .triggerScan()
      .expectProcessingRequest()
  }

  it should "send a removed message to the union actor when starting a new scan" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request.seqNo, 1, 2)
    val metaResults = createMetaDataProcessingResults(request.seqNo, 1, 2)
    helper
      .sendMessages(infoResults).sendMessages(metaResults)
      .sendProcessingComplete(request.seqNo)
      .expectAddMedia(infoResults: _*)
      .expectMediaContribution(metaResults: _*)
      .expectMetaData(metaResults: _*)

    helper.triggerScan()
    helper.probeUnionMediaManager.expectMsg(ArchiveComponentRemoved(ArchiveURIStr))
  }

  it should "only send a remove message if data was added to the union archive" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request.seqNo, 1, 2)
    val metaResults = createMetaDataProcessingResults(request.seqNo, 1, 2)
    helper
      .sendMessages(infoResults).sendMessages(metaResults)
      .sendProcessingComplete(request.seqNo)
      .expectAddMedia(infoResults: _*)
      .expectMediaContribution(metaResults: _*)
      .expectMetaData(metaResults: _*)

    val request2 = helper.triggerScan().expectProcessingRequest()
    helper.sendProcessingComplete(request2.seqNo)
    helper.probeUnionMediaManager.expectMsg(ArchiveComponentRemoved(ArchiveURIStr))

    helper.triggerScan()
    expectNoMoreMsg(helper.probeUnionMediaManager)
  }

  it should "ignore processing results from an outdated scan" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request.seqNo, 1, 2)
    val metaResults = createMetaDataProcessingResults(request.seqNo, 1, 2)
    val request2 = helper.sendProcessingComplete(request.seqNo)
      .triggerScan().expectProcessingRequest()

    helper.sendMessages(infoResults).sendMessages(metaResults)
      .sendProcessingComplete(request2.seqNo)
    expectNoMoreMsg(helper.probeUnionMediaManager)
  }

  it should "ignore a completion message from an outdated scan" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val request2 = helper.sendProcessingComplete(request.seqNo)
      .triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request2.seqNo, 1, 2)
    val metaResults = createMetaDataProcessingResults(request2.seqNo, 1, 2)

    helper.sendMessages(infoResults).sendMessages(metaResults)
      .sendProcessingComplete(request.seqNo)
    expectNoMoreMsg(helper.probeUnionMediaManager)
    helper.sendProcessingComplete(request2.seqNo)
      .expectAddMedia(infoResults: _*)
  }

  it should "handle and propagate a cancel message" in {
    val helper = new HttpArchiveManagementActorTestHelper
    helper.triggerScan().expectProcessingRequest()

    helper post CloseRequest
    expectMsg(CloseAck(helper.manager))
    helper.probeContentProcessor.expectMsg(CancelStreams)
    helper.probeMediumInfoProcessor.expectMsg(CancelStreams)
    helper.probeMetaDataProcessor.expectMsg(CancelStreams)
  }

  it should "correctly complete the current scan when it is canceled" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request.seqNo, 1, 2)
    val metaResults = createMetaDataProcessingResults(request.seqNo, 1, 2)
    helper.sendMessages(infoResults).sendMessages(metaResults)
      .post(CloseRequest)
    expectMsgType[CloseAck]
    helper.probeContentProcessor.expectMsg(CancelStreams)

    val request2 = helper.triggerScan().expectProcessingRequest()
    helper.sendProcessingComplete(request2.seqNo)
    expectNoMoreMsg(helper.probeUnionMediaManager)
  }

  it should "forward a file download request to the download manager" in {
    val request = MediumFileRequest(MediaFileID(MediumID("aMedium", None), "fileUri"),
      withMetaData = true)
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(request)
    expectMsg(ForwardedMessage(request))
  }

  it should "return the correct initial state" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.checkArchiveState(HttpArchiveStateDisconnected)
  }

  it should "return a success state if it is ready" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()

    helper.sendProcessingComplete(request.seqNo)
      .checkArchiveState(HttpArchiveStateConnected)
  }

  it should "switch to a success state as soon as info data comes in" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val infoResults = createInfoResults(request.seqNo, 1)

    helper.sendMessages(infoResults)
      .checkArchiveState(HttpArchiveStateConnected)
  }

  it should "switch to a success state as soon as meta data comes in" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    val metaResults = createMetaDataProcessingResults(request.seqNo, 1)

    helper.sendMessages(metaResults)
      .checkArchiveState(HttpArchiveStateConnected)
  }

  it should "not answer a state request while loading data" in {
    implicit val timeout: Timeout = Timeout(100.millis)
    val helper = new HttpArchiveManagementActorTestHelper
    helper.triggerScan()

    val futState = helper.manager ? HttpArchiveStateRequest
    intercept[AskTimeoutException] {
      Await.result(futState, 10.seconds)
    }
  }

  it should "answer a state request when the state becomes available" in {
    val probe = TestProbe()
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()

    helper.manager.tell(HttpArchiveStateRequest, probe.ref)
    helper.manager ! HttpArchiveStateRequest
    helper.manager.tell(HttpArchiveStateRequest, probe.ref)
    helper.sendProcessingComplete(request.seqNo)
    val expState = HttpArchiveStateResponse(ArchiveName, HttpArchiveStateConnected)
    expectMsg(expState)
    probe.expectMsg(expState)
    expectNoMoreMsg(probe) // check that message was sent only once
  }

  it should "reset the set of pending archive state clients" in {
    val probe = TestProbe()
    val helper = new HttpArchiveManagementActorTestHelper
    val request = helper.triggerScan().expectProcessingRequest()
    helper.manager.tell(HttpArchiveStateRequest, probe.ref)
    helper.sendProcessingComplete(request.seqNo)
    probe.expectMsgType[HttpArchiveStateResponse]

    val request2 = helper.triggerScan().expectProcessingRequest()
    helper.sendProcessingComplete(request2.seqNo)
    expectNoMoreMsg(probe)
  }

  it should "return a server error state if the server could not be contacted" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val exception = new Exception("Server not reachable!")

    helper.initArchiveResponse(Future.failed(exception))
      .triggerScan()
      .checkArchiveState(HttpArchiveStateServerError(exception))
  }

  it should "return a request failed state if the request was not successful" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val status = StatusCodes.Unauthorized
    val response = HttpResponse(status = status)

    helper.initArchiveResponse(Future.failed(FailedRequestException(response)))
      .triggerScan()
      .checkArchiveState(HttpArchiveStateFailedRequest(status))
  }

  it should "forward a DownloadActorAlive notification to the monitoring actor" in {
    val aliveMsg = DownloadActorAlive(TestProbe().ref,
      MediumID("testMediumURI", Some("settings.set")))
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(aliveMsg)
      .expectDownloadMonitoringMessage(aliveMsg)
  }

  /**
    * A test helper class managing all dependencies of a test actor instance.
    */
  private class HttpArchiveManagementActorTestHelper {
    /** Test probe for the union media manager actor. */
    val probeUnionMediaManager = TestProbe()

    /** Test probe for the union meta data manager actor. */
    val probeUnionMetaDataManager = TestProbe()

    /** Test probe for the content processor actor. */
    val probeContentProcessor = TestProbe()

    /** Test probe for the meta data processor actor. */
    val probeMetaDataProcessor = TestProbe()

    /** Test probe for the medium info processor actor. */
    val probeMediumInfoProcessor = TestProbe()

    /** Test probe for the monitoring actor. */
    private val probeMonitoringActor = TestProbe()

    /** Test probe for the remove file actor. */
    private val probeRemoveActor = TestProbe()

    /** Mock for the temp path generator. */
    private val pathGenerator = mock[TempPathGenerator]

    /** Mock for the HTTP flow returned by the mock factory implementation. */
    private val httpFlow = createTestHttpFlow[RequestData]()

    /** The actor simulating the download management actor. */
    private val downloadManagementActor = ForwardTestActor()

    /** The actor to be tested. */
    val manager: TestActorRef[HttpArchiveManagementActor] = createTestActor()

    /**
      * Stores a future response to be returned for a request of the archive's
      * content file.
      */
    private val archiveResponse = new AtomicReference[Future[(HttpResponse, RequestData)]]

    /**
      * Sends a message directly to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def send(msg: Any): HttpArchiveManagementActorTestHelper = {
      manager receive msg
      this
    }

    /**
      * Sends a message via the ! method to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def post(msg: Any): HttpArchiveManagementActorTestHelper = {
      manager ! msg
      this
    }

    /**
      * Sends a sequence of messages to the test actor.
      *
      * @param msgs the sequence of messages
      * @return this test heper
      */
    def sendMessages(msgs: Iterable[_]): HttpArchiveManagementActorTestHelper = {
      msgs.foreach(send)
      this
    }

    /**
      * Sends a message to start a new scan to the test actor.
      *
      * @return this test helper
      */
    def triggerScan(): HttpArchiveManagementActorTestHelper =
      send(ScanAllMedia)

    /**
      * Sends a message to the test actor that the processing is now
      * complete.
      *
      * @param seqNo the current sequence number
      * @return this test helper
      */
    def sendProcessingComplete(seqNo: Int): HttpArchiveManagementActorTestHelper = {
      send(HttpArchiveProcessingComplete(seqNo))
    }

    /**
      * Expects that a request to process the archive has been sent to the
      * content processor actor and returns the message.
      *
      * @return the request message
      */
    def expectProcessingRequest(): ProcessHttpArchiveRequest =
      probeContentProcessor.expectMsgType[ProcessHttpArchiveRequest]

    /**
      * Expects that no processing request is sent to the content processor
      * actor.
      *
      * @return this test helper
      */
    def expectNoProcessingRequest(): HttpArchiveManagementActorTestHelper = {
      probeContentProcessor.expectNoMessage(1.second)
      this
    }

    /**
      * Expects that an ''AddMedia'' message was sent based on the given
      * result objects.
      *
      * @param infoResults the processing result objects
      * @return this test helper
      */
    def expectAddMedia(infoResults: MediumInfoResponseProcessingResult*):
    HttpArchiveManagementActorTestHelper = {
      val media = infoResults.map(r => (r.mediumInfo.mediumID, r.mediumInfo)).toMap
      probeUnionMediaManager.expectMsg(AddMedia(media, ArchiveURIStr, None))
      this
    }

    /**
      * Expects that a media contribution message was sent based on the given
      * result objects.
      *
      * @param results the processing result objects
      * @return this test helper
      */
    def expectMediaContribution(results: MetaDataResponseProcessingResult*):
    HttpArchiveManagementActorTestHelper = {
      val files = results.map { r =>
        (r.mediumID, r.metaData.map(m => FileData(m.path, m.metaData.size)))
      }
      probeUnionMetaDataManager.expectMsg(MediaContribution(files.toMap))
      this
    }

    /**
      * Expects that meta data processing result messages are sent to the meta
      * data manager actor for all of the specified result objects.
      *
      * @param results the processing result objects
      * @return this test helper
      */
    def expectMetaData(results: MetaDataResponseProcessingResult*):
    HttpArchiveManagementActorTestHelper = {
      val metaData = results.flatMap(_.metaData).toSet
      val receivedData = (1 to metaData.size).foldLeft(
        Set.empty[MetaDataProcessingSuccess])((s, _) =>
        s + probeUnionMetaDataManager.expectMsgType[MetaDataProcessingSuccess])
      receivedData should be(metaData)
      this
    }

    /**
      * Expects that no more messages are sent to actors of the union
      * archive.
      *
      * @return this test helper
      */
    def expectNoMoreUnionArchiveInteraction(): HttpArchiveManagementActorTestHelper = {
      expectNoMoreMsg(probeUnionMediaManager)
      expectNoMoreMsg(probeUnionMetaDataManager)
      this
    }

    /**
      * Allows setting an explicit response to be returned by the request to
      * the HTTP archive's content file. If no response was set, a successful
      * response is generated automatically.
      *
      * @param response the future with the response
      * @return this test helper
      */
    def initArchiveResponse(response: Future[(HttpResponse, RequestData)]):
    HttpArchiveManagementActorTestHelper = {
      archiveResponse.set(response)
      this
    }

    /**
      * Queries the current state from the test archive and compares it with
      * the expected state.
      *
      * @param expState the expected state
      * @return this test helper
      */
    def checkArchiveState(expState: HttpArchiveState): HttpArchiveManagementActorTestHelper = {
      post(HttpArchiveStateRequest)
      val stateResponse = expectMsgType[HttpArchiveStateResponse]
      stateResponse.archiveName should be(ArchiveName)
      stateResponse.state should be(expState)
      this
    }

    /**
      * Expects a message to be passed to the download monitoring actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectDownloadMonitoringMessage(msg: Any): HttpArchiveManagementActorTestHelper = {
      probeMonitoringActor.expectMsg(msg)
      this
    }

    /**
      * Creates the test actor.
      *
      * @return the test actor
      */
    private def createTestActor(): TestActorRef[HttpArchiveManagementActor] =
      TestActorRef(createProps())

    /**
      * Creates the properties for the test actor.
      *
      * @return creation Props for the test actor
      */
    private def createProps(): Props =
      Props(new HttpArchiveManagementActor(ArchiveConfig, pathGenerator,
        probeUnionMediaManager.ref, probeUnionMetaDataManager.ref,
        probeMonitoringActor.ref, probeRemoveActor.ref)
        with ChildActorFactory with HttpFlowFactory with SystemPropertyAccess
        with HttpRequestSupport[RequestData] {
        override def createHttpFlow[T](uri: Uri)(implicit mat: Materializer, system: ActorSystem):
        Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] = {
          uri should be(ArchiveConfig.archiveURI)
          system should be(testSystem)
          mat should not be null
          httpFlow.asInstanceOf[Flow[(HttpRequest, T), (Try[HttpResponse], T), Any]]
        }

        /**
          * @inheritdoc This implementation returns a test response.
          */
        override def sendRequest(request: HttpRequest, data: RequestData, flow: Flow[
          (HttpRequest, RequestData), (Try[HttpResponse], RequestData), Any])
                                (implicit mat: Materializer, ec: ExecutionContext):
        Future[(HttpResponse, RequestData)] = {
          mat should not be null
          ec should not be null
          data.mediumDesc should be(null)
          request should be(ArchiveRequest)
          flow should be(httpFlow)
          val resp = archiveResponse.get()
          if(resp != null) resp else Future((createSuccessResponse(), data))
        }

        /**
          * @inheritdoc Checks creation properties and returns test probes for
          *             the child actors
          */
        override def createChildActor(p: Props): ActorRef =
          p.actorClass() match {
            case ClsContentProcessor =>
              p.args should have size 0
              probeContentProcessor.ref

            case ClsMediumInfoProcessor =>
              p.args should have size 0
              probeMediumInfoProcessor.ref

            case ClsMetaDataProcessor =>
              p.args should have size 0
              probeMetaDataProcessor.ref

            case ClsDownloadManagementAactor =>
              p.args should be(List(ArchiveConfig, pathGenerator, probeMonitoringActor.ref,
                probeRemoveActor.ref))
              downloadManagementActor
          }
      })

    /**
      * Creates the test HTTP flow. This flow is not actually invoked; so only
      * a dummy is returned.
      *
      * @return the test HTTP flow
      */
    private def createTestHttpFlow[T](): Flow[(HttpRequest, T), (Try[HttpResponse], T), NotUsed] = {
      Flow.fromFunction[(HttpRequest, T), (Try[HttpResponse], T)] { req =>
        (Try(HttpResponse()), req._2)
      }
    }
  }

}
