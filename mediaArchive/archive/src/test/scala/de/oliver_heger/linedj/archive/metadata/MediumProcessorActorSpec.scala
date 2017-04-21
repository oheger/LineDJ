/*
 * Copyright 2015-2017 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package de.oliver_heger.linedj.archive.metadata

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.{EnhancedMediaScanResult, MediaScanResult}
import de.oliver_heger.linedj.archive.mp3.ID3Header
import de.oliver_heger.linedj.extract.metadata.MetaDataProvider
import de.oliver_heger.linedj.io.{ChannelHandler, CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingResult
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Matchers.{any, eq => eqArg}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.util.Random

object MediumProcessorActorSpec {
  /** A path to a medium. */
  private val Medium = Paths.get("Medium", "medium.settings")

  /** A root path. */
  private val RootPath = path("Root")

  /** Constant for a test medium ID. */
  private val RootMedium = MediumID(RootPath.toString, None)

  /** The enhanced scan result to be processed by the test actor. */
  private val ExtScanResult = createScanResult()

  /** A test media scan result object. */
  private val ScanResult = ExtScanResult.scanResult

  /** A test message about files to be processed. */
  private val ProcessMessage = createProcessMessage()

  /** The number of processing actors for the test root path. */
  private val ProcessorCount = 2

  /** A list containing all paths on the medium to be processed. */
  private val MediumPaths = List(path(1), path(2), path(3))

  /** Version for test ID3v2 headers. */
  private val ID3v2Version = 3

  /**
   * Generates a test path based on the passed in name.
    *
    * @param name the name
   * @return the test path
   */
  private def path(name: String): Path = Paths get "TestPath_" + name

  /**
   * Generates a test path based on the passed in index.
    *
    * @param idx the index
   * @return the test path
   */
  private def path(idx: Int): Path = path(idx.toString)

  /**
   * Calculates the file size of the test file with the given index.
    *
    * @param idx the index
   * @return the size of this test file
   */
  private def testFileSize(idx: Int): Int = idx * 10

  /**
    * Generates a URI for a test song.
    *
    * @param idx the index
    * @return the URI for this test song
    */
  private def fileUri(idx: Int): String = "song://TestSong_" + idx

  /**
   * Generates a test media file from the specified test index. The path and
   * the file size are derived from the index.
    *
    * @param idx the index
   * @return the corresponding test file
   */
  private def mediaFile(idx: Int): FileData = FileData(path(idx).toString, testFileSize(idx))

  /**
   * Extracts the index from the given test path.
    *
    * @param path the path
   * @return the index of this path
   */
  private def extractIndex(path: Path): Int = {
    val sPath = path.toString
    val posIdx = sPath lastIndexOf '_'
    sPath.substring(posIdx + 1).toInt
  }

  /**
   * Converts the specified path of a test file back to a ''FileData'' object.
    *
    * @param path the test path
   * @return the medium file
   */
  private def fileFromPath(path: Path): FileData = FileData(path.toString,
    testFileSize(extractIndex(path)))

  /**
   * Creates a scan result for a medium which contains a couple of files.
    *
    * @return the test scan result
   */
  private def createScanResult(): EnhancedMediaScanResult = {
    val file1 = mediaFile(1)
    val file2 = mediaFile(2)
    val file3 = mediaFile(3)
    val file4 = mediaFile(4)
    val sr = MediaScanResult(RootPath, Map(MediumID.fromDescriptionPath(Medium) -> List(file1),
      RootMedium -> List(file2, file3, file4)))
    val uriMapping = Map(fileUri(1) -> file1, fileUri(2) -> file2, fileUri(3) -> file3,
      fileUri(4) -> file4)
    EnhancedMediaScanResult(sr, Map.empty, uriMapping)
  }

  /**
    * Creates a default message for processing media files.
    *
    * @return the default process message
    */
  private def createProcessMessage(): ProcessMediaFiles =
    ProcessMediaFiles(RootMedium, List(mediaFile(2), mediaFile(3)))

  /**
   * Prepares a mock for the central configuration. The configuration can be
   * asked for a media root object for the root path of the test scan result.
    *
    * @param config the mock to be initialized
   * @param readerCount the number of processor actors to be returned
   * @return the prepared mock object
   */
  private def prepareConfigMock(config: MediaArchiveConfig, readerCount: Int = ProcessorCount):
  MediaArchiveConfig = {
    when(config.rootFor(ScanResult.root)).thenReturn(Some(MediaArchiveConfig.MediaRootData(ScanResult
      .root, readerCount, None)))
    when(config.metaDataReadChunkSize).thenReturn(128)
    config
  }
}

/**
 * Test class for ''MediumProcessorActor''.
 */
class MediumProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import MediumProcessorActorSpec._

  /** Constant for the final meta data of a path. */
  private val MetaData = MediaMetaData(title = Some("Some song"), artist = Some("Artist"))

  def this() = this(ActorSystem("MediumProcessorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
   * Creates a test actor reference with a mock configuration.
    *
    * @return a tuple with the test actor reference and the mock configuration
   */
  private def createStandardTestActor(): (TestActorRef[MediumProcessorActor], MediaArchiveConfig) = {
    val config = prepareConfigMock(mock[MediaArchiveConfig])
    (TestActorRef[MediumProcessorActor](MediumProcessorActor(ExtScanResult, config)), config)
  }

  /**
   * Checks whether a default processor actor map was created for a given actor
   * type. This method checks whether correct creation properties have been
   * assigned to the map.
    *
    * @param actorClass the expected actor class
   * @param selector a function selecting the map to be checked
   */
  private def checkDefaultProcessorActorMap(actorClass: Class[_])(selector: MediumProcessorActor
    => ProcessorActorMap): Unit = {
    val (actor, config) = createStandardTestActor()
    val map = selector(actor.underlyingActor)
    val context = checkCreationProperties(map.creationProps, actorClass)
    context.config should be(config)
    context.collectorActor should be(actor)
  }

  /**
   * Checks the creation properties of an actor that is passed an extraction
   * context object. The context passed as argument is returned.
    *
    * @param props the props to be checked
   * @param expectedActorClass the expected actor class
   * @return the context extracted from the arguments
   */
  private def checkCreationProperties(props: Props, expectedActorClass: Class[_]):
  MetaDataExtractionContext = {
    props.actorClass should be(expectedActorClass)
    props.args should have length 1
    props.args.head shouldBe a[MetaDataExtractionContext]
    val context = props.args.head.asInstanceOf[MetaDataExtractionContext]
    context
  }

  "A MediumProcessorActor" should "create a default ID3v2 processor map" in {
    checkDefaultProcessorActorMap(classOf[ID3FrameProcessorActor])(_.id3v2ProcessorMap)
  }

  it should "create a default mp3 processor map" in {
    checkDefaultProcessorActorMap(classOf[Mp3DataProcessorActor])(_.mp3ProcessorMap)
  }

  it should "create a default ID3v1 processor map" in {
    checkDefaultProcessorActorMap(classOf[ID3v1FrameProcessorActor])(_.id3v1ProcessorMap)
  }

  it should "create a default meta data collectors map" in {
    val (actor, _) = createStandardTestActor()
    actor.underlyingActor.collectorMap shouldBe a[MetaDataCollectorMap]
  }

  it should "start processing after receiving a Process message" in {
    val helper = new MediumProcessorActorTestHelper
    val paths = helper waitForProcessing ProcessorCount
    paths forall MediumPaths.contains shouldBe true
  }

  /**
   * Creates a message for processing an ID3 v2 frame.
    *
    * @param lastChunk flag whether this is the last chunk
   * @param p the optional path to the media file
   * @return the message
   */
  private def createID3FrameData(lastChunk: Boolean, p: Path = path(2)): ProcessID3FrameData = {
    val bytes = new Array[Byte](16)
    val random = new Random
    random nextBytes bytes
    ProcessID3FrameData(p, ID3Header(ID3v2Version, bytes.length), bytes, lastChunk)
  }

  /**
   * Helper method for checking whether an ID3v2 frame message is
   * correctly processed.
    *
    * @param frameData the frame message
   * @return the test helper
   */
  private def checkID3v2FrameMessageHandling(frameData: ProcessID3FrameData):
  MediumProcessorActorTestHelper = {
    val helper = MediumProcessorActorTestHelper()
    val probe = helper.installProcessorActor(helper.id3v2ProcessorMap, frameData.path)
    val collector = helper installCollector frameData.path

    helper send frameData
    verify(collector).expectID3Data(ID3v2Version)
    probe.expectMsg(frameData)
    helper
  }

  it should "handle a message about an ID3v2 frame" in {
    val helper = checkID3v2FrameMessageHandling(createID3FrameData(lastChunk = false))

    verify(helper.id3v2ProcessorMap, never()).removeItemFor(any(classOf[Path]))
  }

  it should "handle a message about an ID3v2 frame for the last chunk" in {
    val msg = createID3FrameData(lastChunk = true)
    val helper = checkID3v2FrameMessageHandling(msg)

    verify(helper.id3v2ProcessorMap).removeItemFor(msg.path)
  }

  /**
   * Creates a message for processing MP3 data.
    *
    * @param p the path to be used for the data
   * @return the message
   */
  private def createMp3Data(p: Path = path(2)): ProcessMp3Data =
    ProcessMp3Data(p, mock[ChannelHandler.ArraySource])

  it should "handle a message about mp3 data" in {
    val helper = MediumProcessorActorTestHelper()
    val msg = createMp3Data()
    val probeId3Processor = helper.installProcessorActor(helper.id3v1ProcessorMap, msg.path)
    val probeMp3Processor = helper.installProcessorActor(helper.mp3ProcessorMap, msg.path)

    helper send msg
    probeId3Processor.expectMsg(msg)
    probeMp3Processor.expectMsg(msg)
  }

  /**
   * Creates an answer for a mock method that sets a meta data part. Because
   * this method is executed asynchronously the mock cannot easily be verified.
   * Therefore, this answer sends the first argument of the invocation to the
   * test actor - by expecting this message the mock gets actually verified.
    *
    * @param complete flag whether meta data is now complete; in this case, the
   *                 resulting meta data is returned by the answer
   * @return the initialized answer
   */
  private def metaDataResultAnswer(complete: Boolean): Answer[Option[MediaMetaData]] = {
    new Answer[Option[MediaMetaData]] {
      override def answer(invocation: InvocationOnMock): Option[MediaMetaData] = {
        testActor ! invocation.getArguments.head
        if (complete) Some(MetaData) else None
      }
    }
  }

  /**
    * Expects that a message for a processing result was sent.
    *
    * @param p   the path to the processed file
    * @param uri the expected URI of the file
    * @return the received result message
    */
  private def expectProcessingResult(p: Path, uri: String): MetaDataProcessingResult = {
    expectMsg(MetaDataProcessingResult(p.toString,
      if (MediumPaths.head == p) MediumID.fromDescriptionPath(Medium)
      else MediumID(ScanResult.root.toString, None), uri, MetaData))
  }

  /**
   * Checks whether the given test probe was stopped.
    *
    * @param probe the probe
   */
  private def checkActorStopped(probe: TestProbe): Unit = {
    val probeWatch = TestProbe()
    probeWatch watch probe.ref
    probeWatch.expectMsgType[Terminated].actor should be(probe.ref)
  }

  /**
   * Creates an ID3 frame meta data object.
    *
    * @param p the path to be used
   * @return the meta data object
   */
  private def createID3FrameMetaData(p: Path): ID3FrameMetaData =
    ID3FrameMetaData(p, ID3Header(2, 32), Some(mock[MetaDataProvider]))

  /**
   * Checks whether a meta data result message for an ID3v2 frame is correctly
   * handled.
    *
    * @param p the path to the file
   * @param complete flag whether processing of this file is complete
   * @return the test helper for further verification
   */
  private def checkID3v2ResultHandling(p: Path, complete: Boolean):
  MediumProcessorActorTestHelper = {
    val helper = MediumProcessorActorTestHelper()
    val id3MetaData = createID3FrameMetaData(p)
    val collector = helper installCollector id3MetaData.path
    val probe = TestProbe()
    when(collector.addID3Data(id3MetaData)).thenAnswer(metaDataResultAnswer(complete))

    helper.actor.tell(id3MetaData, probe.ref)
    expectMsg(id3MetaData)
    helper
  }

  it should "handle an ID3v2 meta data result if processing is not yet done" in {
    val p = path(2)
    val helper = checkID3v2ResultHandling(p, complete = false)

    // now we are synced, so direct verification is possible
    verify(helper.collectorMap, never()).removeItemFor(p)
  }

  it should "handle an ID3v2 meta data result if processing is complete" in {
    val p = path(2)
    val helper = checkID3v2ResultHandling(p, complete = true)

    expectProcessingResult(p, fileUri(2))
    verify(helper.collectorMap).removeItemFor(p)
  }

  /**
   * Checks whether a meta data result for an ID3v1 frame is correctly handled.
    *
    * @param p the path to the file
   * @param complete flag whether processing of this file is complete
   * @return the test helper for further verifications
   */
  private def checkID3v1ResultHandling(p: Path, complete: Boolean):
  MediumProcessorActorTestHelper = {
    val helper = MediumProcessorActorTestHelper()
    val id3MetaData = ID3v1MetaData(p, Some(mock[MetaDataProvider]))
    val collector = helper installCollector p
    val probe = TestProbe()
    when(collector.setID3v1MetaData(id3MetaData.metaData)).thenAnswer(metaDataResultAnswer
      (complete))
    when(helper.id3v1ProcessorMap.removeItemFor(p)).thenReturn(Some(probe.ref))

    helper.actor.tell(id3MetaData, probe.ref)
    expectMsg(id3MetaData.metaData)
    checkActorStopped(probe)
    helper
  }

  it should "handle an ID3v1 meta data result if processing is not yet done" in {
    val p = path(2)
    val helper = checkID3v1ResultHandling(p, complete = false)

    verify(helper.collectorMap, never()).removeItemFor(p)
  }

  it should "handle an ID3v1 meta data result if processing is complete" in {
    val p = path(2)
    val helper = checkID3v1ResultHandling(p, complete = true)

    expectProcessingResult(p, fileUri(2))
    verify(helper.collectorMap).removeItemFor(p)
  }

  it should "ignore ID3v1 meta data for an unknown path" in {
    val helper = MediumProcessorActorTestHelper()
    val p = path(12)
    when(helper.id3v1ProcessorMap.removeItemFor(p)).thenReturn(None)

    helper send ID3v1MetaData(p, Some(mock[MetaDataProvider]))
    verifyZeroInteractions(helper.collectorMap)
  }

  /**
   * Creates an MP3 meta data result object.
    *
    * @param p the target path
   * @return the MP3 result object
   */
  private def createMp3Result(p: Path): Mp3MetaData =
    Mp3MetaData(p, 2, 3, 100, 96000, 128000, 180000)

  /**
   * Checks whether an MP3 meta data result is correctly handled.
    *
    * @param p the path to the file
   * @param complete flag whether processing of this file is complete
   * @return the test helper for further verification
   */
  private def checkMp3ResultHandling(p: Path, complete: Boolean): MediumProcessorActorTestHelper = {
    val helper = MediumProcessorActorTestHelper()
    val mp3MetaData = createMp3Result(p)
    val collector = helper installCollector p
    val probe = TestProbe()
    when(collector.setMp3MetaData(mp3MetaData)).thenAnswer(metaDataResultAnswer(complete))
    when(helper.mp3ProcessorMap.removeItemFor(p)).thenReturn(Some(probe.ref))

    helper.actor.tell(mp3MetaData, probe.ref)
    expectMsg(mp3MetaData)
    checkActorStopped(probe)
    helper
  }

  it should "handle an MP3 meta data result if processing is not yet done" in {
    val p = path(2)
    val helper = checkMp3ResultHandling(p, complete = false)

    verify(helper.collectorMap, never()).removeItemFor(p)
  }

  it should "handle an MP3 meta data result if processing is complete" in {
    val p = path(2)
    val helper = checkMp3ResultHandling(p, complete = true)

    expectProcessingResult(p, fileUri(2))
    verify(helper.collectorMap).removeItemFor(p)
  }

  it should "ignore MP3 meta data for an unknown path" in {
    val helper = MediumProcessorActorTestHelper()
    val p = path(12)
    when(helper.mp3ProcessorMap.removeItemFor(p)).thenReturn(None)

    helper send createMp3Result(p)
    verifyZeroInteractions(helper.collectorMap)
  }

  it should "ignore ID3v2 frame data for an unknown path" in {
    val helper = MediumProcessorActorTestHelper()

    helper send createID3FrameData(p = path(12), lastChunk = true)
    verifyZeroInteractions(helper.id3v2ProcessorMap)
  }

  it should "ignore MP3 data for an unknown path" in {
    val helper = MediumProcessorActorTestHelper()

    helper send createMp3Data(path(28))
    verifyZeroInteractions(helper.mp3ProcessorMap)
  }

  it should "ignore an ID3v2 processing result for an unknown path" in {
    val helper = MediumProcessorActorTestHelper()

    helper send createID3FrameMetaData(path(33))
    verifyZeroInteractions(helper.collectorMap)
  }

  it should "ignore messages for paths that have been fully processed" in {
    val p = path(2)
    val helper = checkID3v1ResultHandling(p, complete = true)
    expectProcessingResult(p, fileUri(2))

    helper send createMp3Data(p)
    verifyZeroInteractions(helper.mp3ProcessorMap)
  }

  it should "process the next file when one is complete" in {
    val helper = new MediumProcessorActorTestHelper
    val paths = helper waitForProcessing ProcessorCount
    val reader = helper.readerActors.head
    val msgFileRead = MediaFileRead(paths.head)
    val probeMp3Processor = helper.installProcessorActor(helper.mp3ProcessorMap, msgFileRead.path)
    val probeId3Processor = helper.installProcessorActor(helper.id3v1ProcessorMap, msgFileRead.path)

    helper.actor.tell(msgFileRead, reader.ref)
    helper.actor ! ProcessMediaFiles(RootMedium, List(mediaFile(1)))
    val msgNextFile = reader.expectMsgType[ReadMediaFile]
    val allPaths = paths + msgNextFile.path
    allPaths should have size MediumPaths.size
    allPaths should contain only (MediumPaths: _*)
    probeMp3Processor.expectMsg(msgFileRead)
    probeId3Processor.expectMsg(msgFileRead)
  }

  it should "pass the correct parent reference to child actors" in {
    val helper = new MediumProcessorActorTestHelper

    helper.parentActorRef.get() should be(helper.actor)
  }

  it should "handle an unknown root path" in {
    val config = prepareConfigMock(mock[MediaArchiveConfig])
    when(config.rootFor(ScanResult.root)).thenReturn(None)
    val helper = new MediumProcessorActorTestHelper(optConfig = Some(config))

    helper send ProcessMessage
    helper.numberOfChildrenCreated should be(1)
  }

  it should "process the whole list of files from multiple process messages" in {
    val helper = new MediumProcessorActorTestHelper

    helper.actor ! ProcessMessage
    val p1 = helper.readerActors.head.expectMsgType[ReadMediaFile].path
    val p2 = helper.readerActors(1).expectMsgType[ReadMediaFile].path
    helper.processPath(p1)
    helper.actor ! ProcessMediaFiles(RootMedium, List(mediaFile(4)))
    helper.processPath(p2)
    helper.actor.tell(MediaFileRead(null), helper.readerActors.head.ref)
    val readMsg = helper.readerActors.head.expectMsgType[ReadMediaFile]
    helper send MediaFileRead(null)
    helper.processPath(readMsg.path)
  }

  it should "react on an exception caused by a reader actor" in {
    val mediaFiles = MediumPaths map (p => FileData(p.toString, 128))
    val mid = MediumID.fromDescriptionPath(Medium)
    val scanResult = ScanResult.copy(mediaFiles = Map(mid -> mediaFiles))
    val errorPath = MediumPaths.head
    val errorPathStr = errorPath.toString
    val errorTuple = ExtScanResult.fileUriMapping.find(e => e._2.path == errorPathStr)
    val errorUri = fileUri(42)
    val uriMapping = ExtScanResult.fileUriMapping - errorTuple.get._1 + (errorUri -> FileData
    (errorPathStr, 128))
    val helper = new MediumProcessorActorTestHelper(numberOfRealActors = 1,
      scanResult = ExtScanResult.copy(scanResult = scanResult, fileUriMapping = uriMapping))
    val probeMp3Processor, probeId3v1Processor, probeId3v2Processor = TestProbe()
    when(helper.mp3ProcessorMap.removeItemFor(errorPath)).thenReturn(Some(probeMp3Processor.ref))
    when(helper.id3v1ProcessorMap.removeItemFor(errorPath)).thenReturn(Some(probeId3v1Processor
      .ref))
    when(helper.id3v2ProcessorMap.removeItemFor(errorPath)).thenReturn(Some(probeId3v2Processor
      .ref))
    when(helper.collectorMap.removeItemFor(errorPath)).thenAnswer(new
        Answer[Option[MetaDataPartsCollector]] {
      override def answer(invocation: InvocationOnMock): Option[MetaDataPartsCollector] = {
        testActor ! errorPath
        None
      }
    })

    helper.actor ! ProcessMediaFiles(mid, scanResult.mediaFiles(mid))
    List(probeMp3Processor, probeId3v1Processor, probeId3v2Processor) foreach checkActorStopped
    expectMsg(MetaDataProcessingResult(metaData = MediaMetaData(), path = errorPath.toString,
      mediumID = mid, uri =  errorUri))
    expectMsg(errorPath)

    helper send createMp3Data(errorPath)
    verify(helper.mp3ProcessorMap, never()).getOrCreateActorFor(eqArg(errorPath), any
      (classOf[ChildActorFactory]))

    val paths = helper waitForProcessing 2
    paths should contain only (MediumPaths.tail: _*)
  }

  it should "handle a Cancel request in the middle of processing" in {
    val helper = new MediumProcessorActorTestHelper
    val readerData = helper waitForFileReads ProcessorCount
    helper.actor ! ProcessMediaFiles(RootMedium, List(mediaFile(3)))

    helper send CloseRequest
    helper.processMediaFiles(readerData)
    helper.readerActors.head.expectNoMsg(100.millis)
    expectMsg(CloseAck(helper.actor))
  }

  it should "handle a Cancel request after processing done" in {
    val helper = new MediumProcessorActorTestHelper
    helper.processMediaFiles(helper waitForFileReads ProcessorCount)

    helper.actor ! CloseRequest
    expectMsg(CloseAck(helper.actor))
  }

  it should "ignore further media files while a Cancel request is pending" in {
    val helper = new MediumProcessorActorTestHelper
    val readerData = helper waitForFileReads ProcessorCount

    helper send CloseRequest
    helper.actor ! ProcessMediaFiles(RootMedium, List(mediaFile(3)))
    helper.processMediaFiles(readerData)
    expectMsg(CloseAck(helper.actor))
    helper.readerActors.head.expectNoMsg(100.millis)
  }

  it should "reset the cancel flag after sending a CloseAck" in {
    val helper = new MediumProcessorActorTestHelper
    val readerData = helper waitForFileReads ProcessorCount

    helper send CloseRequest
    helper.processMediaFiles(readerData)
    expectMsg(CloseAck(helper.actor))
    helper.actor ! ProcessMediaFiles(RootMedium, List(mediaFile(3)))
    helper waitForProcessing 1
  }

  private object MediumProcessorActorTestHelper {
    /**
     * Convenience method for creating a test helper and waiting until its
     * initialization is complete. We wait until all child actors have received
     * messages for paths to be processed.
      *
      * @return the newly created test helper
     */
    def apply(): MediumProcessorActorTestHelper = {
      val helper = new MediumProcessorActorTestHelper
      helper waitForProcessing ProcessorCount
      helper
    }
  }

  /**
    * A test helper class managing some mock dependencies passed to a test actor
    * reference.
    *
    * @param scanResult         the object with the files to be processed
    * @param numberOfRealActors the number of real child actors to be created;
    *                           per default, test probes are returned for child
    *                           actors; with a value greater zero, a number of
    *                           real reader actors can be created
    * @param optConfig          an optional mock for the configuration
    */
  private class MediumProcessorActorTestHelper(scanResult: EnhancedMediaScanResult = ExtScanResult,
                                                numberOfRealActors: Int = 0,
                                               optConfig: Option[MediaArchiveConfig] = None) {
    /** A mock for the processor map for ID3v1 processors. */
    val id3v1ProcessorMap = mock[ProcessorActorMap]

    /** A mock for the processor map for ID3v2 processors. */
    val id3v2ProcessorMap = mock[ProcessorActorMap]

    /** A mock for the processor map for MP3 processors. */
    val mp3ProcessorMap = mock[ProcessorActorMap]

    /** A mock for the meta data collector map. */
    val collectorMap = mock[MetaDataCollectorMap]

    /** A list with test probes for media reader child actors. */
    val readerActors = List.fill(ProcessorCount)(TestProbe())

    /** The mock for the server configuration. */
    val config = optConfig getOrElse createConfigMock()

    /** A reference for the parent actor passed to newly created children. */
    val parentActorRef = new AtomicReference[ActorRef]

    /** A counter for the child actors created by the factory. */
    private val childCount = new AtomicInteger

    /** The test actor. */
    val actor = TestActorRef[MediumProcessorActor](createTestActorProps())

    /**
     * Waits until the given number of paths is processed by child reader
     * actors. The paths are returned.
      *
      * @param pathCount the number of paths to wait for
     * @return a set with the paths currently processed
     */
    def waitForProcessing(pathCount: Int): Set[Path] = {
      actor ! ProcessMessage
      readerActors.take(pathCount).map(_.expectMsgType[ReadMediaFile].path).toSet
    }

    /**
      * Waits until the given number of read operations has been started and
      * returns information about the reader actors and the paths they have to
      * process.
      *
      * @param pathCount the number of paths to wait for
      * @return a sequence with the reader probes and their paths
      */
    def waitForFileReads(pathCount: Int): Seq[(TestProbe, Path)] = {
      actor ! ProcessMessage
      readerActors.take(pathCount).map(p => (p, p.expectMsgType[ReadMediaFile].path))
    }

    /**
     * Directly sends the specified message to the test actor (by invoking
     * receive on the test reference).
      *
      * @param msg the message to be sent
     */
    def send(msg: Any): Unit = {
      actor receive msg
    }

    /**
     * Prepares a mock processor actor map to return a test probe when it is
     * asked for a specific actor instance.
      *
      * @param map the map to be prepared
     * @param p the expected path
     * @return the test probe
     */
    def installProcessorActor(map: ProcessorActorMap, p: Path): TestProbe = {
      val probe = TestProbe()
      when(map.getOrCreateActorFor(eqArg(p), any(classOf[ChildActorFactory]))).thenAnswer(new
          Answer[ActorRef] {
        override def answer(invocation: InvocationOnMock): ActorRef = {
          invocation.getArguments()(1) should be(actor.underlyingActor)
          probe.ref
        }
      })
      probe
    }

    /**
     * Prepares the mock for the collector map to return a specific mock
     * collector for the given path.
      *
      * @param p the path
     * @return the mock collector for this path
     */
    def installCollector(p: Path): MetaDataPartsCollector = {
      val collector = mock[MetaDataPartsCollector]
      when(collectorMap.getOrCreateCollector(fileFromPath(p))).thenReturn(collector)
      collector
    }

    /**
      * Simulates processing of a media file.
      *
      * @param p the path of the media file
      * @return this test helper
      */
    def processPath(p: Path): MediumProcessorActorTestHelper = {
      val probe = installProcessorActor(mp3ProcessorMap, p)
      installProcessorActor(id3v1ProcessorMap, p)
      when(mp3ProcessorMap.removeItemFor(p)).thenReturn(Some(probe.ref))
      val collector = installCollector(p)
      when(collectorMap.removeItemFor(p)).thenReturn(Some(collector))
      val mp3Data = createMp3Result(p)
      when(collector.setMp3MetaData(mp3Data)).thenReturn(Some(MetaData))

      val processMsg = createMp3Data(p)
      send(processMsg)
      probe.expectMsg(processMsg)
      actor.tell(mp3Data, probe.ref)
      val pathIdx = p.toString.last.toString.toInt
      expectProcessingResult(p, fileUri(pathIdx))
      this
    }

    /**
      * Sends a message to the test actor that the specified media file has
      * been read completely.
      *
      * @param reader the reader actor test probe
      * @param p      the path of the media file
      * @return this test helper
      */
    def sendFileRead(reader: TestProbe, p: Path): MediumProcessorActorTestHelper = {
      actor.tell(MediaFileRead(p), reader.ref)
      this
    }

    /**
      * Simulates full processing of the specified media files.
      *
      * @param readerData a sequence with reader actors and paths to process
      * @return this test helper
      */
    def processMediaFiles(readerData: Seq[(TestProbe, Path)]): MediumProcessorActorTestHelper = {
      readerData foreach { d =>
        processPath(d._2)
        sendFileRead(d._1, d._2)
      }
      this
    }

    /**
     * Returns the number of children that have been created by the test
     * actor using the test child factory implementation.
      *
      * @return the number of created children
     */
    def numberOfChildrenCreated: Int = childCount.get

    /**
     * Creates the properties for the test actor.
      *
      * @return creation properties for the test actor
     */
    private def createTestActorProps(): Props = {
      Props(new MediumProcessorActor(scanResult, config,
        optId3v1ProcessorMap = Some(id3v1ProcessorMap),
        optId3v2ProcessorMap = Some(id3v2ProcessorMap), optMp3ProcessorMap = Some(mp3ProcessorMap),
        optCollectorMap = Some(collectorMap)) with ChildActorFactory {
        override def createChildActor(p: Props): ActorRef = {
          val index = childCount.getAndIncrement()
          if (index + 1 <= numberOfRealActors) super.createChildActor(p)
          else {
            val expectedProps = Mp3FileReaderActor(null)
            val extrContext = checkCreationProperties(p, expectedProps.actorClass())
            extrContext.collectorActor should not be null
            if (parentActorRef.get() != null) {
              extrContext.collectorActor should be(parentActorRef.get())
            } else {
              parentActorRef set extrContext.collectorActor
            }
            extrContext.config should be(config)
            readerActors(index - numberOfRealActors).ref
          }
        }
      })
    }

    /**
     * Creates a mock for the server config.
      *
      * @return the config mock
     */
    private def createConfigMock(): MediaArchiveConfig = {
      val config = mock[MediaArchiveConfig]
      prepareConfigMock(config)
    }
  }

}
