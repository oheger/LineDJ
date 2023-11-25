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

package de.oliver_heger.linedj.archivehttp.impl.download

import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.io.MediaDownloader
import de.oliver_heger.linedj.archivehttp.temp.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.shared.archive.media.*
import de.oliver_heger.linedj.test.RecordingSchedulerSupport.SchedulerInvocation
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import de.oliver_heger.linedj.test.{RecordingSchedulerSupport, StoppableTestProbe}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, Terminated}
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.{any, eq as eqArg}
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{BlockingQueue, CountDownLatch, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration.*

object TimeoutAwareHttpDownloadActorSpec:
  /** The class of the actor for writing chunks. */
  private val ClsWriteChunkActor = classOf[WriteChunkActor]

  /** The class of the actor for downloading files. */
  private val ClsMediaFileDownloadActor = classOf[MediaFileDownloadActor]

  /** Name of the test archive. */
  private val ArchiveName = "MyTestArchive"

  /** The index of the test download actor. */
  private val DownloadIndex = 42

  /** Test value for the read chunk size. */
  private val ReadChunkSize = 500

  /** The size of data to read when a timeout occurs. */
  private val TimeoutReadSize = 800

  /** Constant for the default size of a chunk of test data. */
  private val DataChunkSize = 256

  /** Test size of the in-memory buffer. */
  private val BufferSize = 4 * DataChunkSize

  /** Test inactivity timeout value. */
  private val InactivityTimeout = 5.minutes

  /** The property defining the media folder of the test archive. */
  private val MediaPath = Uri.Path("theMediaFolder")

  /** A test transformation function passed to the test actor. */
  private val TransformFunc: MediaFileDownloadActor.DownloadTransformFunc =
    case "mp3" => Flow[ByteString].map(bs => bs.map(b => (b + 1).toByte))

  /** A test request for a medium file. */
  private val TestMediaFileRequest = MediumFileRequest(MediaFileID(MediumID("someMediumUri", Some("path")),
    "someUri"), withMetaData = true)

  /** A test request for a chunk to download. */
  private val TestDownloadRequest = DownloadData(DataChunkSize)

  /** A path to a temporary file to read. */
  private val TempFilePath = Paths get "someTempFile.mp3"

  /**
    * Generates a message with test data. The generated data consists of an
    * array of the given size whose elements have all the specified value.
    *
    * @param value the value of the data
    * @param size  the size of the data
    * @return the generated test data message
    */
  private def generateDataChunk(value: Int, size: Int = DataChunkSize): DownloadDataResult =
    val dataArray = Array.fill(size)(value.toByte)
    DownloadDataResult(ByteString(dataArray))

  /**
    * Generates a collection of download result messages.
    *
    * @param count      the number of messages to generate
    * @param startValue the start value of the first message
    * @return a collection with the generated messages
    */
  private def generateMultipleDataChunks(count: Int, startValue: Int = 1):
  Iterable[DownloadDataResult] =
    (startValue until startValue + count).map(generateDataChunk(_))

  /**
    * Generates a path for a temporary file.
    *
    * @param idx the index of the file
    * @return the corresponding path
    */
  private def generateTempPath(idx: Int): Path =
    Paths get s"TempFile$idx.tmp"

  /**
    * Generates a response message from the file writer actor. This simulates
    * that a temp file was written successfully.
    *
    * @param tempIdx the index of the temp file
    * @return the response message
    */
  private def generateWriteResponse(tempIdx: Int): WriteChunkActor.WriteResponse =
    WriteChunkActor.WriteResponse(WriteChunkActor.WriteRequest(
      generateTempPath(tempIdx), Source.empty, tempIdx))

/**
  * Test class for ''TimeoutAwareHttpDownloadActor''.
  */
class TimeoutAwareHttpDownloadActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("TimeoutAwareHttpDownloadActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  import TimeoutAwareHttpDownloadActorSpec._

  /**
    * Creates a mock configuration with test settings.
    *
    * @return the mock configuration
    */
  private def createConfig(): HttpArchiveConfig =
    val config = mock[HttpArchiveConfig]
    when(config.downloadReadChunkSize).thenReturn(ReadChunkSize)
    when(config.downloadMaxInactivity).thenReturn(InactivityTimeout)
    when(config.timeoutReadSize).thenReturn(TimeoutReadSize)
    when(config.downloadBufferSize).thenReturn(BufferSize)
    when(config.archiveName).thenReturn(ArchiveName)
    when(config.mediaPath).thenReturn(MediaPath)
    val downloader = mock[MediaDownloader]
    when(config.downloader).thenReturn(downloader)
    config

  /**
    * Checks that no more messages have been sent to the specified test
    * probe.
    *
    * @param probe the test probe
    * @return this test helper
    */
  private def expectNoMessageToActor(probe: TestProbe): Unit =
    val msgPing = new Object
    probe.ref ! msgPing
    probe.expectMsg(msgPing)

  /**
    * Expects that the given invocation of the scheduler is already canceled or
    * will be in the near future.
    *
    * @param schedulerInvocation the invocation to check
    */
  private def expectCanceledSchedule(schedulerInvocation: SchedulerInvocation): Unit =
    awaitCond(schedulerInvocation.cancellable.isCancelled, message = "Schedule not canceled.")

  /**
    * Generates an object for a read operation of a temporary file.
    *
    * @param fileIdx the index of the file
    * @return the object representing the read operation
    */
  private def generateReadOperation(fileIdx: Int): TempReadOperation =
    TempReadOperation(StoppableTestProbe(), generateTempPath(fileIdx))

  /**
    * Generates an object representing a completed read operation. The object
    * contains a default read operation and the specified request data.
    *
    * @param fileIdx the index of the file (for the read operation)
    * @param reqData the request data
    * @return the object for the completed read operation
    */
  private def generateCompletedReadOperation(fileIdx: Int, reqData: DownloadRequestData):
  CompletedTempReadOperation =
    CompletedTempReadOperation(generateReadOperation(fileIdx), Some(reqData))

  /**
    * Checks that the specified actor has been stopped.
    *
    * @param actor the actor to be checked
    */
  private def checkActorStopped(actor: ActorRef): Unit =
    val watcher = TestProbe()
    watcher watch actor
    watcher.expectMsgType[Terminated].actor should be(actor)

  /**
    * Instantiates a test actor instance to be used for a test of the
    * ''TempReadOperationHolder'' implementation. The holder of this instance
    * is returned together with a probe for the child read actor and the test
    * actor instance.
    *
    * @return the operation holder, the child reader actor, and the test actor
    */
  private def createTestActorForOperationHolderTest(): (TempReadOperationHolder, StoppableTestProbe, ActorRef) =
    val probeReader = StoppableTestProbe()
    val dependencyRef = TestProbe().ref
    val props = Props(new TimeoutAwareHttpDownloadActor(createConfig(), dependencyRef, TestMediaFileRequest,
      TransformFunc, mock[TempPathGenerator], dependencyRef, 0, None)
      with ChildActorFactory with SchedulerSupport {
      override def createChildActor(p: Props): ActorRef = {
        p.actorClass() match {
          case ClsMediaFileDownloadActor =>
            p.args should contain theSameElementsInOrderAs List(TempFilePath, ReadChunkSize,
              MediaFileDownloadActor.IdentityTransform)
            probeReader

          case _ => TestProbe().ref
        }
      }
    })

    val ref = TestActorRef[TimeoutAwareHttpDownloadActor](props)
    (ref.underlyingActor.tempFileActorManager.operationHolder, probeReader, ref)

  "A TimeoutAwareHttpDownloadActor" should "create a default temp file manager" in:
    val ref = TestActorRef[TimeoutAwareHttpDownloadActor](
      TimeoutAwareHttpDownloadActor(createConfig(), TestProbe().ref, TestMediaFileRequest, TransformFunc,
        mock[TempPathGenerator], TestProbe().ref, DownloadIndex))

    ref.underlyingActor.tempFileActorManager.downloadActor should be(ref)
    ref.underlyingActor.tempFileActorManager.operationHolder should be(ref.underlyingActor)

  it should "create correct creation properties" in:
    val downloadManager = TestProbe()
    val removeActor = TestProbe()
    val config = createConfig()
    val generator = mock[TempPathGenerator]
    val props = TimeoutAwareHttpDownloadActor(config, downloadManager.ref, TestMediaFileRequest,
      TransformFunc, generator, removeActor.ref, DownloadIndex)

    classOf[TimeoutAwareHttpDownloadActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[SchedulerSupport].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(config, downloadManager.ref, TestMediaFileRequest, TransformFunc, generator,
      removeActor.ref, DownloadIndex, None))

  it should "provide a TempReadOperationHolder that creates and manages operations" in:
    val (holder, probe, _) = createTestActorForOperationHolderTest()

    holder.currentReadOperation shouldBe empty
    val op = holder.getOrCreateCurrentReadOperation(Some(TempFilePath))
    op should be(Some(TempReadOperation(probe, TempFilePath)))
    holder.currentReadOperation should be(op)

  it should "provide a TempReadOperationHolder that handles an undefined temp path" in:
    val (holder, _, _) = createTestActorForOperationHolderTest()

    holder.getOrCreateCurrentReadOperation(None) shouldBe empty

  it should "provide a TempReadOperationHolder that resets the current read operation" in:
    val (holder, _, _) = createTestActorForOperationHolderTest()
    holder.getOrCreateCurrentReadOperation(Some(TempFilePath))

    holder.resetReadOperation()
    holder.currentReadOperation shouldBe empty

  it should "watch the reader actors created by the TempReadOperationHolder" in:
    val (holder, probe, actor) = createTestActorForOperationHolderTest()
    holder.getOrCreateCurrentReadOperation(Some(TempFilePath))

    probe.stop()
    checkActorStopped(actor)

  it should "delegate a data request to the wrapped actor and send the result" in:
    val data = generateDataChunk(1)
    val helper = new DownloadActorTestHelper

    helper.sendDataRequest()
      .expectDelegatedDataRequest()
      .passData(data)
      .expectDownloadActorCreation()
    expectMsg(data)

  it should "notify the download manager when a request is received" in:
    val helper = new DownloadActorTestHelper

    helper.sendDataRequest()
      .expectActorAliveNotification()

  it should "cancel the scheduler and schedule a new timeout on receiving data from source" in:
    val helper = new DownloadActorTestHelper

    val schedulerData = helper.sendDataRequest().passData(generateDataChunk(0)).checkAndGetSchedule()
    val schedulerData2 = helper.triggerSchedule(1)
    schedulerData.cancellable.cancelCount should be(1)
    schedulerData2.cancellable.cancelCount should be(0)
    expectMsgType[DownloadDataResult]

  it should "cancel a scheduled message when the actor is stopped" in:
    val helper = new DownloadActorTestHelper
    val schedulerData = helper.triggerSchedule(0)

    helper.stopTestActor()
    awaitCond(schedulerData.cancellable.cancelCount == 1)

  it should "react on a download inactivity notification" in:
    val helper = new DownloadActorTestHelper

    helper.simulateInactivityTimeout()
      .expectDelegatedDataRequest(DownloadData(ReadChunkSize))
      .expectNoSchedule()
      .passData(generateDataChunk(0, ReadChunkSize - 1))
      .expectDelegatedDataRequest(DownloadData(TimeoutReadSize - ReadChunkSize + 1))
      .passData(generateDataChunk(1, TimeoutReadSize))
      .checkAndGetSchedule()

  it should "reset the scheduler handle when receiving a timeout notification" in:
    val helper = new DownloadActorTestHelper
    val schedulerData = helper.triggerSchedule(1)

    helper.sendMessage(schedulerData.message)
      .stopTestActor()
    schedulerData.cancellable.cancelCount should be(0)

  it should "not process two timeout messages in a short interval" in:
    val helper = new DownloadActorTestHelper
    val schedulerData = helper.triggerSchedule(0)
    helper.expectDelegatedDataRequest()

    helper.sendMessage(schedulerData.message)
      .sendMessage(schedulerData.message)
      .expectDelegatedDataRequest(DownloadData(ReadChunkSize))
      .expectNoDelegatedDataRequest()

  it should "ignore a data request while one is in progress" in:
    val helper = new DownloadActorTestHelper
    helper.sendDataRequest().expectDelegatedDataRequest()

    helper.sendMessage(TestDownloadRequest)
      .expectNoDelegatedDataRequest()

  it should "ignore a download data response if it contains an empty byte string" in:
    val helper = new DownloadActorTestHelper

    helper.simulateInactivityTimeout()
      .expectDelegatedDataRequest(DownloadData(ReadChunkSize))
      .passData(DownloadDataResult(ByteString.empty))
      .expectNoDelegatedDataRequest()

  it should "serve a request from a buffer" in:
    val ChunkSize = 16
    val probeClient = TestProbe()
    val helper = new DownloadActorTestHelper

    helper.sendDataRequest(DownloadData(ChunkSize), client = probeClient.ref)
      .passData(generateDataChunk(1))
      .passData(generateDataChunk(2))
      .sendDataRequest(DownloadData(BufferSize))
    probeClient.expectMsg(generateDataChunk(1, ChunkSize))
    expectMsg(generateDataChunk(1, DataChunkSize - ChunkSize))
    expectNoMessageToActor(probeClient)

  it should "not reset the timeout when serving a request from buffer" in:
    val helper = new DownloadActorTestHelper
    val schedulerData1 = helper.triggerSchedule(0)
    helper.passData(generateDataChunk(1))
      .checkAndGetSchedule()
    schedulerData1.cancellable.cancelCount should be(1)

    val schedulerData2 = helper.passData(generateDataChunk(2))
      .checkAndGetSchedule()
    helper.sendDataRequest()
    expectMsgType[DownloadDataResult]
    schedulerData2.cancellable.isCancelled shouldBe false

  it should "notify the temp file manager about a pending write operation" in:
    val helper = new DownloadActorTestHelper

    helper.passMultipleDataChunks(generateMultipleDataChunks(4))
      .expectWriteFileRequest()
    helper.verifyWriteNotification(1)

  it should "send a correct request to write a temporary file" in:
    val chunks = generateMultipleDataChunks(4)
    val helper = new DownloadActorTestHelper

    val writeRequest = helper.passMultipleDataChunks(chunks)
      .expectWriteFileRequest()
    writeRequest.seqNo should be(1)
    writeRequest.target should be(generateTempPath(1))
    val futStream = writeRequest.source.runFold(List.empty[ByteString])((lst, bs) => bs :: lst)
    Await.result(futStream, 3.seconds)
      .reverse should contain theSameElementsInOrderAs chunks.map(_.data)

  it should "clear the in-memory buffer after writing a temporary file" in:
    val chunks = generateMultipleDataChunks(8)
    val helper = new DownloadActorTestHelper
    helper.passMultipleDataChunks(chunks).expectWriteFileRequest()

    val writeRequest2 = helper.expectWriteFileRequest()
    writeRequest2.seqNo should be(2)
    writeRequest2.target should be(generateTempPath(2))
    helper.verifyWriteNotification(2).sendDataRequest()
      .expectDelegatedDataRequest()
      .expectNoWriteFileRequest()

  it should "stop itself if a write actor terminates" in:
    val helper = new DownloadActorTestHelper
    helper.passMultipleDataChunks(generateMultipleDataChunks(4))
      .expectWriteFileRequest()

    helper.stopWriteActor()
      .checkTestActorStopped()

  it should "create a write actor on demand only" in:
    val helper = new DownloadActorTestHelper
    helper.sendDataRequest().passData(generateDataChunk(1))
    expectMsgType[DownloadDataResult]

    helper.numberOfWriteActors should be(0)

  it should "pass a write file response to the temp file manager" in:
    val response = WriteChunkActor.WriteResponse(WriteChunkActor.WriteRequest(
      generateTempPath(5), Source.empty, 42))
    val helper = new DownloadActorTestHelper

    helper.sendMessage(response)
      .verifyFileWrittenNotification(response)

  it should "pass a download result from a temp reader actor to the temp file manager" in:
    val result = generateDataChunk(3)
    val helper = new DownloadActorTestHelper

    helper.passDataFromTempFile(result)
      .verifyDownloadResultPropagated(result)

  it should "delegate a request to the temp file manager if it can handle it" in:
    val helper = new DownloadActorTestHelper

    helper.enableTempManagerRequestProcessing()
      .sendDataRequest()
      .awaitRequestToTempManager()
      .expectNoDelegatedDataRequest()
      .expectActorAliveNotification()

  it should "handle download complete if a new request arrives" in:
    val helper = new DownloadActorTestHelper

    helper.passDownloadComplete().sendDataRequest()
    expectMsg(DownloadComplete)

  it should "cancel the timeout scheduler on receiving download complete" in:
    val helper = new DownloadActorTestHelper
    val scheduleData = helper.triggerSchedule(0)

    helper.passDownloadComplete()
    scheduleData.cancellable.isCancelled shouldBe true

  it should "handle download complete if a request is pending" in:
    val helper = new DownloadActorTestHelper

    helper.sendDataRequest().expectDelegatedDataRequest().passDownloadComplete()
    expectMsg(DownloadComplete)
    helper.expectNoDelegatedDataRequest()
      .expectNoSchedule()

  it should "handle download complete after the buffer has been consumed" in:
    val helper = new DownloadActorTestHelper
    helper.passData(generateDataChunk(1)).passDownloadComplete()
      .sendDataRequest()

    expectMsgType[DownloadDataResult]
    helper.sendDataRequest()
    expectMsg(DownloadComplete)

  it should "ignore a timeout message after download complete" in:
    val helper = new DownloadActorTestHelper
    helper.triggerSchedule(0)
    helper.passDownloadComplete()

    helper.simulateInactivityTimeout()
      .expectNoDelegatedDataRequest()

  it should "react on a temp file complete message if a request is pending" in:
    val RequestSize = DataChunkSize / 2
    val requestData = DownloadRequestData(DownloadData(RequestSize), testActor)
    val helper = new DownloadActorTestHelper

    helper.sendMessage(generateWriteResponse(1))
      .passData(generateDataChunk(1))
      .expectTempReaderCompleteMessage(Some(generateCompletedReadOperation(1, requestData)))
      .passDownloadCompleteFromTempFile()
    expectMsg(generateDataChunk(1, RequestSize))

  it should "react on a temp file complete message handled by the temp manager" in:
    val completedOp = CompletedTempReadOperation(generateReadOperation(1), None)
    val helper = new DownloadActorTestHelper

    helper.sendMessage(generateWriteResponse(1))
      .expectTempReaderCompleteMessage(Some(completedOp))
      .sendMessage(DownloadComplete)
      .expectNoDelegatedDataRequest()

  it should "react on a temp file complete message rejected by the temp manager" in:
    val helper = new DownloadActorTestHelper

    helper.sendMessage(generateWriteResponse(1))
      .expectTempReaderCompleteMessage(None)
      .sendMessage(DownloadComplete)
      .expectNoDelegatedDataRequest()

  it should "react on a temp file complete message if download is complete" in:
    val requestData = DownloadRequestData(TestDownloadRequest, testActor)
    val helper = new DownloadActorTestHelper

    helper.sendMessage(generateWriteResponse(1))
      .expectTempReaderCompleteMessage(Some(generateCompletedReadOperation(1, requestData)))
      .passDownloadComplete()
      .passDownloadCompleteFromTempFile()
    expectMsg(DownloadComplete)

  it should "remove a temporary file after it has been read" in:
    val readOp = generateReadOperation(5)
    val completedOp = CompletedTempReadOperation(readOp, None)
    val helper = new DownloadActorTestHelper

    helper.expectTempReaderCompleteMessage(Some(completedOp))
      .passDownloadCompleteFromTempFile()
      .expectTempFilesRemoved(List(readOp.path))

  it should "stop a reader actor after a file has been read" in:
    val readOp = generateReadOperation(2)
    val completedOp = CompletedTempReadOperation(readOp, None)
    val helper = new DownloadActorTestHelper

    helper.registerAsWatcher(readOp.reader)
      .expectTempReaderCompleteMessage(Some(completedOp))
      .passDownloadCompleteFromTempFile()
    checkActorStopped(readOp.reader)

    // check that the test actor is still alive
    helper.sendDataRequest().expectDelegatedDataRequest()

  it should "stop the wrapped download actor when it terminates" in:
    val helper = new DownloadActorTestHelper

    helper.stopTestActor()
      .checkWrappedDownloadActorStopped()

  it should "remove remaining temporary files when it terminates" in:
    val paths = Set(Paths get "file1.tmp", Paths get "file2.tmp",
      Paths get "more.tmp")
    val helper = new DownloadActorTestHelper

    helper.setPendingTempFiles(paths)
      .stopTestActor()
      .expectTempFilesRemoved(paths)

  it should "not send a remove message if there are no remaining temp files" in:
    val helper = new DownloadActorTestHelper

    helper.stopTestActor()
      .checkTestActorStopped()
      .expectNoTempFilesRemoved()

  it should "stop itself if the wrapped download actor dies without returning data" in:
    val helper = new DownloadActorTestHelper

    helper.stopWrappedDownloadActor()
      .checkTestActorStopped()

  it should "resume a failed download directly if no temp files are present" in:
    val probeClient = TestProbe()
    val helper = new DownloadActorTestHelper
    val schedulerInvocation = helper.sendDataRequest(DownloadData(16), client = probeClient.ref)
      .expectDownloadActorCreation()
      .passData(generateDataChunk(1))
      .expectAndCheckSchedule()
      .passData(generateDataChunk(2))
      .checkAndGetSchedule()

    helper.stopWrappedDownloadActor()
      .expectDownloadActorCreation(bytesToSkip = 2 * DataChunkSize)
      .checkAndGetSchedule(1.second)
    schedulerInvocation.cancellable.isCancelled shouldBe true

  it should "resume a failed download later if temp files are present" in:
    val probeClient = TestProbe()
    val helper = new DownloadActorTestHelper

    helper.sendDataRequest(DownloadData(DataChunkSize / 2), client = probeClient.ref)
      .expectDownloadActorCreation()
      .passData(generateDataChunk(1))
      .setPendingTempFiles(Set(Paths get "temp.tmp"))
      .stopWrappedDownloadActor()
      .expectAndCheckCanceledSchedule()
      .expectNoSchedule()
      .setPendingTempFiles(Set.empty)
      .sendDataRequest(DownloadData(16), client = probeClient.ref)
      .checkAndGetSchedule(1.second)

  it should "not resume a failed download as long as temp files are present" in:
    val probeClient = TestProbe()
    val helper = new DownloadActorTestHelper

    helper.sendDataRequest(DownloadData(DataChunkSize / 2), client = probeClient.ref)
      .expectDownloadActorCreation()
      .passData(generateDataChunk(1))
      .setPendingTempFiles(Set(Paths get "temp.tmp"))
      .stopWrappedDownloadActor()
      .expectAndCheckCanceledSchedule()
      .expectNoSchedule()
      .sendDataRequest(DownloadData(16), client = probeClient.ref)
      .expectNoSchedule()

  it should "not explicitly resume a failed download if data is requested from the download actor anyway" in:
    val probeClient = TestProbe()
    val helper = new DownloadActorTestHelper

    helper.sendDataRequest(DownloadData(DataChunkSize), client = probeClient.ref)
      .expectDownloadActorCreation()
      .passData(generateDataChunk(1))
      .setPendingTempFiles(Set(Paths get "temp.tmp"))
      .stopWrappedDownloadActor()
      .expectAndCheckCanceledSchedule()
      .expectNoSchedule()
      .setPendingTempFiles(Set.empty)
      .sendDataRequest(DownloadData(16), client = probeClient.ref)
      .expectNoSchedule()

  it should "use a separate counter for received bytes per download attempt" in:
    val probeClient = TestProbe()
    val helper = new DownloadActorTestHelper

    helper.sendDataRequest(DownloadData(DataChunkSize), client = probeClient.ref)
      .expectDownloadActorCreation()
      .passData(generateDataChunk(1))
      .setPendingTempFiles(Set(Paths get "temp.tmp"))
      .stopWrappedDownloadActor()
      .expectDownloadActorCreation(bytesToSkip = DataChunkSize)
      .expectAndCheckCanceledSchedule()
      .stopWrappedDownloadActor()
      .checkTestActorStopped()

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class DownloadActorTestHelper:
    /** Test probe for the download manager actor. */
    private val probeDownloadManager = TestProbe()

    /**
      * A reference holding the current test probe for the wrapped download
      * actor. This reference can change when a new instance is created.
      */
    private val downloadActorProbeHolder = new AtomicReference[StoppableTestProbe]

    /** Test probe for the remove actor. */
    private val probeRemoveActor = TestProbe()

    /** Test probe for the write actor. */
    private val probeWriteActor = StoppableTestProbe()

    /** Test probe for a temp file reader actor. */
    private val probeReaderActor = TestProbe()

    /** A counter for the number of write actors that have been created. */
    private val writeActorCounter = new AtomicInteger

    /** Queue to track invocations of the scheduler. */
    private val schedulerInvocationsQueue = new LinkedBlockingQueue[SchedulerInvocation]

    /** Queue to track creations of child download actors. */
    private val downloadActorCreationsQueue = new LinkedBlockingQueue[Props]

    /** Latch to wait until the temp manager was triggered. */
    private val latchTempManagerRequest = new CountDownLatch(1)

    /** Mock for the path generator. */
    private val pathGenerator = createPathGenerator()

    /** Mock for the temp file manager. */
    private val tempFileManager = createTempFileManager()

    /** The actor to be tested. */
    private val downloadActor = createTestActor()

    /**
      * Sends the specified message directly to the test actor by invoking its
      * ''receive'' method.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def sendMessage(msg: Any): DownloadActorTestHelper =
      downloadActor receive msg
      this

    /**
      * Sends a request for data to the test actor on behalf of the provided
      * client actor.
      *
      * @param request the request to be sent
      * @param client  the client actor
      * @return this test helper
      */
    def sendDataRequest(request: DownloadData = TestDownloadRequest, client: ActorRef = testActor):
    DownloadActorTestHelper =
      downloadActor.tell(request, client)
      this

    /**
      * Expects that a request for download data was delegated to the wrapped
      * download actor.
      *
      * @param request the expected request
      * @return this test helper
      */
    def expectDelegatedDataRequest(request: DownloadData = TestDownloadRequest):
    DownloadActorTestHelper =
      probeDownloadActor.expectMsg(request)
      this

    /**
      * Verifies that no request was sent to the wrapped download actor.
      *
      * @return this test helper
      */
    def expectNoDelegatedDataRequest(): DownloadActorTestHelper =
      expectNoMessageToActor(probeDownloadActor)
      this

    /**
      * Simulates incoming data for the test actor from the download actor.
      *
      * @param data the data object
      * @return this test helper
      */
    def passData(data: DownloadDataResult): DownloadActorTestHelper =
      downloadActor ! data
      this

    /**
      * Simulates incoming data for the test actor from reading a temporary
      * file.
      *
      * @param data the data object
      * @return this test helper
      */
    def passDataFromTempFile(data: DownloadDataResult): DownloadActorTestHelper =
      downloadActor.tell(data, probeReaderActor.ref)
      this

    /**
      * Sends a number of data chunks to the test actor.
      *
      * @param chunks the chunks to be sent
      * @return this test helper
      */
    def passMultipleDataChunks(chunks: Iterable[DownloadDataResult]): DownloadActorTestHelper =
      chunks foreach passData
      this

    /**
      * Sends a download complete message to the test actor.
      *
      * @return this test helper
      */
    def passDownloadComplete(): DownloadActorTestHelper =
      downloadActor ! DownloadComplete
      this

    /**
      * Sends a download complete message to the test actor from the current
      * file reader actor.
      *
      * @return this test helper
      */
    def passDownloadCompleteFromTempFile(): DownloadActorTestHelper =
      downloadActor.tell(DownloadComplete, probeReaderActor.ref)
      this

    /**
      * Expects that a download alive message has been sent to the download
      * manager actor.
      *
      * @return this test helper
      */
    def expectActorAliveNotification(): DownloadActorTestHelper =
      val message = probeDownloadManager.expectMsgType[DownloadActorAlive]
      message.reader should be(downloadActor)
      message.fileID.mediumID should be(MediumID.UndefinedMediumID)
      this

    /**
      * Expects that a message was scheduled to prevent that the download gets
      * canceled due to inactivity and returns a data object with the details.
      * Some key properties are verified.
      *
      * @param expDelay the expected delay for the schedule
      * @return the data object with the scheduler invocation
      */
    def checkAndGetSchedule(expDelay: FiniteDuration = InactivityTimeout): SchedulerInvocation =
      val data = RecordingSchedulerSupport expectInvocation schedulerInvocationsQueue
      data.receiver should be(downloadActor)
      data.interval should be(null)
      data.initialDelay should be(expDelay)
      data

    /**
      * Expects that a message was scheduled to prevent an inactivity timeout
      * and verifies some key properties.
      *
      * @return this test helper
      */
    def expectAndCheckSchedule(): DownloadActorTestHelper =
      checkAndGetSchedule()
      this

    /**
      * Checks that no timeout prevention message was scheduled.
      *
      * @return this test helper
      */
    def expectNoSchedule(): DownloadActorTestHelper =
      schedulerInvocationsQueue shouldBe empty
      this

    /**
      * Expects that a message was scheduled to prevent an inactivity timeout,
      * but this schedule has been canceled again. Some key properties of the
      * scheduler invocation are checked.
      *
      * @return this test helper
      */
    def expectAndCheckCanceledSchedule(): DownloadActorTestHelper =
      val invocation = checkAndGetSchedule()
      expectCanceledSchedule(invocation)
      this

    /**
      * Sends messages to the test actor to provoke a scheduler invocation.
      * This invocation is returned.
      *
      * @param dataValue the value in the data chunk sent to the actor
      * @return the ''SchedulerInvocation''
      */
    def triggerSchedule(dataValue: Int): SchedulerInvocation =
      val probeClient = TestProbe()
      sendDataRequest(client = probeClient.ref)
      passData(generateDataChunk(dataValue))
      checkAndGetSchedule()

    /**
      * Sends a message to the test actor indicating a too long download
      * inactivity.
      *
      * @return this test helper
      */
    def simulateInactivityTimeout(): DownloadActorTestHelper =
      val schedulerData = triggerSchedule(0)
      downloadActor receive schedulerData.message
      expectDelegatedDataRequest()

    /**
      * Expects that a temporary file was written.
      *
      * @return the request to write a file
      */
    def expectWriteFileRequest(): WriteChunkActor.WriteRequest =
      probeWriteActor.expectMsgType[WriteChunkActor.WriteRequest]

    /**
      * Checks that no request to write a file was sent.
      *
      * @return this test helper
      */
    def expectNoWriteFileRequest(): DownloadActorTestHelper =
      expectNoMessageToActor(probeWriteActor)
      this

    /**
      * Stops the current writer actor to test that this error is handled.
      *
      * @return this test helper
      */
    def stopWriteActor(): DownloadActorTestHelper =
      probeWriteActor.stop()
      this

    /**
      * Verifies that the temp file manager has been notified about a
      * pending write operation.
      *
      * @param idx the index of the file to be written
      * @return this test helper
      */
    def verifyWriteNotification(idx: Int): DownloadActorTestHelper =
      verify(tempFileManager).pendingWriteOperation(idx)
      this

    /**
      * Verifies that the temp file manager has been notified about a
      * completed write operation.
      *
      * @param msg the response message
      * @return this test helper
      */
    def verifyFileWrittenNotification(msg: WriteChunkActor.WriteResponse):
    DownloadActorTestHelper =
      verify(tempFileManager).tempFileWritten(msg)
      this

    /**
      * Verifies that a download data result message from a reader actor for
      * temporary files is passed to the temp file manager.
      *
      * @param result the result object
      * @return this test helper
      */
    def verifyDownloadResultPropagated(result: DownloadDataResult):
    DownloadActorTestHelper =
      verify(tempFileManager).downloadResultArrived(result)
      this

    /**
      * Prepares the mock for the temp file manager to declare that it can
      * handle a request. When the temp file manager is invoked, a countdown
      * latch is decremented, so that test code can wait for this event.
      *
      * @return this test helper
      */
    def enableTempManagerRequestProcessing(): DownloadActorTestHelper =
      when(tempFileManager.initiateClientRequest(testActor, TestDownloadRequest))
        .thenAnswer((_: InvocationOnMock) => {
          latchTempManagerRequest.countDown()
          true
        })
      this

    /**
      * Prepares the mock for the temp file manager to expect a download
      * completed message and to return the provided response.
      *
      * @param response the response to be returned
      * @return this test helper
      */
    def expectTempReaderCompleteMessage(response: Option[CompletedTempReadOperation]):
    DownloadActorTestHelper =
      when(tempFileManager.downloadCompletedArrived()).thenReturn(response)
      this

    /**
      * Waits until a request to the temp file manager occurs.
      *
      * @return this test helper
      */
    def awaitRequestToTempManager(): DownloadActorTestHelper =
      latchTempManagerRequest.await(3L, TimeUnit.SECONDS) shouldBe true
      this

    /**
      * Prepares the mock temp manager to return the specified set of
      * pending temporary paths.
      *
      * @param files the set with paths to be returned
      * @return this test helper
      */
    def setPendingTempFiles(files: Set[Path]): DownloadActorTestHelper =
      when(tempFileManager.pendingTempPaths).thenReturn(files)
      this

    /**
      * Returns the number of write actors that have been created.
      *
      * @return the number of write actors
      */
    def numberOfWriteActors: Int = writeActorCounter.get()

    /**
      * Expects that a child download actor has been created with the given
      * number of bytes to skip.
      *
      * @param bytesToSkip the expected number of bytes to skip
      * @return this test helper
      */
    def expectDownloadActorCreation(bytesToSkip: Long = 0): DownloadActorTestHelper =
      val creationProps = downloadActorCreationsQueue.poll(3, TimeUnit.SECONDS)
      creationProps should not be null
      creationProps.args(3) should be(bytesToSkip)
      this

    /**
      * Stops the test actor instance. This can be used to check clean-up
      * behavior. Waits until the terminated confirmation is received.
      *
      * @return this test helper
      */
    def stopTestActor(): DownloadActorTestHelper =
      system stop downloadActor
      checkTestActorStopped()

    /**
      * Stops the file download actor wrapped by the test actor.
      *
      * @return this test helper
      */
    def stopWrappedDownloadActor(): DownloadActorTestHelper =
      system stop downloadActorProbeHolder.get().ref
      this

    /**
      * Register the test actor as watcher for the specified actor.
      *
      * @param actor the actor to watch
      * @return this test helper
      */
    def registerAsWatcher(actor: ActorRef): DownloadActorTestHelper =
      downloadActor.underlyingActor.context watch actor
      this

    /**
      * Checks that the test actor instance has stopped.
      *
      * @return this test helper
      */
    def checkTestActorStopped(): DownloadActorTestHelper =
      checkActorStopped(downloadActor)
      this

    /**
      * Checks that the wrapped download actor has been stopped.
      *
      * @return this test helper
      */
    def checkWrappedDownloadActorStopped(): DownloadActorTestHelper =
      checkActorStopped(probeDownloadActor)
      this

    /**
      * Checks that a message to remove the specified paths has been sent to
      * the remove file actor.
      *
      * @param paths the paths to be removed
      * @return this test helper
      */
    def expectTempFilesRemoved(paths: Iterable[Path]): DownloadActorTestHelper =
      probeRemoveActor.expectMsg(RemoveTempFilesActor.RemoveTempFiles(paths))
      this

    /**
      * Checks that no message to remove files has been sent to the remove file
      * actor.
      *
      * @return this test helper
      */
    def expectNoTempFilesRemoved(): DownloadActorTestHelper =
      expectNoMessageToActor(probeRemoveActor)
      this

    /**
      * Creates a mock for the temp file manager and initializes it in a way
      * that it does not handle invocations per default.
      *
      * @return the mock temp file manager
      */
    private def createTempFileManager(): TempFileActorManager =
      val tm = mock[TempFileActorManager]
      when(tm.initiateClientRequest(any(), any())).thenReturn(false)
      when(tm.pendingTempPaths).thenReturn(List.empty)
      tm

    /**
      * Generates a mock for the temp path generator. This mock returns
      * special test paths.
      *
      * @return the mock path generator
      */
    private def createPathGenerator(): TempPathGenerator =
      val generator = mock[TempPathGenerator]
      when(generator.generateDownloadPath(eqArg(ArchiveName), eqArg(DownloadIndex), any()))
        .thenAnswer((invocation: InvocationOnMock) => generateTempPath(invocation.getArguments()(2).asInstanceOf[Int]))
      generator

    /**
      * Returns the test probe for the current download actor.
      *
      * @return the test probe for the current download actor
      */
    private def probeDownloadActor: StoppableTestProbe =
      val probe = downloadActorProbeHolder.get()
      probe should not be null
      probe

    /**
      * Creates the test actor instance.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[TimeoutAwareHttpDownloadActor] =
      val config = createConfig()
      TestActorRef(Props(new TimeoutAwareHttpDownloadActor(config, probeDownloadManager.ref, TestMediaFileRequest,
        TransformFunc, pathGenerator, probeRemoveActor.ref, DownloadIndex, Some(tempFileManager))
        with ChildActorFactory with RecordingSchedulerSupport {
        override val queue: BlockingQueue[SchedulerInvocation] = schedulerInvocationsQueue

        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() match {
            case ClsWriteChunkActor =>
              p.args should have size 0
              writeActorCounter.incrementAndGet() should be(1)
              probeWriteActor.ref

            case _ =>
              val expArgs = List(config, TestMediaFileRequest, TransformFunc)
              val expDownloadActorProps = HttpDownloadActor(config, TestMediaFileRequest, TransformFunc)
              p.actorClass() should be(expDownloadActorProps.actorClass())
              p.args.slice(0, expArgs.size) should contain theSameElementsInOrderAs expArgs
              p.args should have size expArgs.size + 1
              downloadActorCreationsQueue offer p
              val probe = StoppableTestProbe()
              downloadActorProbeHolder.set(probe)
              probe
          }
        }

        override def currentReadOperation: Option[TempReadOperation] =
          Some(TempReadOperation(probeReaderActor.ref, TempFilePath))
      }))
