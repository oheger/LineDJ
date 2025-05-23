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

package de.oliver_heger.linedj.extract.metadata

import de.oliver_heger.linedj.io._
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingResult, MetadataProcessingSuccess, ProcessMetadataFile}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.stream.DelayOverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.concurrent.duration._

object MetadataExtractionActorSpec:
  /**
    * Constant for a test element which should cause a processing error. A test
    * file containing this index is ignored by the test processing actor and
    * thus causes a timeout.
    */
  val ErrorIndex = 42

  /** Constant for the pattern the error index appears in file names. */
  val ErrorIndexStr = "_42."

  /** A regular expression to extract the index from a test file path. */
  private val RegFilePath = raw"""song_(\d+).mp3""".r

  /** Test medium ID. */
  private val TestMediumID = MediumID("testMedium", Some("test.settings"))

  /** The maximum number of test files. */
  private val MaxTestFiles = 100

  /** Type definition for a function which can manipulate a stream source. */
  private type SourceAdapter = Source[FileData, NotUsed] => Source[FileData, NotUsed]

  /**
    * Generates a path for a test media file.
    *
    * @param idx the index of the file
    * @return the test path
    */
  private def testPath(idx: Int): Path = Paths get s"/music/song_$idx.mp3"

  /**
    * Generates a uri for a test media file.
    *
    * @param idx the index of the file
    * @return the test URI
    */
  private def testUri(idx: Int): MediaFileUri = MediaFileUri(s"song://uri_$idx.mp3")

  /**
    * Generates a test ''FileData'' object.
    *
    * @param idx the index of the file
    * @return the test data object
    */
  private def testFileData(idx: Int): FileData =
    FileData(testPath(idx), (idx + 1) * 231)

  /**
    * A function for mapping paths of media files to URIs. This is done based
    * on the index of the test path.
    *
    * @param path the path
    * @return the URI for this path
    */
  private def uriMappingFunc(path: Path): MediaFileUri =
    path.getFileName.toString match
      case RegFilePath(idx) => testUri(idx.toInt)
      case _ => MediaFileUri("invalid://" + path)

  /**
    * Generates a processing request for a number of test files.
    *
    * @param from the start index for media files
    * @param to   the end index (including)
    * @return the request to process these test files
    */
  private def createProcessRequest(from: Int, to: Int): ProcessMediaFiles =
    val files = from.to(to).map(testFileData).toList
    ProcessMediaFiles(TestMediumID, files, uriMappingFunc)

  /**
    * Generates a processing result for the given input.
    *
    * @param fileData a test file data
    * @param template a test result template
    * @return the final processing result
    */
  def createProcessingResult(fileData: FileData, template: MetadataProcessingSuccess):
  MetadataProcessingSuccess =
    template.withMetadata(MediaMetadata(title = Some("Song_" + fileData.path), size = Some(fileData.size.toInt)))

  /**
    * Generates a processing result for the test file with the given index.
    *
    * @param idx the index of the test file
    * @return the processing result for this test file
    */
  def createProcessingResult(idx: Int): MetadataProcessingSuccess =
    val fileData = testFileData(idx)
    val template = MetadataProcessingSuccess(TestMediumID, testUri(idx), MediaMetadata())
    createProcessingResult(fileData, template)

/**
  * Test class for ''MetaDataExtractionActor''.
  */
class MetadataExtractionActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:

  import MetadataExtractionActorSpec._

  def this() = this(ActorSystem("MetaDataExtractionActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Creates an instance of the test actor.
    *
    * @param manager       the metadata manager actor
    * @param msgQueue      optional queue to be passed to the test wrapper actor
    * @param asyncCount    the async factor when calling extractor actors
    * @param timeout       the timeout when calling extractor actors
    * @param srcFunc       a function to modify the source used by the actor
    * @param optCloseCount optional counter for close complete operations
    * @return the test actor reference
    */
  private def createExtractionActor(manager: ActorRef = testActor,
                                    msgQueue: Option[BlockingQueue[ProcessMetadataFile]] = None,
                                    asyncCount: Int = 2,
                                    timeout: Timeout = Timeout(1.minute),
                                    srcFunc: SourceAdapter = identity,
                                    optCloseCount: Option[AtomicInteger] = None):
  TestActorRef[MetadataExtractionActor] =
    val extractorFactory = mock[ExtractorActorFactory]
    val childCount = new AtomicInteger
    val childRefProps = MetadataExtractorWrapperActor(extractorFactory)
    val closeCompleteCount = optCloseCount getOrElse new AtomicInteger
    val props = Props(new MetadataExtractionActor(manager, extractorFactory,
      asyncCount, timeout) with ChildActorFactory with CloseSupport {
      override def createChildActor(p: Props): ActorRef = {
        if p.actorClass() == childRefProps.actorClass() then {
          p should be(childRefProps)
          childCount.incrementAndGet() should be(1)
          system.actorOf(Props(classOf[WrapperActorImpl], msgQueue))
        } else super.createChildActor(p)
      }

      override def onCloseComplete(): Unit = {
        super.onCloseComplete()
        closeCompleteCount.incrementAndGet()
      }

      override private[metadata] def createSource(files: List[FileData]) =
        srcFunc(super.createSource(files))
    })
    TestActorRef[MetadataExtractionActor](props)

  /**
    * Expects that metadata processing results in the specified range are
    * received.
    *
    * @param fromIdx the expected start index
    * @param toIdx   the expected end index
    * @return the list with result messages received (in reverse order)
    */
  private def expectMetaDataResults(fromIdx: Int, toIdx: Int): List[MetadataProcessingResult] =
    val expResults = (fromIdx to toIdx) map createProcessingResult
    val results = fetchProcessingResults(fromIdx, toIdx)
    results should contain theSameElementsAs expResults
    results

  /**
    * Obtains a number of metadata processing results from the test actor.
    * All results received are returned in a list (in reverse order)
    *
    * @param fromIdx the expected start index
    * @param toIdx   the expected end index
    * @return the list with result messages received (in reverse order)
    */
  private def fetchProcessingResults(fromIdx: Int, toIdx: Int): List[MetadataProcessingResult] =
    (fromIdx to toIdx).foldLeft(List.empty[MetadataProcessingResult])((lst, _) =>
      expectMsgType[MetadataProcessingResult] :: lst)

  "A MetadataExtractionActor" should "return correct properties" in:
    val factory = mock[ExtractorActorFactory]
    val timeout = Timeout(5.minutes)
    val props = MetadataExtractionActor(testActor, factory, 1, timeout)

    classOf[MetadataExtractionActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CloseSupport].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(testActor, factory, 1, timeout))

  it should "process a number of media files" in:
    val Count = 16
    val actor = createExtractionActor()

    actor ! createProcessRequest(1, Count)
    expectMetaDataResults(1, Count)

  it should "handle a timeout when processing a stream" in:
    val actor = createExtractionActor(timeout = Timeout(100.millis), asyncCount = 1)

    actor ! createProcessRequest(40, 50)
    expectMetaDataResults(40, 41)
    val errMsg = expectMsgType[MetadataProcessingError]
    expectMetaDataResults(43, 50)
    errMsg.uri should be(testUri(ErrorIndex))

  it should "take the async factor into account" in:
    val actor = createExtractionActor(timeout = Timeout(100.millis))

    actor ! createProcessRequest(ErrorIndex, 60)
    val results = fetchProcessingResults(ErrorIndex, 60).reverse.zipWithIndex
    val optError = results.find(_._1.isInstanceOf[MetadataProcessingError])
    // because of the timeout, other elements should have been processed first
    optError.get._2 should be > 0

  it should "handle multiple requests one after the other" in:
    val actor = createExtractionActor()

    actor ! createProcessRequest(1, ErrorIndex - 1)
    actor ! createProcessRequest(ErrorIndex + 1, MaxTestFiles - 1)
    expectMetaDataResults(1, ErrorIndex - 1)
    expectMetaDataResults(ErrorIndex + 1, MaxTestFiles - 1)

  it should "support cancellation of stream processing" in:
    val Count = ErrorIndex - 1
    val receiver = TestProbe()
    val queue = new LinkedBlockingQueue[ProcessMetadataFile]
    val actor = createExtractionActor(msgQueue = Some(queue), manager = receiver.ref,
      srcFunc = src => src.delay(200.millis, DelayOverflowStrategy.backpressure))
    actor ! createProcessRequest(1, Count)
    actor ! createProcessRequest(50, 88)

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    queue.size() should be < Count
    (1 to queue.size()).foreach(_ => receiver.expectMsgType[MetadataProcessingResult])
    receiver.expectNoMessage(500.millis)

  it should "no longer accepts requests after a close request" in:
    val receiver = TestProbe()
    val actor = createExtractionActor(manager = receiver.ref)

    actor ! CloseRequest
    actor ! createProcessRequest(1, 22)
    expectMsg(CloseAck(actor))
    receiver.expectNoMessage(500.millis)

  it should "react on a close complete message" in:
    val completeCount = new AtomicInteger
    val actor = createExtractionActor(optCloseCount = Some(completeCount))
    actor ! CloseRequest
    expectMsg(CloseAck(actor))

    completeCount.get() should be(1)

/**
  * An actor implementation simulating the extractor wrapper actor.
  *
  * This actor class produces simulated processing results and sends them
  * back to caller. It also supports storing all messages received in a
  * queue.
  *
  * @param messages an optional queue for storing received messages
  */
class WrapperActorImpl(messages: Option[BlockingQueue[ProcessMetadataFile]]) extends Actor:
  override def receive: Receive =
    case p: ProcessMetadataFile =>
      if !p.fileData.path.toString.contains(MetadataExtractionActorSpec.ErrorIndexStr) then
        sender() ! MetadataExtractionActorSpec.createProcessingResult(p.fileData, p.resultTemplate)
        messages foreach (_.offer(p))

    case CloseRequest =>
      sender() ! CloseAck(self)
