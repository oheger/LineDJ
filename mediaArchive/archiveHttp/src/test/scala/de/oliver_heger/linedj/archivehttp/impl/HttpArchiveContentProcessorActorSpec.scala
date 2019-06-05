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

import java.io.IOException

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Cookie, Location}
import akka.stream.DelayOverflowStrategy
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess
import org.apache.commons.configuration.PropertiesConfiguration
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.util.matching.Regex
import scala.util.{Failure, Random, Success, Try}

object HttpArchiveContentProcessorActorSpec {
  /** The sequence number of the test scan operation. */
  val SeqNo = 42

  /** Regular expression to parse the index from a setting path. */
  val RegExSettings: Regex = raw".*medium(\d+)/.+".r

  /** Regular expression to parse the index from a meta data path. */
  val RegExMetaData: Regex = raw".*/data_(\d+).mdt".r

  /** Name of a special cookie that causes an error response. */
  val ErrorCookie = "Error"

  /** A default URI mapping configuration for the archive content file. */
  private val ContentMappingConfig =
    HttpArchiveConfig.extractMappingConfig(new PropertiesConfiguration, "")

  /** A default configuration for the test archive. */
  private val DefaultArchiveConfig = RequestActorTestImpl.createTestArchiveConfig()
    .copy(contentMappingConfig = ContentMappingConfig)

  /** Constant for the URI pointing to the content file of the test archive. */
  val ArchiveUri: String = DefaultArchiveConfig.archiveURI.toString()

  /** Message indicating stream completion. */
  private val CompleteMessage = new Object

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
  def mediumID(mediumDesc: HttpMediumDesc): MediumID = {
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
  def createMediumDesc(idx: Int): HttpMediumDesc =
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
    HttpRequest(uri = Uri(path))

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
    * Creates a result object for a processed settings request.
    *
    * @param desc   the description for the medium affected
    * @param reqUri the URI of the request
    * @return the result for this settings request
    */
  def createSettingsProcessingResult(desc: HttpMediumDesc, reqUri: String):
  MediumInfoResponseProcessingResult = {
    val info = MediumInfo(mediumID = mediumID(desc), name = desc.mediumDescriptionPath,
      description = reqUri, orderMode = "", orderParams = "", checksum = "")
    MediumInfoResponseProcessingResult(info, SeqNo)
  }

  /**
    * Creates a result object for a processed meta data request.
    *
    * @param desc   the description for the medium affected
    * @param reqUri the URI of the request
    * @return the result for this meta data request
    */
  def createMetaDataProcessingResult(desc: HttpMediumDesc, reqUri: String):
  MetaDataResponseProcessingResult = {
    val mid = mediumID(desc)
    val data = List(MetaDataProcessingSuccess(mediumID = mid, uri = desc.metaDataPath,
      path = desc.metaDataPath, metaData = MediaMetaData(title = Some(reqUri))))
    MetaDataResponseProcessingResult(mid, data, SeqNo)
  }
}

/**
  * Test class for ''HttpArchiveContentProcessorActor''.
  */
class HttpArchiveContentProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

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
  private def expectResultsFor(results: List[MediumProcessingResult],
                               media: Iterable[HttpMediumDesc]): List[MediumProcessingResult] = {
    val expResults = media map { desc =>
      val infoResult = createSettingsProcessingResult(desc, desc.mediumDescriptionPath)
      val metaResult = createMetaDataProcessingResult(desc, desc.metaDataPath)
      MediumProcessingResult(infoResult.mediumInfo, metaResult.metaData, SeqNo)
    }
    results should contain only (expResults.toSeq: _*)
    results
  }

  "A HttpArchiveContentProcessorActor" should "process the content document" in {
    val descriptions = createMediumDescriptions(8)
    val mapping = createResponseMapping(descriptions)
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchive(descriptions, mapping, DefaultArchiveConfig)
      .expectProcessingResults(descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)
    expectNoMessage(100.millis)
  }

  it should "apply a content URI mapping" in {
    val Prefix = "test/"
    val config = DefaultArchiveConfig
      .copy(contentMappingConfig = DefaultArchiveConfig.contentMappingConfig
        .copy(uriTemplate = Prefix + HttpArchiveConfig.DefaultUriMappingTemplate))
    val descriptions = createMediumDescriptions(4)
    val mapping = createResponseMapping(descriptions.map { desc =>
      HttpMediumDesc(Prefix + desc.mediumDescriptionPath, Prefix + desc.metaDataPath)
    })
    val helper = new ContentProcessorActorTestHelper(checkProcessingMessages = false)

    val results = helper.processArchive(descriptions, mapping, config)
      .expectProcessingResults(descriptions.size)
    results foreach { res =>
      res.mediumInfo.description should startWith(Prefix)
    }
  }

  it should "handle an empty source" in {
    val helper = new ContentProcessorActorTestHelper

    helper.processArchive(List.empty, Map.empty, DefaultArchiveConfig)
      .expectProcessingComplete()
  }

  it should "filter out failures from the content URI mapping" in {
    val config = DefaultArchiveConfig
      .copy(contentMappingConfig = DefaultArchiveConfig.contentMappingConfig
        .copy(removePrefix = "nonExistingPrefix"))
    val descriptions = createMediumDescriptions(4)
    val mapping = createResponseMapping(descriptions)
    val helper = new ContentProcessorActorTestHelper

    helper.processArchive(descriptions, mapping, config)
      .expectProcessingComplete()
  }

  it should "respect timeouts from processor actors" in {
    val config = DefaultArchiveConfig.copy(processorTimeout = Timeout(50.millis))
    val descriptions = createMediumDescriptions(8)
    val successMapping = createResponseMapping(descriptions)
    val failedSettingsDesc = createMediumDesc(42)
    val mapping1 = successMapping + (createRequest(
      failedSettingsDesc.mediumDescriptionPath) -> HttpResponse()) +
      (createRequest(failedSettingsDesc.metaDataPath) ->
        createResponse(failedSettingsDesc.metaDataPath))
    val failedMetaDesc = createMediumDesc(49)
    val mapping = mapping1 + (createRequest(failedMetaDesc.mediumDescriptionPath) ->
      createResponse(failedMetaDesc.mediumDescriptionPath)) +
      (createRequest(failedMetaDesc.metaDataPath) -> HttpResponse())
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchive(failedMetaDesc :: failedSettingsDesc ::
      descriptions, mapping, config)
      .expectProcessingResults(descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)
  }

  it should "handle error responses from processor actors" in {
    def createErrorResponse(msg: String): HttpResponse = {
      val cookie = Cookie(ErrorCookie, msg)
      HttpResponse(headers = List(cookie))
    }

    val descriptions = createMediumDescriptions(8)
    val successMapping = createResponseMapping(descriptions)
    val failedSettingsDesc = createMediumDesc(42)
    val mapping1 = successMapping + (createRequest(
      failedSettingsDesc.mediumDescriptionPath) -> createErrorResponse("wrong_settings")) +
      (createRequest(failedSettingsDesc.metaDataPath) ->
        createResponse(failedSettingsDesc.metaDataPath))
    val failedMetaDesc = createMediumDesc(49)
    val mapping = mapping1 + (createRequest(failedMetaDesc.mediumDescriptionPath) ->
      createResponse(failedMetaDesc.mediumDescriptionPath)) +
      (createRequest(failedMetaDesc.metaDataPath) -> createErrorResponse("wrong_meta_data"))
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchive(failedMetaDesc :: failedSettingsDesc ::
      descriptions, mapping, DefaultArchiveConfig)
      .expectProcessingResults(descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)
  }

  it should "handle error responses from the request actor" in {
    val descriptions = createMediumDescriptions(8)
    val successMapping = RequestActorTestImpl.toMappingWithFailures(createResponseMapping(descriptions))
    val failedSettingsDesc = createMediumDesc(42)
    val mapping = successMapping + (createRequest(
      failedSettingsDesc.mediumDescriptionPath) -> Failure(new IOException("Boom"))) +
      (createRequest(failedSettingsDesc.metaDataPath) -> Success(createResponse(failedSettingsDesc.metaDataPath)))
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchiveWithFailureMapping(failedSettingsDesc :: descriptions,
      mapping, DefaultArchiveConfig)
      .expectProcessingResults(descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)
  }

  it should "ignore incomplete medium descriptions" in {
    val descriptions = List(HttpMediumDesc(settingsPath(1), null),
      HttpMediumDesc(null, metaDataPath(2)))
    val helper = new ContentProcessorActorTestHelper

    helper.processArchive(descriptions, Map.empty, DefaultArchiveConfig)
      .expectProcessingComplete()
  }

  it should "allow canceling the current stream" in {
    val descriptions = createMediumDescriptions(256)
    val mapping = createResponseMapping(descriptions)
    val source = Source(descriptions).delay(1.second, DelayOverflowStrategy.backpressure)
    val helper = new ContentProcessorActorTestHelper

    helper.processArchiveWithSource(source, RequestActorTestImpl toMappingWithFailures mapping, DefaultArchiveConfig)
      .cancelOperation()
      .fishForProcessingComplete()
  }

  it should "handle processing results in unexpected order" in {
    val random = new Random

    def createShuffledMapping(descs: Seq[HttpMediumDesc], f: HttpMediumDesc => String):
    Map[HttpRequest, HttpResponse] = {
      val requests = random.shuffle(descs map (d => createRequest(f(d))))
      val responses = random.shuffle(descs map (d => createResponse(f(d))))
      requests.zip(responses).toMap
    }

    val descriptions = createMediumDescriptions(16)
    val mapping = createShuffledMapping(descriptions, _.mediumDescriptionPath) ++
      createShuffledMapping(descriptions, _.metaDataPath)
    val helper = new ContentProcessorActorTestHelper(checkProcessingMessages = false)

    val results = helper.processArchive(descriptions, mapping, DefaultArchiveConfig)
      .expectProcessingResults(descriptions.size)
    expectResultsFor(results, descriptions)
  }

  /**
    * A test helper class managing a test actor instance and its dependencies.
    *
    * @param checkProcessingMessages flag whether the mock processing actors
    *                                should check the messages they receive
    */
  private class ContentProcessorActorTestHelper(checkProcessingMessages: Boolean = true) {
    /** The settings processor actor. */
    private val settingsProcessor =
      system.actorOf(Props(classOf[TestMediumInfoProcessingActor], checkProcessingMessages))

    /** The meta data processor actor. */
    private val metaDataProcessor =
      system.actorOf(Props(classOf[TestMetaDataProcessingActor], checkProcessingMessages))

    /** A test probe acting as sink for archive processing. */
    private val sinkProbe = TestProbe()

    /** The actor to be tested. */
    private val contentProcessorActor = system.actorOf(Props[HttpArchiveContentProcessorActor])

    /**
      * Simulates a processing operation on the test archive.
      *
      * @param media           the sequence with media data
      * @param responseMapping the response mapping
      * @param config          the configuration for the archive
      * @return this test helper
      */
    def processArchive(media: collection.immutable.Iterable[HttpMediumDesc],
                       responseMapping: Map[HttpRequest, HttpResponse],
                       config: HttpArchiveConfig):
    ContentProcessorActorTestHelper =
      processArchiveWithFailureMapping(media, RequestActorTestImpl.toMappingWithFailures(responseMapping), config)

    /**
      * Simulates a processing operation on the test archive with potential
      * errors sent by the request actor.
      *
      * @param media           the sequence with media data
      * @param responseMapping the response mapping
      * @param config          the configuration for the archive
      * @return this test helper
      */
    def processArchiveWithFailureMapping(media: collection.immutable.Iterable[HttpMediumDesc],
                                         responseMapping: Map[HttpRequest, Try[HttpResponse]],
                                         config: HttpArchiveConfig):
    ContentProcessorActorTestHelper =
      processArchiveWithSource(Source[HttpMediumDesc](media), responseMapping, config)

    /**
      * Simulates a processing operation on the test archive with the source
      * specified.
      *
      * @param source          the source for media data
      * @param responseMapping the response mapping
      * @param config          the configuration for the archive
      * @return this test helper
      */
    def processArchiveWithSource(source: Source[HttpMediumDesc, Any],
                                 responseMapping: Map[HttpRequest, Try[HttpResponse]],
                                 config: HttpArchiveConfig):
    ContentProcessorActorTestHelper = {
      val sink = Sink.actorRef(sinkProbe.ref, CompleteMessage)
      val msg = createProcessArchiveRequest(source, responseMapping, config, sink)
      contentProcessorActor ! msg
      this
    }

    /**
      * Creates a request to process an HTTP archive based on the passed in
      * parameters.
      *
      * @param source          the source to be processed
      * @param responseMapping the response mapping
      * @param config          the configuration for the archive
      * @param sink            the sink for accepting the data
      * @return the processing request
      */
    def createProcessArchiveRequest(source: Source[HttpMediumDesc, Any],
                                    responseMapping: Map[HttpRequest, Try[HttpResponse]],
                                    config: HttpArchiveConfig = DefaultArchiveConfig,
                                    sink: Sink[MediumProcessingResult, Any]):
    ProcessHttpArchiveRequest =
      ProcessHttpArchiveRequest(mediaSource = source,
        clientFlow = null, requestActor = createRequestActor(responseMapping),
        archiveConfig = config, settingsProcessorActor = settingsProcessor,
        metaDataProcessorActor = metaDataProcessor, sink = sink,
        seqNo = SeqNo, metaDataParallelism = 1, infoParallelism = 1)

    /**
      * Expects that the given number of processing results has been sent to
      * the manager actor and returns a list with all result messages.
      *
      * @param count the expected number of results
      * @return the list with received messages for further testing
      */
    def expectProcessingResults(count: Int): List[MediumProcessingResult] = {
      val results = (1 to count).foldLeft(List.empty[MediumProcessingResult])(
        (lst, _) => sinkProbe.expectMsgType[MediumProcessingResult] :: lst)
      results.reverse
    }

    /**
      * Expects the completion message of the processing operation.
      *
      * @return this test helper
      */
    def expectProcessingComplete(): ContentProcessorActorTestHelper = {
      sinkProbe.expectMsg(CompleteMessage)
      this
    }

    /**
      * Tries to find a processing complete message ignoring test results.
      *
      * @return this test helper
      */
    def fishForProcessingComplete(): ContentProcessorActorTestHelper = {
      sinkProbe.fishForMessage() {
        case _: MediumProcessingResult => false
        case CompleteMessage => true
      }
      this
    }

    /**
      * Sends a message to cancel the current stream operation to the test
      * actor.
      *
      * @return this test helper
      */
    def cancelOperation(): ContentProcessorActorTestHelper = {
      contentProcessorActor ! CancelStreams
      this
    }

    /**
      * Creates a simulated request actor and initializes it with the given
      * request-response mapping.
      *
      * @param mapping the mapping
      * @return the request actor reference
      */
    private def createRequestActor(mapping: Map[HttpRequest, Try[HttpResponse]]): ActorRef = {
      val requestActor = system.actorOf(RequestActorTestImpl())
      requestActor ! RequestActorTestImpl.InitRequestResponseMappingWithFailures(mapping)
      requestActor
    }
  }

}

/**
  * Abstract actor base class which simulates a processor of responses.
  *
  * The actor expects ''ProcessResponse'' messages. It extracts the location
  * header of the response to obtain the original request path. If no such
  * header can be found, the actor does nothing forcing a timeout. If the
  * response contains a cookie header with the name ''error'', a failure
  * response is returned with the error message obtained from the cookie value.
  * Otherwise, a dummy processing result is generated and sent to the sender.
  *
  * @param checkMsg flag whether incoming messages must be checked
  */
abstract class AbstractTestProcessorActor(checkMsg: Boolean) extends Actor {

  import HttpArchiveContentProcessorActorSpec._

  override def receive: Receive = {
    case ProcessResponse(mid, desc, resp, config, seqNo)
      if seqNo == HttpArchiveContentProcessorActorSpec.SeqNo &&
        config.archiveURI == Uri(ArchiveUri) =>
      if (!handleErrorCookie(resp)) {
        val optResponse = resp.header[Location]
          .flatMap(loc => createMediumDescFromLocation(loc.uri.toString()))
          .filter(d => !checkMsg || (d == desc && mid == mediumID(desc)))
          .map(desc => createResult(desc, resp.header[Location].get.uri.toString()))
        optResponse foreach sender.!
      }
  }

  /**
    * Creates the response to be returned for a successful request.
    *
    * @param desc   the medium description extracted from the response location
    * @param reqUri the URI of the original request
    * @return the object to be returned
    */
  protected def createResult(desc: HttpMediumDesc, reqUri: String): AnyRef

  /**
    * Extracts the index of the test medium from the given location URI.
    *
    * @param location the location header from the response
    * @return an ''Option'' with the index of the test medium
    */
  protected def extractLocationIndex(location: String): Option[Int]

  /**
    * Tries to extract the index from the given location string and creates a
    * corresponding medium description.
    *
    * @param location the location header from the response
    * @return an ''Option'' with the resulting medium description
    */
  private def createMediumDescFromLocation(location: String): Option[HttpMediumDesc] =
    extractLocationIndex(location) map createMediumDesc

  /**
    * Checks whether the response contains a special error cookie. If so, a
    * failure is sent back to the sender.
    *
    * @param response the response
    * @return '''true''' if a failure response was sent; '''false''' otherwise
    */
  private def handleErrorCookie(response: HttpResponse): Boolean =
    response.header[Cookie].flatMap(_.cookies.find(_.name == ErrorCookie)) match {
      case Some(pair) =>
        sender() ! akka.actor.Status.Failure(new IOException(pair.value))
        true
      case None => false
    }
}

/**
  * A concrete test processing actor that generates medium info results.
  */
class TestMediumInfoProcessingActor(checkMsg: Boolean)
  extends AbstractTestProcessorActor(checkMsg) {
  override protected def createResult(desc: HttpMediumDesc, reqUri: String): AnyRef =
    HttpArchiveContentProcessorActorSpec.createSettingsProcessingResult(desc, reqUri)

  override protected def extractLocationIndex(location: String): Option[Int] =
    location match {
      case HttpArchiveContentProcessorActorSpec.RegExSettings(idx) =>
        Some(idx.toInt)
      case _ =>
        None
    }
}

/**
  * A concrete test processing actor that generates meta data results.
  */
class TestMetaDataProcessingActor(checkMsg: Boolean)
  extends AbstractTestProcessorActor(checkMsg) {
  override protected def createResult(desc: HttpMediumDesc, reqUri: String): AnyRef =
    HttpArchiveContentProcessorActorSpec.createMetaDataProcessingResult(desc, reqUri)

  override protected def extractLocationIndex(location: String): Option[Int] =
    location match {
      case HttpArchiveContentProcessorActorSpec.RegExMetaData(idx) =>
        Some(idx.toInt)
      case _ => None
    }
}
