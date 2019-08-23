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

package de.oliver_heger.linedj.extract.id3.processor

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy, Terminated}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.extract.id3.model._
import de.oliver_heger.linedj.extract.metadata.MetaDataProvider
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.{MetaDataProcessingError, MetaDataProcessingSuccess}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object Mp3FileProcessorActorSpec {
  /** Constant for the tag size limit config property. */
  private val TagSizeLimit = 8192

  /** A test FileData object for a file to be processed. */
  private val FileSpec = FileData("someMp3File.mp3", 20170426132934L)

  /** A test processing result template passed to the test actor. */
  private val TestProcessingResult =
    MetaDataProcessingSuccess(path = "some/dir/" + FileSpec.path,
      mediumID = MediumID("some/medium", None), uri = "mp3://testSong.mp3",
      metaData = MediaMetaData())

  /** Test meta data to be returned by the collector mock. */
  private val TestMetaData = MediaMetaData(title = Some("Title"), artist = Some("Artist"))

  /** The expected processing result. */
  private val ExpectedProcessingResult = TestProcessingResult.copy(metaData = TestMetaData)

  /** Test MP3 meta data. */
  private val TestMp3MetaData = Mp3MetaData(version = 2, layer = 3, sampleRate = 64,
    minimumBitRat = 128, maximumBitRate = 128, duration = 120)

  /**
    * Creates a message to process a chunk of ID3 data.
    *
    * @param idx      the index to generate unique data
    * @param version  the version of the header
    * @param complete flag whether this is the last chunk of the frame
    * @return the test message
    */
  private def createProcessID3FrameDataMsg(idx: Int, version: Int = 2, complete: Boolean = false):
  ProcessID3FrameData =
    ProcessID3FrameData(ID3Header(version, 42), ByteString("ID3_data_" + idx), complete)

  /**
    * Generates an expected processing error message for the specified
    * exception.
    *
    * @param cause the exception
    * @return the error message
    */
  private def createFailureMessage(cause: Throwable): MetaDataProcessingError =
    MetaDataProcessingError(TestProcessingResult.path, TestProcessingResult.mediumID,
      TestProcessingResult.uri, cause)

  /**
    * A data class used to record the creation of child actors.
    *
    * @param probe the probe returned for the child actor
    * @param props the ''Props'' passed to the child actor factory
    */
  private case class ActorCreationData(probe: TestProbe, props: Props)

}

/**
  * Test class for ''Mp3FileProcessorActorSpec''.
  */
class Mp3FileProcessorActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import Mp3FileProcessorActorSpec._

  def this() = this(ActorSystem("Mp3FileProcessorActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a test ID3 frame meta data object.
    *
    * @param procMsg the process ID3 frame message
    * @return the test meta data
    */
  private def createID3MetaData(procMsg: ProcessID3FrameData): ID3FrameMetaData =
    ID3FrameMetaData(procMsg.frameHeader, Some(mock[MetaDataProvider]))

  "An Mp3FileProcessorActor" should "create a correct Props object" in {
    val metaDataActor = TestProbe()
    val props = Mp3FileProcessorActor(metaDataActor.ref, TagSizeLimit, FileSpec,
      TestProcessingResult)

    classOf[Mp3FileProcessorActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args.head should be(metaDataActor.ref)
    props.args(1) should be(TagSizeLimit)
    val collector = props.args(2).asInstanceOf[MetaDataPartsCollector]
    collector.file should be(FileSpec)
    props.args(3) should be(TestProcessingResult)
    props.args should have size 4
  }

  it should "send a ProcessMp3Data message to the mp3 processing actor" in {
    val msg = ProcessMp3Data(ByteString("Test MP3 data"))
    val helper = new Mp3FileProcessorTestHelper

    helper postMessage msg
    helper.mp3DataActor.expectMsg(msg)
    expectMsg(Mp3ChunkAck)
  }

  it should "not send an ACK message if data processing is too slow" in {
    val msg1 = ProcessMp3Data(ByteString("Test MP3 data"))
    val msg2 = ProcessMp3Data(ByteString("More MP3 data"))
    val helper = new Mp3FileProcessorTestHelper

    helper.postMessage(msg1).postMessage(msg2)
    expectMsg(Mp3ChunkAck)
    expectNoMessage(1.second)
  }

  it should "send an ACK message when MP3 processing completes" in {
    val msg1 = ProcessMp3Data(ByteString("Test MP3 data"))
    val msg2 = ProcessMp3Data(ByteString("More MP3 data"))
    val helper = new Mp3FileProcessorTestHelper

    helper.postMessage(msg1).postMessage(msg2)
    expectMsg(Mp3ChunkAck)
    helper.postMessage(Mp3DataProcessed)
    expectMsg(Mp3ChunkAck)
    helper.postMessage(Mp3DataProcessed)
    expectNoMessage(1.second)
  }

  it should "request MP3 meta data when the stream completes" in {
    val helper = new Mp3FileProcessorTestHelper

    helper sendMessage Mp3StreamCompleted
    helper.mp3DataActor.expectMsg(Mp3MetaDataRequest)
  }

  it should "handle incoming MP3 meta data if there is outstanding processing data" in {
    val helper = new Mp3FileProcessorTestHelper

    helper.sendMessage(TestMp3MetaData)
      .expectNoMetaDataResult()
    verify(helper.collector).setMp3MetaData(TestMp3MetaData)
  }

  it should "handle incoming MP3 meta data if this completes processing" in {
    val helper = new Mp3FileProcessorTestHelper
    when(helper.collector.setMp3MetaData(TestMp3MetaData)).thenReturn(Some(TestMetaData))

    helper.sendMessage(TestMp3MetaData)
      .expectMetaDataResult()
  }

  it should "handle incoming ID3v1 data if there is outstanding processing data" in {
    val metaData = ID3v1MetaData(Some(mock[MetaDataProvider]))
    val helper = new Mp3FileProcessorTestHelper

    helper.sendMessage(metaData)
      .expectNoMetaDataResult()
    verify(helper.collector).setID3v1MetaData(metaData.metaData)
  }

  it should "handle incoming ID3v1 data if this completes processing" in {
    val metaData = ID3v1MetaData(Some(mock[MetaDataProvider]))
    val helper = new Mp3FileProcessorTestHelper
    when(helper.collector.setID3v1MetaData(metaData.metaData)).thenReturn(Some(TestMetaData))

    helper.sendMessage(metaData)
      .expectMetaDataResult()
  }

  it should "handle a message to process an ID3 frame" in {
    val msg = createProcessID3FrameDataMsg(1)
    val helper = new Mp3FileProcessorTestHelper

    val frameProcessor = helper.sendMessage(msg).expectID3ProcessorActorCreation(msg)
    frameProcessor.expectMsg(msg)
    verify(helper.collector).expectID3Data(2)
  }

  it should "handle ID3 frames with multiple chunks" in {
    val msg1 = createProcessID3FrameDataMsg(1)
    val msg2 = createProcessID3FrameDataMsg(2)
    val helper = new Mp3FileProcessorTestHelper
    val frameProcessor = helper.sendMessage(msg1).expectID3ProcessorActorCreation(msg1)
    frameProcessor.expectMsg(msg1)

    helper.sendMessage(msg2).expectNoChildCreation()
    frameProcessor.expectMsg(msg2)
    verify(helper.collector, times(2)).expectID3Data(2)
  }

  it should "handle multiple ID3 frames" in {
    val msg1 = createProcessID3FrameDataMsg(1, complete = true)
    val msg2 = createProcessID3FrameDataMsg(2, version = 3)
    val helper = new Mp3FileProcessorTestHelper
    val id3Proc1 = helper.sendMessage(msg1).expectID3ProcessorActorCreation(msg1)
    id3Proc1.expectMsg(msg1)
    verify(helper.collector).expectID3Data(2)

    val id3Proc2 = helper.sendMessage(msg2).expectID3ProcessorActorCreation(msg2)
    id3Proc2.expectMsg(msg2)
    verify(helper.collector).expectID3Data(3)
  }

  it should "handle an incomplete ID3 frame message if there is a processing actor" in {
    val msg = createProcessID3FrameDataMsg(1)
    val msgInc = IncompleteID3Frame(msg.frameHeader)
    val msgNext = createProcessID3FrameDataMsg(2, version = 3)
    val helper = new Mp3FileProcessorTestHelper
    val id3Proc = helper.sendMessage(msg).expectID3ProcessorActorCreation(msg)
    id3Proc.expectMsg(msg)

    helper.sendMessage(msgInc)
    id3Proc.expectMsg(msgInc)
    helper.sendMessage(msgNext).expectID3ProcessorActorCreation(msgNext)
  }

  it should "handle an incomplete ID3 frame message if there is no processing actor" in {
    val helper = new Mp3FileProcessorTestHelper

    helper.sendMessage(IncompleteID3Frame(ID3Header(2, 100)))
      .expectNoChildCreation()
  }

  it should "handle incoming ID3v2 data if there is outstanding processing data" in {
    val procMsg = createProcessID3FrameDataMsg(1)
    val data = createID3MetaData(procMsg)
    val helper = new Mp3FileProcessorTestHelper
    val id3Proc = helper.sendMessage(procMsg).expectID3ProcessorActorCreation(procMsg)
    id3Proc.expectMsg(procMsg)

    helper.sendID3FrameData(data, id3Proc)
      .expectActorStopped(id3Proc.ref)
      .expectNoMetaDataResult()
    verify(helper.collector).addID3Data(data)
  }

  it should "handle incoming ID3v2 data if this completes processing" in {
    val procMsg = createProcessID3FrameDataMsg(1)
    val data = createID3MetaData(procMsg)
    val helper = new Mp3FileProcessorTestHelper
    when(helper.collector.addID3Data(data)).thenReturn(Some(TestMetaData))
    val id3Proc = helper.sendMessage(procMsg).expectID3ProcessorActorCreation(procMsg)
    id3Proc.expectMsg(procMsg)

    helper.sendID3FrameData(data, id3Proc)
      .expectMetaDataResult()
      .expectActorStopped(id3Proc.ref)
  }

  it should "use a supervisor strategy that stops failing child actors" in {
    val helper = new Mp3FileProcessorTestHelper

    val strategy = helper.supervisorStrategy
    strategy shouldBe a[OneForOneStrategy]
    strategy.decider.apply(new Exception) should be(SupervisorStrategy.Stop)
  }

  it should "handle a stream failure message" in {
    val msg = Mp3StreamFailure(new Exception("Test exception"))
    val helper = new Mp3FileProcessorTestHelper

    helper.sendMessage(msg)
      .expectProcessingError(msg.exception)
      .expectTestActorStopped()
  }

  it should "stop itself when processing results have been sent" in {
    val helper = new Mp3FileProcessorTestHelper
    when(helper.collector.setMp3MetaData(TestMp3MetaData)).thenReturn(Some(TestMetaData))

    helper.sendMessage(TestMp3MetaData)
      .expectTestActorStopped()
  }

  it should "fail if the mp3 processor actor dies" in {
    val exception = new Exception("mp3 processing error!")
    val helper = new Mp3FileProcessorTestHelper

    helper.initStreamFailure(exception)
    system stop helper.mp3DataActor.ref
    helper.expectProcessingError(exception)
      .expectTestActorStopped()
  }

  it should "fail if an ID3 processor actor dies" in {
    val exception = new Exception("ID3 frame error!")
    val msg = createProcessID3FrameDataMsg(1)
    val helper = new Mp3FileProcessorTestHelper
    val id3Extractor = helper.sendMessage(msg).initStreamFailure(exception)
      .expectID3ProcessorActorCreation(msg)
    id3Extractor.expectMsg(msg)

    system stop id3Extractor.ref
    helper.expectProcessingError(exception)
      .expectTestActorStopped()
  }

  it should "ACK an init message" in {
    val helper = new Mp3FileProcessorTestHelper

    helper postMessage Mp3StreamInit
    expectMsg(Mp3ChunkAck)
  }

  /**
    * A test helper class managing a test instance and dependencies.
    */
  private class Mp3FileProcessorTestHelper {
    /** A mock collector for meta data. */
    val collector: MetaDataPartsCollector = createCollector()

    /** The probe for the meta data receiver actor. */
    private val probeMetaDataActor = TestProbe()

    /** A queue for tracking child actor creations. */
    private val actorCreationQueue = new LinkedBlockingQueue[ActorCreationData]

    /** Test probe for the MP3 data processor actor. */
    private var probeMp3DataActor: TestProbe = _

    /** The test actor instance. */
    private val mp3Processor = createTestActor()

    /**
      * Returns the test probe for the MP3 data actor.
      *
      * @return the probe for the MP3 data actor
      */
    def mp3DataActor: TestProbe = probeMp3DataActor

    /**
      * Expects the creation of a child actor. Information about the new child
      * actor is returned.
      *
      * @return an object with information about the new child actor
      */
    def expectChildCreation(): ActorCreationData = {
      val data = actorCreationQueue.poll(3, TimeUnit.SECONDS)
      data should not be null
      data
    }

    /**
      * Checks that no new child actor has been created.
      *
      * @return this test helper
      */
    def expectNoChildCreation(): Mp3FileProcessorTestHelper = {
      actorCreationQueue.isEmpty shouldBe true
      this
    }

    /**
      * Directly sends a message to the test actor by calling its ''receive()''
      * method.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def sendMessage(msg: Any): Mp3FileProcessorTestHelper = {
      mp3Processor receive msg
      this
    }

    /**
      * Sends a message to the test actor using the "!" method.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def postMessage(msg: Any): Mp3FileProcessorTestHelper = {
      mp3Processor ! msg
      this
    }

    /**
      * Checks that no meta data message has been sent to the receiver actor.
      *
      * @return this test helper
      */
    def expectNoMetaDataResult(): Mp3FileProcessorTestHelper = {
      val msg = new Object
      probeMetaDataActor.ref ! msg
      probeMetaDataActor.expectMsg(msg)
      this
    }

    /**
      * Expects that a processing result was sent to the meta data receiver
      * actor.
      *
      * @return this test helper
      */
    def expectMetaDataResult(): Mp3FileProcessorTestHelper = {
      probeMetaDataActor.expectMsg(ExpectedProcessingResult)
      this
    }

    /**
      * Expects a processing error message to be sent to the receiver actor.
      *
      * @param exception the exception
      * @return this test helper
      */
    def expectProcessingError(exception: Throwable): Mp3FileProcessorTestHelper = {
      probeMetaDataActor.expectMsg(createFailureMessage(exception))
      this
    }

    /**
      * Expects the creation of a child actor for ID3 frame processing. Checks
      * the creation properties.
      *
      * @param msg the message to process ID3 frame data
      * @return the probe for the child actor
      */
    def expectID3ProcessorActorCreation(msg: ProcessID3FrameData): TestProbe = {
      val creation = expectChildCreation()
      creation.props.actorClass() should be(classOf[ID3FrameProcessorActor])
      creation.props.args should have size 2
      creation.props.args.head should be(mp3Processor)
      val extractor = creation.props.args(1).asInstanceOf[ID3FrameExtractor]
      extractor.tagSizeLimit should be(TagSizeLimit)
      extractor.header should be(msg.frameHeader)
      creation.probe
    }

    /**
      * Sends the specified ID3 frame message to the test actor from the
      * specified test probe.
      *
      * @param data the meta data
      * @param from the sending actor
      * @return this test helper
      */
    def sendID3FrameData(data: ID3FrameMetaData, from: TestProbe): Mp3FileProcessorTestHelper = {
      mp3Processor.tell(data, from.ref)
      this
    }

    /**
      * Expects that the specified actor has been stopped.
      *
      * @param actor the actor to check
      * @return this test helper
      */
    def expectActorStopped(actor: ActorRef): Mp3FileProcessorTestHelper = {
      val probeWatch = TestProbe()
      probeWatch watch actor
      probeWatch.expectMsgType[Terminated].actor should be(actor)
      this
    }

    /**
      * Expects that the test actor has been stopped.
      *
      * @return this test helper
      */
    def expectTestActorStopped(): Mp3FileProcessorTestHelper = {
      expectActorStopped(mp3Processor)
      this
    }

    /**
      * Returns the supervisor strategy from the test actor.
      *
      * @return the supervisor strategy
      */
    def supervisorStrategy: SupervisorStrategy =
      mp3Processor.underlyingActor.supervisorStrategy

    /**
      * Invokes the supervisor strategy with the given exception to initialize
      * the failure cause.
      *
      * @param cause the cause
      * @return this test helper
      */
    def initStreamFailure(cause: Throwable): Mp3FileProcessorTestHelper = {
      supervisorStrategy.decider.apply(cause)
      this
    }

    /**
      * Creates a mock collector for meta data.
      *
      * @return the mock collector
      */
    private def createCollector(): MetaDataPartsCollector = {
      val col = mock[MetaDataPartsCollector]
      when(col.file).thenReturn(FileSpec)
      when(col.setMp3MetaData(any(classOf[Mp3MetaData]))).thenReturn(None)
      when(col.setID3v1MetaData(any(classOf[Option[MetaDataProvider]]))).thenReturn(None)
      when(col.addID3Data(any(classOf[ID3FrameMetaData]))).thenReturn(None)
      col
    }

    private def createTestActor(): TestActorRef[Mp3FileProcessorActor] = {
      val actor = TestActorRef[Mp3FileProcessorActor](createTestActorProps())
      initMp3DataProcessorActor()
      actor
    }

    /**
      * Initializes the actor reference for processing MP3 data. This is a
      * child actor that is directly created by the test actor.
      */
    private def initMp3DataProcessorActor(): Unit = {
      val creation = expectChildCreation()
      creation.props.actorClass() should be(classOf[Mp3DataProcessorActor])
      creation.props.args should have size 1
      val extractor = creation.props.args.head.asInstanceOf[Mp3DataExtractor]
      extractor.getFrameCount should be(0)
      probeMp3DataActor = creation.probe
    }

    /**
      * Creates the properties for a test actor instance.
      *
      * @return creation properties
      */
    private def createTestActorProps(): Props =
      Props(new Mp3FileProcessorActor(probeMetaDataActor.ref, TagSizeLimit, collector,
        TestProcessingResult) with ChildActorFactory {
        /**
          * @inheritdoc This implementation creates a new test probe representing
          *             the child actor. A data object about the child actor
          *             creation is created and recorded.
          */
        override def createChildActor(p: Props): ActorRef = {
          val creation = ActorCreationData(TestProbe(), p)
          actorCreationQueue offer creation
          creation.probe.ref
        }
      })
  }

}
