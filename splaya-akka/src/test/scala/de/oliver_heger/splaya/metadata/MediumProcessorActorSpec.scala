/*
 * Copyright 2015 The Developers Team.
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
package de.oliver_heger.splaya.metadata

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.splaya.config.ServerConfig
import de.oliver_heger.splaya.io.ChannelHandler
import de.oliver_heger.splaya.media.{MediaFile, MediaScanResult}
import de.oliver_heger.splaya.mp3.{ID3Header, ID3TagProvider}
import de.oliver_heger.splaya.utils.ChildActorFactory
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
  private val Medium = path("Medium")

  /** A test media scan result object. */
  private val ScanResult = createScanResult()

  /** The number of processing actors for the test root path. */
  private val ProcessorCount = 2

  /** A list containing all paths on the medium to be processed. */
  private val MediumPaths = List(path(1), path(2), path(3))

  /**
   * Generates a test path based on the passed in name.
   * @param name the name
   * @return the test path
   */
  private def path(name: String): Path = Paths get "TestPath_" + name

  /**
   * Generates a test path based on the passed in index.
   * @param idx the index
   * @return the test path
   */
  private def path(idx: Int): Path = path(idx.toString)

  /**
   * Creates a scan result for a medium which contains a couple of files.
   * @return the test scan result
   */
  private def createScanResult(): MediaScanResult = {
    val root = path("Root")
    val mediumFiles = Map(Medium -> List(MediaFile(path(1), 10)))
    MediaScanResult(root, mediumFiles, List(MediaFile(path(2), 20), MediaFile(path(3), 30)))
  }

  /**
   * Prepares a mock for the central configuration. The configuration can be
   * asked for a media root object for the root path of the test scan result.
   * @param config the mock to be initialized
   * @param readerCount the number of processor actors to be returned
   * @return the prepared mock object
   */
  private def prepareConfigMock(config: ServerConfig, readerCount: Int = ProcessorCount):
  ServerConfig = {
    when(config.rootFor(ScanResult.root)).thenReturn(Some(ServerConfig.MediaRootData(ScanResult
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
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Creates a test actor reference with a mock configuration.
   * @return a tuple with the test actor reference and the mock configuration
   */
  private def createStandardTestActor(): (TestActorRef[MediumProcessorActor], ServerConfig) = {
    val config = mock[ServerConfig]
    (TestActorRef[MediumProcessorActor](MediumProcessorActor(ScanResult, config)), config)
  }

  /**
   * Checks whether a default processor actor map was created for a given actor
   * type. This method checks whether correct creation properties have been
   * assigned to the map.
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
    helper waitForProcessing ProcessorCount should contain only (MediumPaths: _*)
  }

  it should "ignore a second Process message" in {
    val helper = MediumProcessorActorTestHelper()

    helper send ProcessMediaFiles
    helper.numberOfChildrenCreated should be(2)
  }

  /**
   * Creates a message for processing an ID3 v2 frame.
   * @param lastChunk flag whether this is the last chunk
   * @param p the optional path to the media file
   * @return the message
   */
  private def createID3FrameData(lastChunk: Boolean, p: Path = path(2)): ProcessID3FrameData = {
    val bytes = new Array[Byte](16)
    val random = new Random
    random nextBytes bytes
    ProcessID3FrameData(p, ID3Header(2, bytes.length), bytes, lastChunk)
  }

  /**
   * Helper method for checking whether an ID3v2 frame message is
   * correctly processed.
   * @param frameData the frame message
   * @return the test helper
   */
  private def checkID3v2FrameMessageHandling(frameData: ProcessID3FrameData):
  MediumProcessorActorTestHelper = {
    val helper = MediumProcessorActorTestHelper()
    val probe = helper.installProcessorActor(helper.id3v2ProcessorMap, frameData.path)
    val collector = helper installCollector frameData.path

    helper send frameData
    verify(collector).expectID3Data()
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
   * @param p the path to the processed file
   * @return the received result message
   */
  private def expectProcessingResult(p: Path): MetaDataProcessingResult = {
    expectMsg(MetaDataProcessingResult(p,
      if (MediumPaths.head == p) Some(Medium) else None, MetaData))
  }

  /**
   * Checks whether the given test probe was stopped.
   * @param probe the probe
   */
  private def checkActorStopped(probe: TestProbe): Unit = {
    val probeWatch = TestProbe()
    probeWatch watch probe.ref
    probeWatch.expectMsgType[Terminated].actor should be(probe.ref)
  }

  /**
   * Creates an ID3 frame meta data object.
   * @param p the path to be used
   * @return the meta data object
   */
  private def createID3FrameMetaData(p: Path): ID3FrameMetaData =
    ID3FrameMetaData(p, ID3Header(2, 32), Some(mock[ID3TagProvider]))

  /**
   * Checks whether a meta data result message for an ID3v2 frame is correctly
   * handled.
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

    expectProcessingResult(p)
    verify(helper.collectorMap).removeItemFor(p)
  }

  /**
   * Checks whether a meta data result for an ID3v1 frame is correctly handled.
   * @param p the path to the file
   * @param complete flag whether processing of this file is complete
   * @return the test helper for further verifications
   */
  private def checkID3v1ResultHandling(p: Path, complete: Boolean):
  MediumProcessorActorTestHelper = {
    val helper = MediumProcessorActorTestHelper()
    val id3MetaData = ID3v1MetaData(p, Some(mock[ID3TagProvider]))
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

    expectProcessingResult(p)
    verify(helper.collectorMap).removeItemFor(p)
  }

  it should "ignore ID3v1 meta data for an unknown path" in {
    val helper = MediumProcessorActorTestHelper()
    val p = path(12)
    when(helper.id3v1ProcessorMap.removeItemFor(p)).thenReturn(None)

    helper send ID3v1MetaData(p, Some(mock[ID3TagProvider]))
    verifyZeroInteractions(helper.collectorMap)
  }

  /**
   * Creates an MP3 meta data result object.
   * @param p the target path
   * @return the MP3 result object
   */
  private def createMp3Result(p: Path): Mp3MetaData =
    Mp3MetaData(p, 2, 3, 100, 96000, 128000, 180000)

  /**
   * Checks whether an MP3 meta data result is correctly handled.
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

    expectProcessingResult(p)
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
    expectProcessingResult(p)

    helper send createMp3Data(p)
    verifyZeroInteractions(helper.mp3ProcessorMap)
  }

  it should "process the next file when one is complete" in {
    val helper = new MediumProcessorActorTestHelper
    val paths = helper waitForProcessing ProcessorCount
    val reader = helper.readerActors.head

    helper.actor.tell(MediaFileRead(paths.head), reader.ref)
    val msgNextFile = reader.expectMsgType[ReadMediaFile]
    val allPaths = paths + msgNextFile.path
    allPaths should have size MediumPaths.size
    allPaths should contain only (MediumPaths: _*)
  }

  it should "create not more readers a files to process" in {
    val smallScanResult = ScanResult.copy(otherFiles = Nil)
    val helper = new MediumProcessorActorTestHelper(smallScanResult)

    helper send ProcessMediaFiles
    helper.numberOfChildrenCreated should be(1)
  }

  it should "handle an unknown root path" in {
    val helper = new MediumProcessorActorTestHelper
    when(helper.config.rootFor(ScanResult.root)).thenReturn(None)

    helper send ProcessMediaFiles
    helper.numberOfChildrenCreated should be(1)
  }

  it should "process the whole list of files" in {
    val helper = new MediumProcessorActorTestHelper

    def processPath(p: Path): Unit = {
      val probe = helper.installProcessorActor(helper.mp3ProcessorMap, p)
      helper.installProcessorActor(helper.id3v1ProcessorMap, p)
      when(helper.mp3ProcessorMap.removeItemFor(p)).thenReturn(Some(probe.ref))
      val collector = helper installCollector p
      when(helper.collectorMap.removeItemFor(p)).thenReturn(Some(collector))
      val mp3Data = createMp3Result(p)
      when(collector.setMp3MetaData(mp3Data)).thenReturn(Some(MetaData))

      val processMsg = createMp3Data(p)
      helper send processMsg
      probe.expectMsg(processMsg)
      helper.actor.tell(mp3Data, probe.ref)
      expectProcessingResult(p)
    }

    helper.waitForProcessing(2) foreach processPath
    helper.actor.tell(MediaFileRead(null), helper.readerActors.head.ref)
    val readMsg = helper.readerActors.head.expectMsgType[ReadMediaFile]
    helper send MediaFileRead(null)
    processPath(readMsg.path)

    expectMsg(MediaFilesProcessed(ScanResult))
  }

  it should "react on an exception caused by a reader actor" in {
    val mediaFiles = MediumPaths map (MediaFile(_, 128))
    val scanResult = ScanResult.copy(mediaFiles = Map(Medium -> mediaFiles), otherFiles = Nil)
    val errorPath = MediumPaths.head
    val helper = new MediumProcessorActorTestHelper(scanResult = scanResult, numberOfRealActors = 1)
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

    helper.actor ! ProcessMediaFiles
    List(probeMp3Processor, probeId3v1Processor, probeId3v2Processor) foreach checkActorStopped
    expectMsg(MetaDataProcessingResult(metaData = MediaMetaData(), path = errorPath, mediumPath =
      Some(Medium)))
    expectMsg(errorPath)

    helper send createMp3Data(errorPath)
    verify(helper.mp3ProcessorMap, never()).getOrCreateActorFor(eqArg(errorPath), any
      (classOf[ChildActorFactory]))

    val paths = helper waitForProcessing 2
    paths should contain only (MediumPaths.tail: _*)
  }

  private object MediumProcessorActorTestHelper {
    /**
     * Convenience method for creating a test helper and waiting until its
     * initialization is complete. We wait until all child actors have received
     * messages for paths to be processed.
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
   * @param scanResult the object with the files to be processed
   * @param numberOfRealActors the number of real child actors to be created;
   *                           per default, test probes are returned for child
   *                           actors; with a value greater zero, a number of
   *                           real reader actors can be created
   */
  private class MediumProcessorActorTestHelper(scanResult: MediaScanResult = ScanResult,
                                                numberOfRealActors: Int = 0) {
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
    val config = createConfigMock()

    /** A counter for the child actors created by the factory. */
    private val childCount = new AtomicInteger

    /** The test actor. */
    val actor = TestActorRef[MediumProcessorActor](createTestActorProps())

    /**
     * Waits until the given number of paths is processed by child reader
     * actors. The paths are returned.
     * @param pathCount the number of paths to wait for
     * @return a set with the paths currently processed
     */
    def waitForProcessing(pathCount: Int): Set[Path] = {
      actor ! ProcessMediaFiles
      readerActors.map(_.expectMsgType[ReadMediaFile].path).toSet
    }

    /**
     * Directly sends the specified message to the test actor (by invoking
     * receive on the test reference).
     * @param msg the message to be sent
     */
    def send(msg: Any): Unit = {
      actor receive msg
    }

    /**
     * Prepares a mock processor actor map to return a test probe when it is
     * asked for a specific actor instance.
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
     * @param p the path
     * @return the mock collector for this path
     */
    def installCollector(p: Path): MetaDataPartsCollector = {
      val collector = mock[MetaDataPartsCollector]
      when(collectorMap.getOrCreateCollector(p)).thenReturn(collector)
      collector
    }

    /**
     * Returns the number of children that have been created by the test
     * actor using the test child factory implementation.
     * @return the number of created children
     */
    def numberOfChildrenCreated: Int = childCount.get

    /**
     * Creates the properties for the test actor.
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
            extrContext.collectorActor should be(actor)
            extrContext.config should be(config)
            readerActors(index - numberOfRealActors).ref
          }
        }
      })
    }

    /**
     * Creates a mock for the server config.
     * @return the config mock
     */
    private def createConfigMock(): ServerConfig = {
      val config = mock[ServerConfig]
      prepareConfigMock(config)
    }
  }

}
