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

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, Location}
import akka.stream.{DelayOverflowStrategy, KillSwitch}
import akka.stream.scaladsl.{Flow, Source}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.archivecommon.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Try}

object HttpArchiveContentProcessorActorSpec {
  /** Constant for the root URI of the test archive. */
  val ArchiveRootUri = "https://test.music.archive.org/"

  /** Constant for the URI pointing to the content file of the test archive. */
  val ArchiveUri: String = ArchiveRootUri + "content.json"

  /** The sequence number of the test scan operation. */
  val SeqNo = 42

  /** Name of the settings processor actor. */
  private val SettingsProcessorName = "settingsProcessor"

  /** Name of the meta data processor actor. */
  private val MetaDataProcessorName = "metaDataProcessor"

  /** A default configuration for the test archive. */
  private val DefaultArchiveConfig = HttpArchiveConfig(Uri(ArchiveUri),
    UserCredentials("scott", "tiger"), 2, Timeout(10.seconds), 64)

  /**
    * Returns a test settings path for the specified index.
    *
    * @param idx the index
    * @return the test settings path for this index
    */
  private def settingsPath(idx: Int): String = "medium" + idx + "/playlist.settings"

  /**
    * Returns a test meta data path for the specified index.
    *
    * @param idx the index
    * @return the test meta data path for this index
    */
  private def metaDataPath(idx: Int): String = s"metadata/data_$idx.mdt"

  /**
    * Creates a medium ID from the given description object.
    *
    * @param mediumDesc the ''HttpMediumDesc''
    * @return the ''MediumID''
    */
  private def mediumID(mediumDesc: HttpMediumDesc): MediumID = {
    val idx = mediumDesc.mediumDescriptionPath lastIndexOf '/'
    MediumID(mediumDesc.mediumDescriptionPath.substring(0, idx),
      Some(mediumDesc.mediumDescriptionPath), ArchiveUri)
  }

  /**
    * Creates a test medium description with the specified index.
    *
    * @param idx the index
    * @return the test medium description
    */
  private def createMediumDesc(idx: Int): HttpMediumDesc =
    HttpMediumDesc(settingsPath(idx), metaDataPath(idx))

  /**
    * Generates the given number of medium description objects.
    *
    * @param count the number of objects to create
    * @return a sequence with the generated elements
    */
  private def createMediumDescriptions(count: Int): List[HttpMediumDesc] =
    (1 to count).map(createMediumDesc).toList

  /**
    * Creates a request for the specified path.
    *
    * @param path the path
    * @return the corresponding request
    */
  private def createRequest(path: String): HttpRequest =
    HttpRequest(uri = Uri(path),
      headers = List(Authorization(BasicHttpCredentials(DefaultArchiveConfig.credentials.userName,
        DefaultArchiveConfig.credentials.password))))

  /**
    * Creates a response for the specified path.
    *
    * @param path the path
    * @return the response for this path
    */
  private def createResponse(path: String, code: StatusCode = StatusCodes.OK): HttpResponse =
    HttpResponse(status = code, headers = List(Location(Uri(path))))

  /**
    * Adds request/response mappings for the specified medium description to
    * the specified map.
    *
    * @param mapping the mapping to be appended
    * @param desc    the description
    * @return the updated mapping
    */
  private def appendResponseMapping(mapping: Map[HttpRequest, HttpResponse],
                                    desc: HttpMediumDesc): Map[HttpRequest, HttpResponse] = {
    val mapSettings = createRequest(desc.mediumDescriptionPath) ->
      createResponse(desc.mediumDescriptionPath)
    val mapMetaData = createRequest(desc.metaDataPath) -> createResponse(desc.metaDataPath)
    mapping + mapSettings + mapMetaData
  }

  /**
    * Creates a request mapping that supports all requests for the specified
    * sequence of media descriptions.
    *
    * @param mediaList the sequence with media descriptions
    * @return a corresponding request/response mapping
    */
  private def createResponseMapping(mediaList: Iterable[HttpMediumDesc]):
  Map[HttpRequest, HttpResponse] =
    mediaList.foldLeft(Map.empty[HttpRequest, HttpResponse])((map, desc) =>
      appendResponseMapping(map, desc))

  /**
    * Creates a flow which simulates the HTTP request processing. Passed in
    * requests are directly mapped to response objects.
    *
    * @param mapping the mapping of supported requests
    * @return the request flow
    */
  private def createRequestFlow(mapping: Map[HttpRequest, HttpResponse]):
  Flow[(HttpRequest, RequestData), (Try[HttpResponse], RequestData), NotUsed] =
    Flow.fromFunction(i => (Try {
      mapping(i._1)
    }, i._2))

  /**
    * Creates a result object for a processed settings request.
    *
    * @param desc the description for the medium affected
    * @return the result for this settings request
    */
  private def createSettingsProcessingResult(desc: HttpMediumDesc): TestProcessingResult =
    TestProcessingResult(SettingsProcessorName, mediumID(desc), desc.mediumDescriptionPath)

  /**
    * Creates a result object for a processed meta data request.
    *
    * @param desc the description for the medium affected
    * @return the result for this meta data request
    */
  private def createMetaDataProcessingResult(desc: HttpMediumDesc): TestProcessingResult =
    TestProcessingResult(MetaDataProcessorName, mediumID(desc), desc.metaDataPath)
}

/**
  * Test class for ''HttpArchiveContentProcessorActor''.
  */
class HttpArchiveContentProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import HttpArchiveContentProcessorActorSpec._

  def this() = this(ActorSystem("HttpArchiveContentProcessorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Checks whether the specified set contains result objects for all of the
    * given medium descriptions.
    *
    * @param results the set with results
    * @param media   the medium descriptions
    * @return the same set with results
    */
  private def expectResultsFor(results: Set[TestProcessingResult],
                               media: Iterable[HttpMediumDesc]): Set[TestProcessingResult] = {
    media.foreach { md =>
      results should contain(createSettingsProcessingResult(md))
      results should contain(createMetaDataProcessingResult(md))
    }
    results
  }

  "A HttpArchiveContentProcessorActor" should "process the content document" in {
    val descriptions = createMediumDescriptions(8)
    val mapping = createResponseMapping(descriptions)
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchive(descriptions, mapping)
      .expectProcessingResults(2 * descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)
  }

  it should "handle an empty source" in {
    val helper = new ContentProcessorActorTestHelper

    helper.processArchive(List.empty, Map.empty)
      .expectProcessingComplete()
  }

  it should "drop failed responses and respect timeouts" in {
    val config = DefaultArchiveConfig.copy(processorTimeout = Timeout(50.millis))
    val descriptions = createMediumDescriptions(8)
    val successMapping = createResponseMapping(descriptions)
    val failedSettingsDesc = createMediumDesc(42)
    val mapping1 = successMapping + (createRequest(
      failedSettingsDesc.mediumDescriptionPath) ->
      createResponse(failedSettingsDesc.mediumDescriptionPath, StatusCodes.BadRequest)) +
      (createRequest(failedSettingsDesc.metaDataPath) ->
        createResponse(failedSettingsDesc.metaDataPath))
    val failedMetaDesc = createMediumDesc(49)
    val mapping = mapping1 + (createRequest(failedMetaDesc.mediumDescriptionPath) ->
      createResponse(failedMetaDesc.mediumDescriptionPath))
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchive(failedMetaDesc :: failedSettingsDesc ::
      descriptions, mapping, config)
      .expectProcessingResults(2 * descriptions.size + 2)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)
    results should contain allOf(createSettingsProcessingResult(failedMetaDesc),
      createMetaDataProcessingResult(failedSettingsDesc))
  }

  it should "ignore incomplete medium descriptions" in {
    val descriptions = List(HttpMediumDesc(settingsPath(1), null),
      HttpMediumDesc(null, metaDataPath(2)))
    val helper = new ContentProcessorActorTestHelper

    helper.processArchive(descriptions, Map.empty)
      .expectProcessingComplete()
  }

  it should "allow canceling the current stream" in {
    val descriptions = createMediumDescriptions(256)
    val mapping = createResponseMapping(descriptions)
    val helper = new ContentProcessorActorTestHelper
    val source = Source(descriptions).delay(1.second, DelayOverflowStrategy.backpressure)
    val actor = system.actorOf(Props[HttpArchiveContentProcessorActor])

    actor ! helper.createProcessArchiveRequest(source, mapping)
    actor ! CancelStreams
    helper.fishForProcessingComplete()
  }

  it should "unregister kill switches after stream completion" in {
    val killSwitch = mock[KillSwitch]
    val descriptions = createMediumDescriptions(256)
    val mapping = createResponseMapping(descriptions)
    val helper = new ContentProcessorActorTestHelper
    val props = Props(new HttpArchiveContentProcessorActor {
      override private[impl] def materializeStream(req: ProcessHttpArchiveRequest):
      (KillSwitch, Future[Done]) = (killSwitch, Future.successful(Done))
    })
    val actor = TestActorRef[HttpArchiveContentProcessorActor](props)
    actor ! helper.createProcessArchiveRequest(Source(descriptions), mapping)
    helper.fishForProcessingComplete()

    actor receive CancelStreams
    verify(killSwitch, never()).shutdown()
  }

  /**
    * A test helper class managing a test actor instance and its dependencies.
    */
  private class ContentProcessorActorTestHelper {
    /** Test probe for the manager actor. */
    private val manager = TestProbe()

    /** The settings processor actor. */
    private val settingsProcessor =
      system.actorOf(Props(classOf[TestProcessorActor], SettingsProcessorName))

    /** The meta data processor actor. */
    private val metaDataProcessor =
      system.actorOf(Props(classOf[TestProcessorActor], MetaDataProcessorName))

    /**
      * Creates a test actor and simulates a processing operation on the test
      * archive.
      *
      * @param media           the sequence with media data
      * @param responseMapping the response mapping
      * @param config          the configuration for the archive
      * @return this test helper
      */
    def processArchive(media: collection.immutable.Iterable[HttpMediumDesc],
                       responseMapping: Map[HttpRequest, HttpResponse],
                       config: HttpArchiveConfig = DefaultArchiveConfig):
    ContentProcessorActorTestHelper = {
      val source = Source[HttpMediumDesc](media)
      val msg = createProcessArchiveRequest(source, responseMapping, config)
      val actor = system.actorOf(Props[HttpArchiveContentProcessorActor])
      actor ! msg
      this
    }

    /**
      * Creates a request to process an HTTP archive based on the passed in
      * parameters.
      *
      * @param source          the source to be processed
      * @param responseMapping the response mapping
      * @param config          the configuration for the archive
      * @return the processing request
      */
    def createProcessArchiveRequest(source: Source[HttpMediumDesc, NotUsed],
                                    responseMapping: Map[HttpRequest, HttpResponse],
                                    config: HttpArchiveConfig = DefaultArchiveConfig):
    ProcessHttpArchiveRequest =
      ProcessHttpArchiveRequest(mediaSource = source,
        clientFlow = createRequestFlow(responseMapping),
        archiveConfig = config, settingsProcessorActor = settingsProcessor,
        metaDataProcessorActor = metaDataProcessor, archiveActor = manager.ref,
        seqNo = SeqNo)

    /**
      * Expects that the given number of processing results has been sent to
      * the manager actor and returns a test with all result messages.
      *
      * @param count the expected number of results
      * @return the set with received messages for further testing
      */
    def expectProcessingResults(count: Int): Set[TestProcessingResult] =
      (1 to count).foldLeft(Set.empty[TestProcessingResult])(
        (s, _) => s + manager.expectMsgType[TestProcessingResult])

    /**
      * Expects the completion message of the processing operation.
      *
      * @return this test helper
      */
    def expectProcessingComplete(): ContentProcessorActorTestHelper = {
      manager.expectMsg(HttpArchiveProcessingComplete(SeqNo))
      this
    }

    /**
      * Tries to find a processing complete message ignoring test results.
      *
      * @return this test helper
      */
    def fishForProcessingComplete(): ContentProcessorActorTestHelper = {
      manager.fishForMessage() {
        case _: TestProcessingResult => false
        case HttpArchiveProcessingComplete(SeqNo) => true
      }
      this
    }
  }

}

/**
  * A class that simulates the response of a processor actor.
  *
  * @param actorName the name of the processing actor
  * @param mediumID  the medium ID
  * @param path      the path of the request
  */
case class TestProcessingResult(actorName: String, mediumID: MediumID, path: String)

/**
  * Tests actor which simulates a processor of responses.
  *
  * The actor expects ''ProcessResponse'' messages. It extracts the location
  * header of the response to obtain the original request path. If the
  * response is not successful, the actor does nothing forcing a timeout.
  *
  * @param name the actor name
  */
class TestProcessorActor(name: String) extends Actor {
  override def receive: Receive = {
    case ProcessResponse(mid, resp, config, seqNo)
      if seqNo == HttpArchiveContentProcessorActorSpec.SeqNo =>
      resp match {
        case Success(r) if r.status.isSuccess() &&
          config.archiveURI == Uri(HttpArchiveContentProcessorActorSpec.ArchiveUri) =>
          sender ! TestProcessingResult(name, mid, r.header[Location].get.uri.toString())
        case _ => // ignore which leads to timeout
      }
  }
}
