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

import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.io.MediaDownloader
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import de.oliver_heger.linedj.shared.archive.union.MetadataProcessingSuccess
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.stream.DelayOverflowStrategy
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.apache.pekko.util.{ByteString, Timeout}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Random, Success, Try}

object HttpArchiveContentProcessorActorSpec:
  /** The sequence number of the test scan operation. */
  final val SeqNo = 42

  /** Regular expression to parse the index from a setting path. */
  final val RegExSettings: Regex = raw".*medium(\d+)/.+".r

  /** Regular expression to parse the index from a metadata path. */
  final val RegExMetadata: Regex = raw".*/data_(\d+).mdt".r

  /** A prefix indicating an error response. */
  final val ErrorPrefix = "Error:"

  /** The relative base path for the media files of the test archive. */
  private val MediaPath = Uri.Path("media")

  /** The relative base path for the metadata files of the test archive. */
  private val MetadataPath = Uri.Path("meta")

  /** A default configuration for the test archive. */
  private val DefaultArchiveConfig =
    HttpArchiveConfig(archiveBaseUri = "https://some.archive.org" + "/data" + "/" + "archiveContent.json",
      archiveName = "test", processorCount = 1, processorTimeout = Timeout(1.minute), propagationBufSize = 100,
      maxContentSize = 1024, downloadBufferSize = 1000, downloadMaxInactivity = 10.seconds,
      downloadReadChunkSize = 8192, timeoutReadSize = 111, downloadConfig = null, downloader = null,
      contentPath = Uri.Path("archiveContent.json"), mediaPath = MediaPath, metadataPath = MetadataPath)

  /** Constant for the URI pointing to the content file of the test archive. */
  val ArchiveUri: String = DefaultArchiveConfig.archiveBaseUri.toString()

  /** Message indicating stream completion. */
  private val CompleteMessage = new Object

  /** Message indicating the initialization of a stream. */
  private val InitMessage = new Object

  /** Message used to acknowledge messages from the stream. */
  private val AckMessage = new Object

  /**
    * A data class identifying a file to be downloaded using a
    * [[MediaDownloader]]. An instance collects the parameters expected by the
    * download function.
    *
    * @param path   the path prefix of the file in question
    * @param suffix the suffix identifying the file
    */
  private case class DownloadKey(path: Uri.Path, suffix: String)

  /**
    * Type alias for a mapping defining download requests and responses to be
    * served by a test [[MediaDownloader]]. The mapping basically contains a
    * string result for a file to be downloaded. The download may fail;
    * therefore, the result is a ''Try''.
    */
  private type DownloadMapping = Map[DownloadKey, Try[String]]

  /**
    * Returns a test settings path for the specified index.
    *
    * @param idx the index
    * @return the test settings path for this index
    */
  private def settingsPath(idx: Int): String = "medium" + idx + "/playlist.settings"

  /**
    * Returns a test metadata path for the specified index.
    *
    * @param idx the index
    * @return the test metadata path for this index
    */
  private def metadataPath(idx: Int): String = s"metadata/data_$idx.mdt"

  /**
    * Creates a medium ID from the given description object.
    *
    * @param mediumDesc the ''HttpMediumDesc''
    * @return the ''MediumID''
    */
  def mediumID(mediumDesc: HttpMediumDesc): MediumID =
    val idx = mediumDesc.mediumDescriptionPath lastIndexOf '/'
    MediumID(mediumDesc.mediumDescriptionPath.substring(0, idx),
      Some(mediumDesc.mediumDescriptionPath), DefaultArchiveConfig.archiveName)

  /**
    * Creates a test medium description with the specified index.
    *
    * @param idx the index
    * @return the test medium description
    */
  def createMediumDesc(idx: Int): HttpMediumDesc =
    HttpMediumDesc(settingsPath(idx), metadataPath(idx))

  /**
    * Generates the given number of medium description objects.
    *
    * @param count the number of objects to create
    * @return a sequence with the generated elements
    */
  private def createMediumDescriptions(count: Int): List[HttpMediumDesc] =
    (1 to count).map(createMediumDesc).toList

  /**
    * Adds request/response mappings for the specified medium description to
    * the specified map.
    *
    * @param mapping the mapping to be appended
    * @param desc    the description
    * @return the updated mapping
    */
  private def appendResponseMapping(mapping: DownloadMapping, desc: HttpMediumDesc): DownloadMapping =
    val mapSettings =
      DownloadKey(MediaPath, desc.mediumDescriptionPath) -> Success(desc.mediumDescriptionPath)
    val mapMetaData = DownloadKey(MetadataPath, desc.metaDataPath) -> Success(desc.metaDataPath)
    mapping + mapSettings + mapMetaData

  /**
    * Creates a request mapping that supports all requests for the specified
    * sequence of media descriptions.
    *
    * @param mediaList the sequence with media descriptions
    * @return a corresponding request/response mapping
    */
  private def createResponseMapping(mediaList: Iterable[HttpMediumDesc]): DownloadMapping =
    mediaList.foldLeft(Map.empty[DownloadKey, Try[String]])((map, desc) =>
      appendResponseMapping(map, desc))

  /**
    * Creates a result object for a processed settings request.
    *
    * @param desc   the description for the medium affected
    * @param reqUri the URI of the request
    * @return the result for this settings request
    */
  def createSettingsProcessingResult(desc: HttpMediumDesc, reqUri: String):
  MediumInfoResponseProcessingResult =
    val info = MediumInfo(mediumID = mediumID(desc), name = desc.mediumDescriptionPath,
      description = reqUri, orderMode = "", checksum = "")
    MediumInfoResponseProcessingResult(info, SeqNo)

  /**
    * Creates a result object for a processed metadata request.
    *
    * @param desc   the description for the medium affected
    * @param reqUri the URI of the request
    * @return the result for this metadata request
    */
  def createMetadataProcessingResult(desc: HttpMediumDesc, reqUri: String):
  MetadataResponseProcessingResult =
    val mid = mediumID(desc)
    val data = List(MetadataProcessingSuccess(mediumID = mid, uri = MediaFileUri(desc.metaDataPath),
      metadata = MediaMetadata(title = Some(reqUri))))
    MetadataResponseProcessingResult(mid, data, SeqNo)

/**
  * Test class for ''HttpArchiveContentProcessorActor''.
  */
class HttpArchiveContentProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:

  import HttpArchiveContentProcessorActorSpec._

  def this() = this(ActorSystem("HttpArchiveContentProcessorActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Checks whether the specified set contains result objects for all of the
    * given medium descriptions.
    *
    * @param results the set with results
    * @param media   the medium descriptions
    * @return the same set with results
    */
  private def expectResultsFor(results: List[MediumProcessingResult],
                               media: Iterable[HttpMediumDesc]): List[MediumProcessingResult] =
    val expResults = media map { desc =>
      val infoResult = createSettingsProcessingResult(desc, desc.mediumDescriptionPath)
      val metaResult = createMetadataProcessingResult(desc, desc.metaDataPath)
      MediumProcessingResult(infoResult.mediumInfo, metaResult.metadata, SeqNo)
    }
    results should contain theSameElementsAs expResults
    results

  "A HttpArchiveContentProcessorActor" should "process the content document" in:
    val descriptions = createMediumDescriptions(8)
    val mapping = createResponseMapping(descriptions)
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchive(descriptions, mapping, DefaultArchiveConfig)
      .expectProcessingResults(descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)
    expectNoMessage(100.millis)

  it should "handle an empty source" in:
    val helper = new ContentProcessorActorTestHelper

    helper.processArchive(List.empty, Map.empty, DefaultArchiveConfig)
      .expectProcessingComplete(expectInit = true)

  it should "respect timeouts from processor actors" in:
    val config = DefaultArchiveConfig.copy(processorTimeout = Timeout(50.millis))
    val descriptions = createMediumDescriptions(8)
    val successMapping = createResponseMapping(descriptions)
    val failedSettingsDesc = createMediumDesc(42)
    val mapping1 = successMapping +
      (DownloadKey(MediaPath, failedSettingsDesc.mediumDescriptionPath) -> Success("")) +
      (DownloadKey(MetadataPath, failedSettingsDesc.metaDataPath) -> Success(failedSettingsDesc.metaDataPath))
    val failedMetaDesc = createMediumDesc(49)
    val mapping = mapping1 + (DownloadKey(MediaPath, failedMetaDesc.mediumDescriptionPath) ->
      Success(failedMetaDesc.mediumDescriptionPath)) +
      (DownloadKey(MetadataPath, failedMetaDesc.metaDataPath) -> Success(""))
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchive(failedMetaDesc :: failedSettingsDesc ::
      descriptions, mapping, config)
      .expectProcessingResults(descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)

  it should "handle error responses from processor actors" in:
    def createErrorResponse(msg: String): String = ErrorPrefix + msg

    val descriptions = createMediumDescriptions(8)
    val successMapping = createResponseMapping(descriptions)
    val failedSettingsDesc = createMediumDesc(42)
    val mapping1 = successMapping + (DownloadKey(MediaPath,
      failedSettingsDesc.mediumDescriptionPath) -> Success(createErrorResponse("wrong_settings"))) +
      (DownloadKey(MetadataPath, failedSettingsDesc.metaDataPath) -> Success(failedSettingsDesc.metaDataPath))
    val failedMetaDesc = createMediumDesc(49)
    val mapping = mapping1 + (DownloadKey(MediaPath, failedMetaDesc.mediumDescriptionPath) ->
      Success(failedMetaDesc.mediumDescriptionPath)) +
      (DownloadKey(MetadataPath, failedMetaDesc.metaDataPath) -> Success(createErrorResponse("wrong_meta_data")))
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchive(failedMetaDesc :: failedSettingsDesc ::
      descriptions, mapping, DefaultArchiveConfig)
      .expectProcessingResults(descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)

  it should "handle error responses from the downloader" in:
    val descriptions = createMediumDescriptions(8)
    val successMapping = createResponseMapping(descriptions)
    val failedSettingsDesc = createMediumDesc(42)
    val mapping = successMapping + (DownloadKey(MediaPath,
      failedSettingsDesc.mediumDescriptionPath) -> Failure(new IOException("Boom"))) +
      (DownloadKey(MetadataPath, failedSettingsDesc.metaDataPath) -> Success(failedSettingsDesc.metaDataPath))
    val helper = new ContentProcessorActorTestHelper

    val results = helper.processArchiveWithFailureMapping(failedSettingsDesc :: descriptions,
      mapping, DefaultArchiveConfig)
      .expectProcessingResults(descriptions.size)
    helper.expectProcessingComplete()
    expectResultsFor(results, descriptions)

  it should "ignore incomplete medium descriptions" in:
    val descriptions = List(HttpMediumDesc(settingsPath(1), null),
      HttpMediumDesc(null, metadataPath(2)))
    val helper = new ContentProcessorActorTestHelper

    helper.processArchive(descriptions, Map.empty, DefaultArchiveConfig)
      .expectProcessingComplete(expectInit = true)

  it should "allow canceling the current stream" in:
    val descriptions = createMediumDescriptions(256)
    val mapping = createResponseMapping(descriptions)
    val source = Source(descriptions).delay(1.second, DelayOverflowStrategy.backpressure)
    val helper = new ContentProcessorActorTestHelper

    helper.processArchiveWithSource(source, mapping, DefaultArchiveConfig)
      .cancelOperation()
      .fishForProcessingComplete()

  it should "handle processing results in unexpected order" in:
    val random = new Random
    val descriptions = createMediumDescriptions(16)

    def createShuffledMapping(basePath: Uri.Path, f: HttpMediumDesc => String): DownloadMapping =
      val requests = random.shuffle(descriptions map (d => DownloadKey(basePath, f(d))))
      val responses = random.shuffle(descriptions map (d => Success(f(d))))
      requests.zip(responses).toMap

    val mapping = createShuffledMapping(MediaPath, _.mediumDescriptionPath) ++
      createShuffledMapping(MetadataPath, _.metaDataPath)
    val helper = new ContentProcessorActorTestHelper(checkProcessingMessages = false)

    val results = helper.processArchive(descriptions, mapping, DefaultArchiveConfig)
      .expectProcessingResults(descriptions.size)
    expectResultsFor(results, descriptions)

  /**
    * A test helper class managing a test actor instance and its dependencies.
    *
    * @param checkProcessingMessages flag whether the mock processing actors
    *                                should check the messages they receive
    */
  private class ContentProcessorActorTestHelper(checkProcessingMessages: Boolean = true):
    /** The settings processor actor. */
    private val settingsProcessor =
      system.actorOf(Props(classOf[TestMediumInfoProcessingActor], checkProcessingMessages))

    /** The metadata processor actor. */
    private val metaDataProcessor =
      system.actorOf(Props(classOf[TestMetaDataProcessingActor], checkProcessingMessages))

    /** A test probe acting as sink for archive processing. */
    private val sinkProbe = TestProbe()

    /** The actor to be tested. */
    private val contentProcessorActor = system.actorOf(Props[HttpArchiveContentProcessorActor]())

    /**
      * Simulates a processing operation on the test archive.
      *
      * @param media           the sequence with media data
      * @param downloadMapping the download mapping
      * @param config          the configuration for the archive
      * @return this test helper
      */
    def processArchive(media: collection.immutable.Iterable[HttpMediumDesc], downloadMapping: DownloadMapping,
                       config: HttpArchiveConfig): ContentProcessorActorTestHelper =
      processArchiveWithFailureMapping(media, downloadMapping, config)

    /**
      * Simulates a processing operation on the test archive with potential
      * errors sent by the request actor.
      *
      * @param media           the sequence with media data
      * @param downloadMapping the download mapping
      * @param config          the configuration for the archive
      * @return this test helper
      */
    def processArchiveWithFailureMapping(media: collection.immutable.Iterable[HttpMediumDesc],
                                         downloadMapping: DownloadMapping,
                                         config: HttpArchiveConfig):
    ContentProcessorActorTestHelper =
      processArchiveWithSource(Source[HttpMediumDesc](media), downloadMapping, config)

    /**
      * Simulates a processing operation on the test archive with the source
      * specified.
      *
      * @param source          the source for media data
      * @param downloadMapping the download mapping
      * @param config          the configuration for the archive
      * @return this test helper
      */
    def processArchiveWithSource(source: Source[HttpMediumDesc, Any],
                                 downloadMapping: DownloadMapping,
                                 config: HttpArchiveConfig):
    ContentProcessorActorTestHelper =
      val sink = Sink.actorRefWithBackpressure(sinkProbe.ref, onCompleteMessage = CompleteMessage,
        onInitMessage = InitMessage, ackMessage = AckMessage, onFailureMessage = identity)
      val msg = createProcessArchiveRequest(source, downloadMapping, config, sink)
      contentProcessorActor ! msg
      this

    /**
      * Creates a request to process an HTTP archive based on the passed in
      * parameters.
      *
      * @param source          the source to be processed
      * @param downloadMapping the download mapping
      * @param config          the configuration for the archive
      * @param sink            the sink for accepting the data
      * @return the processing request
      */
    def createProcessArchiveRequest(source: Source[HttpMediumDesc, Any],
                                    downloadMapping: DownloadMapping,
                                    config: HttpArchiveConfig = DefaultArchiveConfig,
                                    sink: Sink[MediumProcessingResult, Any]):
    ProcessHttpArchiveRequest =
      val downloader = createDownloader(downloadMapping)
      ProcessHttpArchiveRequest(mediaSource = source,
        archiveConfig = config.copy(downloader = downloader), settingsProcessorActor = settingsProcessor,
        metadataProcessorActor = metaDataProcessor, sink = sink,
        seqNo = SeqNo, metadataParallelism = 1, infoParallelism = 1)

    /**
      * Expects that the given number of processing results has been sent to
      * the manager actor and returns a list with all result messages.
      *
      * @param count the expected number of results
      * @return the list with received messages for further testing
      */
    def expectProcessingResults(count: Int): List[MediumProcessingResult] =
      sinkProbe.expectMsg(InitMessage)
      sinkProbe.reply(AckMessage)
      val results = (1 to count).foldLeft(List.empty[MediumProcessingResult]) { (lst, _) =>
        val next = sinkProbe.expectMsgType[MediumProcessingResult] :: lst
        ackSink()
        next
      }
      results.reverse

    /**
      * Expects the completion message of the processing operation.
      *
      * @param expectInit flag whether an Init message is expected first
      * @return this test helper
      */
    def expectProcessingComplete(expectInit: Boolean = false): ContentProcessorActorTestHelper =
      if expectInit then
        sinkProbe.expectMsg(InitMessage)
        ackSink()
      sinkProbe.expectMsg(CompleteMessage)
      this

    /**
      * Tries to find a processing complete message ignoring test results.
      *
      * @return this test helper
      */
    def fishForProcessingComplete(): ContentProcessorActorTestHelper =
      sinkProbe.fishForMessage():
        case _: MediumProcessingResult => false
        case InitMessage => false
        case CompleteMessage => true
      this

    /**
      * Sends a message to cancel the current stream operation to the test
      * actor.
      *
      * @return this test helper
      */
    def cancelOperation(): ContentProcessorActorTestHelper =
      contentProcessorActor ! CancelStreams
      this

    /**
      * Sends an ACK message to the sink probe to indicate that the next
      * message can be processed.
      */
    private def ackSink(): Unit =
      sinkProbe.reply(AckMessage)

    /**
      * Creates a test downloader and initializes it with the given
      * request-response mapping.
      *
      * @param mapping the mapping
      * @return the test downloader
      */
    private def createDownloader(mapping: DownloadMapping): MediaDownloader =
      val downloader = mock[MediaDownloader]
      when(downloader.downloadMediaFile(any(), any())).thenAnswer((invocation: InvocationOnMock) => {
        val prefixPath = invocation.getArguments.head.asInstanceOf[Uri.Path]
        val suffix = invocation.getArgument(1, classOf[String])
        val key = DownloadKey(prefixPath, suffix)
        Future.fromTry(mapping(key).map(txt => Source.single(ByteString(txt))))
      })
      downloader


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
abstract class AbstractTestProcessorActor(checkMsg: Boolean) extends Actor:

  import HttpArchiveContentProcessorActorSpec._

  override def receive: Receive =
    case ProcessResponse(mid, desc, data, config, seqNo)
      if seqNo == HttpArchiveContentProcessorActorSpec.SeqNo &&
        config.archiveBaseUri == Uri(ArchiveUri) =>
      val client = sender()
      readData(data) foreach { text =>
        if text.startsWith(ErrorPrefix) then
          client ! org.apache.pekko.actor.Status.Failure(new IOException(text.substring(ErrorPrefix.length)))
        else
          createMediumDescFromDownload(text)
            .filter(d => !checkMsg || (d == desc && mid == mediumID(desc)))
            .map(desc => createResult(desc, text))
            .foreach(client.!)
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
    * Extracts the index of the test medium from the given download data.
    *
    * @param data the data from the download operation
    * @return an ''Option'' with the index of the test medium
    */
  protected def extractMediumIndex(data: String): Option[Int]

  /**
    * Tries to extract the index from the given data string and creates a
    * corresponding medium description.
    *
    * @param data the data from the download operation
    * @return an ''Option'' with the resulting medium description
    */
  private def createMediumDescFromDownload(data: String): Option[HttpMediumDesc] =
    extractMediumIndex(data) map createMediumDesc

  /**
    * Extracts the string from the given source.
    *
    * @param data the source
    * @return the string extracted from the source
    */
  private def readData(data: Source[ByteString, Any]): Future[String] =
    implicit val mat: ActorSystem = context.system
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    data.runWith(sink).map(_.utf8String)

  /**
    * Returns the implicit execution context for future operations.
    *
    * @return the execution context
    */
  private implicit def ec: ExecutionContext = context.dispatcher

/**
  * A concrete test processing actor that generates medium info results.
  */
class TestMediumInfoProcessingActor(checkMsg: Boolean)
  extends AbstractTestProcessorActor(checkMsg):
  override protected def createResult(desc: HttpMediumDesc, reqUri: String): AnyRef =
    HttpArchiveContentProcessorActorSpec.createSettingsProcessingResult(desc, reqUri)

  override protected def extractMediumIndex(data: String): Option[Int] =
    data match
      case HttpArchiveContentProcessorActorSpec.RegExSettings(idx) =>
        Some(idx.toInt)
      case _ =>
        None

/**
  * A concrete test processing actor that generates metadata results.
  */
class TestMetaDataProcessingActor(checkMsg: Boolean)
  extends AbstractTestProcessorActor(checkMsg):
  override protected def createResult(desc: HttpMediumDesc, reqUri: String): AnyRef =
    HttpArchiveContentProcessorActorSpec.createMetadataProcessingResult(desc, reqUri)

  override protected def extractMediumIndex(data: String): Option[Int] =
    data match
      case HttpArchiveContentProcessorActorSpec.RegExMetadata(idx) =>
        Some(idx.toInt)
      case _ => None
