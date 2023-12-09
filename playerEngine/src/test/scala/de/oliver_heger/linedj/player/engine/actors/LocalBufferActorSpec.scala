/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.actors

import de.oliver_heger.linedj.io.*
import de.oliver_heger.linedj.player.engine.actors.BufferFileManager.BufferFile
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.*
import de.oliver_heger.linedj.player.engine.{PlayerConfig, PlayerConfigSpec}
import de.oliver_heger.linedj.shared.archive.media.{DownloadComplete, DownloadData, DownloadDataResult}
import de.oliver_heger.linedj.test.{FileTestHelper, SupervisionTestActor}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.apache.pekko.actor.*
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}
import java.util.regex.Pattern
import scala.concurrent.duration.*

object LocalBufferActorSpec:
  /** The test chunk size for IO operations. */
  val ChunkSize = 16

  /** The prefix for temporary files. */
  private val FilePrefix = "LocalBufferActorTestPrefix"

  /** The suffix for temporary files. */
  private val FileSuffix = ".tst"

  /** A configuration object used by tests. */
  private val Config = createConfig()

  /**
    * Creates a test configuration for the player.
    *
    * @return the test configuration
    */
  private def createConfig(): PlayerConfig =
    PlayerConfigSpec.TestPlayerConfig.copy(bufferFileSize = FileTestHelper.testBytes().length,
      bufferChunkSize = ChunkSize)

  /**
    * Generates the name of a test buffer file.
    *
    * @param index the index of the buffer file
    * @return the name of the buffer file with this index
    */
  private def createBufferFileName(index: Int): String = FilePrefix + index + FileSuffix

/**
  * Test class for ''LocalBufferActor''.
  */
class LocalBufferActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with AnyFlatSpecLike with ImplicitSender with BeforeAndAfterAll with BeforeAndAfterEach with Matchers
  with MockitoSugar with FileTestHelper:

  import FileTestHelper._
  import LocalBufferActorSpec._

  def this() = this(ActorSystem("LocalBufferActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  /**
    * Checks whether a correct file name was passed to a write actor.
    *
    * @param writeData the data object to be checked
    */
  private def checkWriteFileName(writeData: FileWriteData): Unit =
    writeData.path.getFileName.toString should fullyMatch regex (FilePrefix + "\\d+" + Pattern
      .quote(FileSuffix))
    writeData.path.getParent should be(testDirectory)

  "A LocalBufferActor" should "create a correct Props object" in:
    val bufferManager = mock[BufferFileManager]
    val props = LocalBufferActor(Config, bufferManager)

    props.args should have length 2
    props.args.head should be(Config)
    props.args(1) should be(bufferManager)
    val bufferActor = TestActorRef[LocalBufferActor](props)
    bufferActor.underlyingActor shouldBe a[LocalBufferActor]
    bufferActor.underlyingActor shouldBe a[ChildActorFactory]

  /**
    * Reads the data produced by a buffer file actor and returns the content of
    * the temporary file that was read.
    *
    * @param bufferActor the reader actor received from the buffer
    * @return the content of the file that was read
    */
  private def readBufferActor(bufferActor: ActorRef): String =
    val readActor = system.actorOf(Props(new Actor {
      private var result = ByteString.empty
      private var client: ActorRef = _

      override def receive: Receive = {
        case req: BufferDataRequest =>
          client = sender()
          bufferActor ! req

        case BufferDataResult(data) =>
          data.length should be <= ChunkSize
          result = result ++ data
          bufferActor ! BufferDataRequest(ChunkSize)

        case BufferDataComplete =>
          client ! BufferFileReadResult(result.utf8String)
      }
    }))

    readActor ! BufferDataRequest(ChunkSize)
    val result = expectMsgType[BufferFileReadResult]
    result.content

  it should "allow filling the buffer from a source reader" in:
    val helper = new BufferTestHelper

    helper.fillBuffer(testBytes())
      .checkNextBufferFile(testBytes())
      .expectBufferFilled()
      .checkNoMoreFiles()

  it should "create temporary files of the configured size" in:
    val AdditionalData = "more"
    val helper = new BufferTestHelper

    helper.fillBuffer(toBytes(TestData + AdditionalData))
      .checkNextBufferFile(testBytes())
      .expectBufferFilled()

  it should "create no more temporary files when the buffer is full" in:
    val helper = new BufferTestHelper

    helper.fillBuffer(toBytes(TestData * 3))
    helper.nextBufferFile()
    helper.nextBufferFile()
    helper.checkNoMoreFiles()

  it should "send a busy message for simultaneous fill requests" in:
    val helper = new BufferTestHelper
    helper.send(FillBuffer(testActor))

    expectMsgType[DownloadData]
    helper.send(FillBuffer(testActor))
    expectMsg(BufferBusy)

  it should "allow another fill operation after the previous one is complete" in:
    val AdditionalData = "Some more data"
    val helper = new BufferTestHelper

    val writeData = helper.fillBuffer(testBytes())
      .checkNextBufferFile(testBytes())
      .expectBufferFilled()
      .fillBuffer(toBytes(AdditionalData + TestData))
      .nextBufferFile()
    val dataStr = new String(writeData.data, StandardCharsets.UTF_8)
    dataStr should startWith(AdditionalData)

  it should "not write new data into the buffer when it is full" in:
    val helper = new BufferTestHelper

    helper.withFileManagerMock { bufferManager =>
      when(bufferManager.isFull).thenReturn(true)
    }.fillBuffer(testBytes())
      .checkNoMoreFiles()

  it should "send a busy message for simultaneous read requests" in:
    val helper = new BufferTestHelper

    helper.send(ReadBuffer)
      .send(ReadBuffer)
    expectMsg(BufferBusy)

  it should "support reading from the buffer" in:
    val sourceLengths = List(1L, 2L, 3L, 4L)
    val helper = new BufferTestHelper

    helper.expectReads(FileReadData(positions = sourceLengths))
      .send(ReadBuffer)
    val readActorMsg = expectMsgType[BufferReadActor]
    readActorMsg.sourceLengths should be(sourceLengths)
    readBufferActor(readActorMsg.readerActor) should be(FileTestHelper.TestData)

  it should "handle an error when opening a buffer file" in:
    val helper = new BufferTestHelper

    helper.withFileManagerMock { bufferManager =>
      when(bufferManager.read)
        .thenReturn(Some(BufferFile(Paths.get("/non/existing/file.txt"), Nil)))
    }.send(ReadBuffer)
    val readActorMsg = expectMsgType[BufferReadActor]
    val watcher = TestProbe()
    watcher watch readActorMsg.readerActor
    watcher.expectTerminated(readActorMsg.readerActor)

  it should "checkout a temporary file after it has been read" in:
    val sourceLengths = List(20180227220401L)
    val latch = new CountDownLatch(1)
    val helper = new BufferTestHelper

    helper.expectReads(FileReadData(data = "someData", positions = sourceLengths))
      .withFileManagerMock { bufferManager =>
        when(bufferManager.checkOutAndRemove()).thenAnswer((_: InvocationOnMock) => {
          latch.countDown()
          Paths get "somePath"
        })
      }.send(ReadBuffer)
    val readActorMsg = expectMsgType[BufferReadActor]
    readActorMsg.sourceLengths should be(sourceLengths)
    helper.send(LocalBufferActor.BufferReadComplete(readActorMsg.readerActor))
    latch.await(5, TimeUnit.SECONDS) shouldBe true

  it should "react on a terminated reader actor" in:
    val latch = new CountDownLatch(1)
    val helper = new BufferTestHelper

    helper.expectReads(FileReadData())
      .withFileManagerMock { bufferManager =>
        when(bufferManager.checkOutAndRemove()).thenAnswer((_: InvocationOnMock) => {
          latch.countDown()
          Paths get "somePath"
        })
      }.send(ReadBuffer)
    val readActorMsg = expectMsgType[BufferReadActor]
    readActorMsg.sourceLengths should be(Nil)
    system stop readActorMsg.readerActor
    latch.await(5, TimeUnit.SECONDS) shouldBe true

  it should "allow multiple read request in series" in:
    val Content2 = FileTestHelper.TestData.reverse
    val helper = new BufferTestHelper

    helper.expectReads(FileReadData(), FileReadData(Content2))
      .send(ReadBuffer)
    val readActorMsg1 = expectMsgType[BufferReadActor]
    readBufferActor(readActorMsg1.readerActor) should be(FileTestHelper.TestData)
    helper.send(LocalBufferActor.BufferReadComplete(readActorMsg1.readerActor))
      .send(ReadBuffer)
    val readActorMsg2 = fishForMessage(max = 3.second) {
      case BufferBusy =>
        helper send ReadBuffer
        false

      case _: BufferReadActor =>
        true
    }.asInstanceOf[BufferReadActor]
    readBufferActor(readActorMsg2.readerActor) should be(Content2)

  it should "ignore a BufferReadComplete message for an unknown actor" in:
    val bufferActor = TestActorRef(LocalBufferActor(Config, mock[BufferFileManager]))

    bufferActor receive LocalBufferActor.BufferReadComplete(testActor)

  it should "serve a read request when new data is available" in:
    val path = createDataFile()
    val probe = TestProbe()
    val helper = new BufferTestHelper

    helper.withFileManagerMock { bufferManager =>
      when(bufferManager.read).thenReturn(None, Some(BufferFile(path, Nil)))
    }.send(ReadBuffer, probe.ref)
      .fillBuffer(testBytes())
      .expectBufferFilled()
    val readActorMsg = probe.expectMsgType[BufferReadActor]
    readBufferActor(readActorMsg.readerActor) should be(FileTestHelper.TestData)

  it should "continue a fill operation after space is available again in the buffer" in:
    val helper = new BufferTestHelper

    helper.expectReads(FileReadData())
      .fillBuffer(toBytes(TestData * 3))
      .checkNextBufferFile(testBytes())
      .checkNextBufferFile(testBytes())
      .send(ReadBuffer)
    val readActorMsg1 = expectMsgType[BufferReadActor]
    helper.send(LocalBufferActor.BufferReadComplete(readActorMsg1.readerActor))
      .checkNextBufferFile(testBytes())
      .send(ReadBuffer)
    val readActorMsg2 = expectMsgType[BufferReadActor]
    helper.send(LocalBufferActor.BufferReadComplete(readActorMsg2.readerActor))
      .expectBufferFilled()

  it should "reset pending fill requests when they have been processed" in:
    val fillActor = TestProbe()
    val Content1 = FileTestHelper.TestData + "_read1"
    val Content2 = FileTestHelper.TestData + "_read2"
    val Content3 = FileTestHelper.TestData + "_read3"
    val helper = new BufferTestHelper

    def readRequest(expContent: String): ActorRef =
      helper send ReadBuffer
      val readActorMsg = expectMsgType[BufferReadActor]
      readBufferActor(readActorMsg.readerActor) should be(expContent)
      helper send LocalBufferActor.BufferReadComplete(readActorMsg.readerActor)
      readActorMsg.readerActor

    helper.withFileManagerMock { bufferManager =>
      when(bufferManager.isFull).thenReturn(true, false)
    }.expectReads(FileReadData(Content1), FileReadData(Content2), FileReadData(Content3))

    helper send FillBuffer(fillActor.ref)
    readRequest(Content1)
    val readActor = readRequest(Content2)
    val watcher = TestProbe()
    watcher watch readActor
    watcher.expectTerminated(readActor)
    helper send FillBuffer(testActor)
    expectMsg(BufferBusy)
    readRequest(Content3)

  it should "not crash when receiving an unexpected end-of-data message" in:
    val bufferActor = TestActorRef(LocalBufferActor(Config, mock[BufferFileManager]))

    bufferActor receive BufferDataComplete

  /**
    * Prepares a test for the close operation of the buffer.
    *
    * @return the buffer actor
    */
  private def prepareClosingTest(): ActorRef =
    val bufferActor = system.actorOf(LocalBufferActor(Config, mock[BufferFileManager]))

    bufferActor ! CloseRequest
    expectMsg(CloseAck(bufferActor))
    bufferActor

  it should "answer a close request if no operations are pending" in:
    prepareClosingTest()

  it should "reject a fill request after the buffer was closed" in:
    val bufferActor = prepareClosingTest()
    bufferActor ! FillBuffer(testActor)

    expectMsg(BufferBusy)

  it should "reject a read request after the buffer was closed" in:
    val bufferActor = prepareClosingTest()
    bufferActor ! ReadBuffer

    expectMsg(BufferBusy)

  it should "terminate a fill operation on closing" in:
    val TestContent = "some data"
    val filePath = testDirectory.resolve(createBufferFileName(1))
    val helper = new BufferTestHelper

    helper.fillBuffer(toBytes(TestContent))
      .expectBufferFilled()
      .send(CloseRequest)
      .expectBufferClosed()
    val writtenData = readDataFile(filePath)
    writtenData should be(TestContent)

  it should "wait on closing until a read operation is complete" in:
    val helper = new BufferTestHelper

    helper.expectReads(FileReadData())
      .send(ReadBuffer)
    val readActorMsg = expectMsgType[BufferReadActor]
    helper.send(CloseRequest)
      .send(ReadBuffer)
    expectMsg(BufferBusy)
    helper.send(LocalBufferActor.BufferReadComplete(readActorMsg.readerActor))
      .expectBufferClosed()
      .withFileManagerMock { bufferManager =>
        verify(bufferManager).checkOutAndRemove()
      }
    val watcher = TestProbe()
    watcher watch readActorMsg.readerActor
    watcher.expectTerminated(readActorMsg.readerActor)

  it should "wait on closing until a read actor was stopped" in:
    val helper = new BufferTestHelper
    helper.expectReads(FileReadData())
      .send(ReadBuffer)
    val readActorMsg = expectMsgType[BufferReadActor]
    helper.send(CloseRequest)
      .send(ReadBuffer)
    expectMsg(BufferBusy)
    system stop readActorMsg.readerActor
    helper.expectBufferClosed()
      .withFileManagerMock { bufferManager =>
        verify(bufferManager).checkOutAndRemove()
      }

  it should "not wait on closing if no read actor exists yet" in:
    val helper = new BufferTestHelper

    helper.send(ReadBuffer)
      .send(CloseRequest)
      .expectBufferClosed()

  it should "perform cleanup on closing" in:
    val helper = new BufferTestHelper

    helper.send(CloseRequest)
      .expectBufferClosed()
      .withFileManagerMock(verify(_).removeContainedPaths())

  it should "ignore read results when closing" in:
    val helper = new BufferTestHelper

    helper.send(FillBuffer(testActor))
    expectMsgType[DownloadData]
    helper.send(CloseRequest)
      .send(DownloadDataResult(ByteString(TestData.substring(0, 10))))
      .expectBufferClosed()
      .send(FillBuffer(testActor))
    expectMsg(BufferBusy)
    helper.withFileManagerMock { bufferManager =>
      verify(bufferManager, never()).createPath()
    }

  it should "allow completing the current playlist" in:
    val TestContent = toBytes("a small chunk of test data")
    val helper = new BufferTestHelper

    helper.fillBuffer(TestContent)
      .expectBufferFilled()
      .send(SequenceComplete)
      .checkNextBufferFile(TestContent)

  it should "do nothing on completion of the current playlist if no file is written" in:
    val bufferActor = system.actorOf(LocalBufferActor(Config, mock[BufferFileManager]))

    bufferActor ! SequenceComplete
    expectNoMessage(1.second)

  it should "send a busy message when completing the playlist on closing" in:
    val helper = new BufferTestHelper

    helper.fillBuffer(toBytes("a small chunk"))
      .expectBufferFilled()
      .send(CloseRequest)
      .send(SequenceComplete)
    expectMsg(BufferBusy)
    helper.expectBufferClosed()

  it should "watch the current fill actor to react on a failed read operation" in:
    val strategy = OneForOneStrategy():
      case _: IOException => Stop
    val supervisionTestActor = SupervisionTestActor(system, strategy, Props(new Actor {
      override def receive: Receive = {
        case DownloadData(_) =>
          throw new IOException("Test exception")
      }
    }))
    val fillActor = supervisionTestActor.underlyingActor.childActor
    val helper = new BufferTestHelper

    helper send LocalBufferActor.FillBuffer(fillActor)
    val filledMsg = expectMsgType[LocalBufferActor.BufferFilled]
    filledMsg.readerActor should be(fillActor)
    filledMsg.sourceLength should be(0)

  it should "not create multiple reader actors at the same time" in:
    val probe = TestProbe()
    val path = createDataFile()
    val helper = new BufferTestHelper

    helper.withFileManagerMock { bufferManager =>
      when(bufferManager.read).thenReturn(None, Some(BufferFile(path, Nil)))
    }.send(ReadBuffer, probe.ref)
      .fillBuffer(toBytes(TestData * 3))
    probe.expectMsgType[BufferReadActor]
    probe.expectNoMessage(500.millis)

  it should "record and report source lengths when filling the buffer" in:
    val data1 = toBytes("Some data 1")
    val data2 = toBytes("And some more data")
    val helper = new BufferTestHelper

    helper.fillBuffer(data1)
      .expectBufferFilled()
      .fillBuffer(data2)
      .expectBufferFilled()
      .fillBuffer(toBytes(TestData * 3))
    helper.nextBufferFile()
    helper.nextBufferFile()
    helper.withFileManagerMock { bufManager =>
      val captor = ArgumentCaptor.forClass(classOf[BufferFile])
      verify(bufManager, times(2)).append(captor.capture())
      captor.getAllValues should have size 2
      captor.getAllValues.get(0).sourceLengths should be(List(data1.length, data2.length))
      captor.getAllValues.get(1).sourceLengths should have length 0
    }

  it should "handle a failed download if the buffer is full" in:
    val expSrcLength = (2 * TestData.length / ChunkSize + 1) * ChunkSize
    // plus the part which has been downloaded and is stored in-memory
    val helper = new BufferTestHelper
    val fillActor = helper.createDownloadActor(toBytes(TestData * 3))

    helper.expectReads(FileReadData())
      .send(FillBuffer(fillActor))
      .checkNextBufferFile(testBytes())
      .checkNextBufferFile(testBytes())
    system stop fillActor
    expectNoMessage(500.millis)
    helper.send(ReadBuffer)
    val readerMsg = expectMsgType[BufferReadActor]
    helper send BufferReadComplete(readerMsg.readerActor) // removes file from buffer
    expectMsg(BufferFilled(fillActor, expSrcLength))

    helper.fillBuffer(testBytes())
      .checkNextBufferFile(testBytes())
      .withFileManagerMock { bufManager =>
        val captor = ArgumentCaptor.forClass(classOf[BufferFile])
        verify(bufManager, times(3)).append(captor.capture())
        captor.getAllValues.get(0).sourceLengths should have length 0
        captor.getAllValues.get(1).sourceLengths should have length 0
        captor.getAllValues.get(2).sourceLengths.head should be(expSrcLength)
      }

  it should "handle a read complete message while writing into the buffer" in:
    val helper = new BufferTestHelper

    helper.expectReads(FileReadData())
      .send(ReadBuffer)
      .fillBuffer(toBytes(TestData * 2 + TestData.dropRight(1)))
    val msgReadActor = expectMsgType[BufferReadActor]
    helper.send(BufferReadComplete(msgReadActor.readerActor))
      .expectBufferFilled()

  /**
    * A test helper class managing an actor to be tested and its dependencies.
    */
  private class BufferTestHelper:
    /** A queue to store the paths returned by the buffer manager. */
    private val bufferFilesQueue = new LinkedBlockingQueue[Path]

    /** A flag to record whether the buffer directory was cleared. */
    private val bufferDirectoryCleared = new AtomicBoolean

    /** A mock for the buffer file manager. */
    private val bufferManager: BufferFileManager = createBufferFileManager()

    /** The actor to be tested. */
    private val bufferActor = createBufferActor()

    /** Stores the currently active download actor. */
    private var currentDownloadActor: ActorRef = _

    /**
      * Stores the length of the source that is currently filled in the buffer.
      */
    private var currentSourceLength = 0

    /**
      * Sends the given message to the test actor.
      *
      * @param msg    the message to be sent
      * @param sender the sender reference of the message
      * @return this test helper
      */
    def send(msg: Any, sender: ActorRef = testActor): BufferTestHelper =
      bufferActor.tell(msg, sender)
      this

    /**
      * Invokes the given function with the buffer file manager mock. This can
      * be used to prepare or validate the mock.
      *
      * @param f the function acting with the mock
      * @return this test helper
      */
    def withFileManagerMock(f: BufferFileManager => Unit): BufferTestHelper =
      f(bufferManager)
      this

    /**
      * Prepares the mock for the buffer file manager to return paths to
      * temporary files with the contents and meta data specified. That way,
      * read operations from the buffer can be tested.
      *
      * @param first describes the first read operation
      * @param more  further read operations
      * @return this test helper
      */
    def expectReads(first: FileReadData, more: FileReadData*): BufferTestHelper =
      def toBufferFile(readData: FileReadData): Option[BufferFile] =
        val path = createDataFile(readData.data)
        Some(BufferFile(path, readData.positions))

      withFileManagerMock { manager =>
        when(manager.read).thenReturn(toBufferFile(first), more.map(toBufferFile): _*)
      }

    /**
      * Simulates a fill operation of the buffer with the data provided. Sends
      * a ''FillBuffer'' message to the test actor with a download actor that
      * produces the given data.
      *
      * @param data the data to be downloaded
      * @return this test helper
      */
    def fillBuffer(data: Array[Byte]): BufferTestHelper =
      val downloadActor = createDownloadActor(data)
      currentDownloadActor = downloadActor
      currentSourceLength = data.length
      send(FillBuffer(downloadActor))

    /**
      * Returns data about the next buffer file that has been written. Fails if
      * waiting for a buffer files times out.
      *
      * @return the data object describing the next buffer file
      */
    def nextBufferFile(): FileWriteData =
      val filePath = bufferFilesQueue.poll(3, TimeUnit.SECONDS)
      filePath should not be null
      FileWriteData(filePath, toBytes(readDataFile(filePath)))

    /**
      * Checks that another buffer file was written with a valid file name and
      * the given data.
      *
      * @param expData the expected data in the file
      * @return this test helper
      */
    def checkNextBufferFile(expData: Array[Byte]): BufferTestHelper =
      val writeData = nextBufferFile()
      checkWriteFileName(writeData)
      writeData.data should be(expData)
      this

    /**
      * Checks that no further buffer files have been written.
      *
      * @return this test helper
      */
    def checkNoMoreFiles(): BufferTestHelper =
      bufferFilesQueue.poll(100, TimeUnit.MILLISECONDS) should be(null)
      this

    /**
      * Expects a notification that the buffer was filled.
      *
      * @return this test helper
      */
    def expectBufferFilled(): BufferTestHelper =
      val filled = expectMsgType[BufferFilled]
      filled.readerActor should be(currentDownloadActor)
      filled.sourceLength should be(currentSourceLength)
      this

    /**
      * Expects a close confirmation message for the test actor.
      *
      * @return this test helper
      */
    def expectBufferClosed(): BufferTestHelper =
      expectMsg(CloseAck(bufferActor))
      this

    /**
      * Creates a simulated download actor that feeds the given data into the
      * test actor instance.
      *
      * @param data the data to be downloaded
      * @return the download actor
      */
    def createDownloadActor(data: Array[Byte]): ActorRef =
      system.actorOf(Props(classOf[DownloadTestActor], data))

    /**
      * Returns a flag whether the temporary files of the local buffer have
      * been removed.
      *
      * @return a flag whether the buffer manager has been asked to clear its
      *         temporary directory
      */
    def isBufferDirectoryCleared: Boolean = bufferDirectoryCleared.get()

    /**
      * Creates a mock for a buffer file manager. The mock is prepared to give some
      * default answers.
      *
      * @return the mock for the ''BufferFileManager''
      */
    private def createBufferFileManager(): BufferFileManager =
      val pathCounter = new AtomicInteger
      val tempFileCounter = new AtomicInteger
      val manager = mock[BufferFileManager]
      when(manager.isFull).thenAnswer((_: InvocationOnMock) => tempFileCounter.get() >= 2)
      when(manager.read).thenReturn(None)
      when(manager.createPath()).thenAnswer((_: InvocationOnMock) => {
        val path = createPathInDirectory(createBufferFileName(pathCounter.incrementAndGet()))
        path
      })
      doAnswer((_: InvocationOnMock) => bufferDirectoryCleared.set(true))
        .when(manager).clearBufferDirectory()
      doAnswer((inv: InvocationOnMock) => {
        tempFileCounter.incrementAndGet()
        val file = inv.getArguments.head.asInstanceOf[BufferFile]
        bufferFilesQueue.add(file.path)
        null
      }).when(manager).append(any(classOf[BufferFile]))
      when(manager.checkOutAndRemove())
        .thenAnswer((_: InvocationOnMock) => {
          tempFileCounter.decrementAndGet()
          null
        })
      manager

    /**
      * Creates a ''Props'' object for the creation of a test actor instance.
      * The actor uses a child actor factory that allows injecting test reader
      * actors.
      *
      * @return the ''Props'' object with the specified data
      */
    private def testActorProps(): Props = LocalBufferActor(Config, bufferManager)

    /**
      * Creates a new instance of the test actor and waits until it has been
      * fully initialized. (Waiting is necessary because otherwise,
      * preparations of the buffer file manager mock can cause wired
      * exceptions.)
      *
      * @return the test actor instance
      */
    private def createBufferActor(): ActorRef =
      val actor = system.actorOf(testActorProps())
      awaitCond(isBufferDirectoryCleared)
      actor


/**
  * A helper class for storing information about a file that was written into the
  * buffer.
  *
  * @param path the path of the file
  * @param data the data written
  */
private case class FileWriteData(path: Path, data: Array[Byte])

/**
  * A data class used to define a read operation from the buffer.
  *
  * A temporary file with the content specified is created, which is referenced
  * from a [[BufferFile]]; the list with positions is included as well.
  *
  * @param data      the data of the temporary file to be read
  * @param positions the list with positions
  */
private case class FileReadData(data: String = FileTestHelper.TestData, positions: List[Long] = Nil)

/**
  * A message class used to report the result of a read operation from a buffer
  * actor.
  *
  * @param content the data that was read
  */
private case class BufferFileReadResult(content: String)

/**
  * An actor that plays the role of a download actor to fill the local buffer.
  * It reacts on download requests to send pre-defined data to a test actor.
  *
  * @param data the data to be sent chunk-wise to the test actor
  */
private class DownloadTestActor(data: Array[Byte]) extends Actor:
  private var pos = 0

  override def receive: Receive =
    case DownloadData(LocalBufferActorSpec.ChunkSize) =>
      if pos >= data.length then
        sender() ! DownloadComplete
      else
        val actCount = scala.math.min(data.length - pos, LocalBufferActorSpec.ChunkSize)
        val resultData = new Array[Byte](actCount)
        System.arraycopy(data, pos, resultData, 0, actCount)
        sender() ! DownloadDataResult(ByteString(resultData))
        pos += actCount
