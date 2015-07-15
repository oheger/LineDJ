package de.oliver_heger.splaya.playback

import java.io.{ByteArrayOutputStream, File, IOException}
import java.nio.file.{FileSystems, Path}
import java.util.regex.Pattern

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import de.oliver_heger.splaya.{SupervisionTestActor, FileTestHelper}
import de.oliver_heger.splaya.io.ChannelHandler.{ArraySource, InitFile}
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData, ReadResult}
import de.oliver_heger.splaya.io.FileWriterActor.{WriteResult, WriteResultStatus}
import de.oliver_heger.splaya.io._
import de.oliver_heger.splaya.utils.ChildActorFactory
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

object LocalBufferActorSpec {
  /** The test chunk size for IO operations. */
  private val ChunkSize = 16

  /** The prefix for temporary files. */
  private val FilePrefix = "LocalBufferActorTestPrefix"

  /** The suffix for temporary files. */
  private val FileSuffix = ".tst"

  /** A path pointing to the test buffer directory. */
  private val BufferPath = FileSystems.getDefault.getPath("test")
}

/**
 * Test class for ''LocalBufferActor''.
 */
class LocalBufferActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
with FlatSpecLike with ImplicitSender with BeforeAndAfterAll with Matchers
with MockitoSugar {

  import de.oliver_heger.splaya.FileTestHelper._
  import de.oliver_heger.splaya.playback.LocalBufferActor._
  import de.oliver_heger.splaya.playback.LocalBufferActorSpec._

  def this() = this(ActorSystem("LocalBufferActorSpec",
    ConfigFactory.parseString(s"""
      |splaya {
      | buffer {
      |   fileSize = ${FileTestHelper.testBytes().length}
      |   chunkSize = ${LocalBufferActorSpec.ChunkSize}
      |   filePrefix = ${LocalBufferActorSpec.FilePrefix}
      |   fileExtension = ${LocalBufferActorSpec.FileSuffix}
      | }
      |}
    """.stripMargin)))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A LocalBufferActor" should "create a correct Props object" in {
    val bufferManager = mock[BufferFileManager]
    val props = LocalBufferActor(Some(bufferManager))

    props.args should have length 1
    props.args.head should be (Some(bufferManager))
    val bufferActor = TestActorRef[LocalBufferActor](props)
    bufferActor.underlyingActor shouldBe a[LocalBufferActor]
    bufferActor.underlyingActor shouldBe a[ChildActorFactory]
    bufferActor.underlyingActor.bufferManager should be (bufferManager)
  }

  /**
   * Creates a canonical path from the given string. This means that a trailing
   * separator character is removed if it is present.
   * @param path the path in question
   * @return the canonical path
   */
  private def canonicalPath(path: String): String =
    if (path(path.length - 1) == File.separatorChar) path.substring(0, path.length - 1)
    else path

  it should "create a default buffer file manager" in {
    val bufferActor = TestActorRef[LocalBufferActor](LocalBufferActor())
    val bufferManager = bufferActor.underlyingActor.bufferManager
    bufferManager.prefix should be(FilePrefix)
    bufferManager.extension should be(FileSuffix)
    canonicalPath(bufferManager.directory.toString) should be(canonicalPath(System getProperty
      "java.io.tmpdir"))
  }

  /**
   * Simulates a buffer fill operation via a reader actor. This method behaves
   * like a file reader actor and answers requests from the buffer actor. The
   * provided test data is filled into the buffer.
   * @param bufferActor the test actor
   * @param data the data to be filled into the buffer
   * @return a list with information about the files written into the buffer
   */
  private def simulateFilling(bufferActor: ActorRef, data: Array[Byte]): List[FileWriteData] = {
    val listBuffer = ListBuffer.empty[FileWriteData]
    var currentStream: ByteArrayOutputStream = null
    var currentPath: Path = null
    var pos = 0
    var bytesWritten = 0

    fishForMessage() {
      case FileReaderActor.ReadData(count) =>
        count should be(ChunkSize)
        if (pos >= data.length) {
          bufferActor ! FileReaderActor.EndOfFile(null)
          false
        } else {
          val actCount = scala.math.min(data.length - pos, count)
          val resultData = new Array[Byte](actCount)
          System.arraycopy(data, pos, resultData, 0, actCount)
          bufferActor ! FileReaderActor.ReadResult(resultData, actCount)
          pos += actCount
          false
        }

      case ChannelHandler.InitFile(path) =>
        currentPath = path
        currentStream = new ByteArrayOutputStream
        bytesWritten = 0
        false

      case source: ArraySource =>
        currentStream.write(source.data, source.offset, source.length)
        bufferActor ! WriteResult(WriteResultStatus.Ok, source.length)
        bytesWritten += source.length
        false

      case CloseRequest =>
        listBuffer += FileWriteData(currentPath, currentStream.toByteArray)
        bufferActor ! CloseAck(testActor)
        bytesWritten = 0
        false

      case BufferFilled(actor, sourceLength) =>
        actor should be(testActor)
        sourceLength should be (data.length)
        true
    }

    if (bytesWritten > 0) {
      listBuffer += FileWriteData(currentPath, currentStream.toByteArray)
    }
    listBuffer.toList
  }

  /**
   * Creates a mock for a buffer file manager. The mock is prepared to give some
   * default answers.
   * @return the mock for the ''BufferFileManager''
   */
  private def createBufferFileManager(): BufferFileManager = {
    val manager = mock[BufferFileManager]
    when(manager.isFull).thenReturn(false)
    when(manager.read).thenReturn(None)
    when(manager.createPath()).then(new Answer[Path] {
      override def answer(invocation: InvocationOnMock): Path =
        BufferPath.resolve(FilePrefix + System.nanoTime() + FileSuffix)
    })
    manager
  }

  /**
   * Helper method for resolving an optional actor reference. If not specified,
   * the test actor is used.
   * @param ref the optional actor reference
   * @return the resolved reference
   */
  private def fetchReference(ref: Option[ActorRef]): ActorRef = ref.getOrElse(testActor)

  /**
   * Creates a ''Props'' object for instantiating a buffer actor with a mock
   * child actor factory that returns the provided actor references. If no
   * actors are specified, the implicit test actor is used.
   * @param readerActor an optional reader actor
   * @param writerActor an optional writer actor
   * @param bufferManager an optional buffer manager object
   * @return the ''Props'' object with the specified data
   */
  private def propsWithMockFactory(readerActor: Option[ActorRef] = None,
                                   writerActor: Option[ActorRef] = None, bufferManager:
  Option[BufferFileManager] = None): Props =
    propsWithMockFactoryForMultipleReaders(List(fetchReference(readerActor)), writerActor,
      bufferManager)

  /**
   * Creates a ''Props'' object for instantiating a buffer actor with a mock
   * child actor factory that returns multiple reader actor references. This
   * method can be used if multiple reader actors are involved.
   * @param readers a list with reader actors
   * @param writerActor an optional writer actor
   * @param bufferManager an optional buffer manager object
   * @return the ''Props'' object with the specified data
   */
  private def propsWithMockFactoryForMultipleReaders(readers: List[ActorRef], writerActor:
  Option[ActorRef], bufferManager: Option[BufferFileManager]): Props = {
    Props(new LocalBufferActor(bufferManager orElse Some(createBufferFileManager()))
      with ChildActorFactory {
      var readerList = readers

      override def createChildActor(p: Props): ActorRef = {
        p.args shouldBe 'empty
        val ReaderClass = classOf[FileReaderActor]
        val WriterClass = classOf[FileWriterActor]
        p.actorClass() match {
          case ReaderClass =>
            val readerActor = readerList.head
            readerList = readerList.tail
            readerActor

          case WriterClass =>
            fetchReference(writerActor)
        }
      }
    })
  }

  /**
   * Checks whether a correct file name was passed to a write actor.
   * @param writeData the data object to be checked
   */
  private def checkWriteFileName(writeData: FileWriteData): Unit = {
    writeData.path.getFileName.toString should fullyMatch regex (FilePrefix + "\\d+" + Pattern
      .quote(FileSuffix))
    writeData.path.getParent should be(BufferPath)
  }

  it should "allow filling the buffer from a source reader" in {
    val bufferActor = system.actorOf(propsWithMockFactory())
    bufferActor ! FillBuffer(testActor)
    val fileList = simulateFilling(bufferActor, testBytes())

    fileList should have length 1
    val writeData = fileList.head
    checkWriteFileName(writeData)
    writeData.data should be(testBytes())
  }

  it should "create temporary files of the configured size" in {
    val AdditionalData = "more"
    val bufferActor = system.actorOf(propsWithMockFactory())
    bufferActor ! FillBuffer(testActor)
    val fileList = simulateFilling(bufferActor, toBytes(TestData + AdditionalData))

    fileList should have length 2
    val writeData = fileList.head
    checkWriteFileName(writeData)
    writeData.data should be(testBytes())
    val writeData2 = fileList(1)
    checkWriteFileName(writeData2)
    writeData2.data should be(toBytes(AdditionalData))
  }

  it should "send a busy message for simultaneous fill requests" in {
    val bufferActor = system.actorOf(propsWithMockFactory())
    bufferActor ! FillBuffer(testActor)

    expectMsgType[ReadData]
    bufferActor ! FillBuffer(testActor)
    expectMsg(BufferBusy)
  }

  it should "clear the buffer directory at startup" in {
    val bufferManager = createBufferFileManager()
    val bufferActor = system.actorOf(propsWithMockFactory(bufferManager = Some(bufferManager)))
    bufferActor ! FillBuffer(testActor)
    expectMsgType[ReadData]

    verify(bufferManager).clearBufferDirectory()
  }

  it should "allow another fill operation after the previous one is complete" in {
    val bufferActor = system.actorOf(propsWithMockFactory())
    val probe = TestProbe()
    bufferActor ! FillBuffer(testActor)
    bufferActor.tell(FillBuffer(probe.ref), probe.ref)
    probe.expectMsg(BufferBusy)
    simulateFilling(bufferActor, testBytes())

    bufferActor ! FillBuffer(testActor)
    simulateFilling(bufferActor, toBytes("Some more data")) should have length 1
  }

  it should "pass complete files to the buffer manager" in {
    val bufferManager = createBufferFileManager()
    val bufferActor = system.actorOf(propsWithMockFactory(bufferManager = Some(bufferManager)))
    bufferActor ! FillBuffer(testActor)
    val fileList = simulateFilling(bufferActor, toBytes(TestData + "some more data"))

    verify(bufferManager).append(fileList.head.path)
  }

  it should "not read new data when the buffer is full" in {
    val bufferManager = createBufferFileManager()
    when(bufferManager.isFull).thenReturn(true)
    val bufferActor = system.actorOf(propsWithMockFactory(bufferManager = Some(bufferManager)))

    bufferActor ! FillBuffer(testActor)
    bufferActor ! FillBuffer(testActor)
    expectMsg(BufferBusy)
  }

  it should "send a busy message for simultaneous read requests" in {
    val bufferActor = system.actorOf(propsWithMockFactory())

    bufferActor ! ReadBuffer
    bufferActor ! ReadBuffer
    expectMsg(BufferBusy)
  }

  it should "support reading from the buffer" in {
    val bufferManager = createBufferFileManager()
    when(bufferManager.read).thenReturn(Some(BufferPath))
    val bufferActor = system.actorOf(propsWithMockFactory(bufferManager = Some(bufferManager)))
    val probe = TestProbe()

    bufferActor.tell(ReadBuffer, probe.ref)
    expectMsg(InitFile(BufferPath))
    probe.expectMsg(BufferReadActor(testActor))
  }

  /**
   * Creates a ''Terminated'' message for the specified actor reference.
   * @param actor the actor reference
   * @return the terminated message for this actor
   */
  private def termMessage(actor: ActorRef): Terminated = {
    val termMsg = mock[Terminated]
    when(termMsg.actor).thenReturn(actor)
    termMsg
  }

  it should "checkout a temporary file after it has been read" in {
    val bufferManager = createBufferFileManager()
    when(bufferManager.read).thenReturn(Some(BufferPath))
    val probe = TestProbe()
    val bufferActor = TestActorRef(propsWithMockFactory(bufferManager = Some(bufferManager),
      readerActor = Some(probe.ref)))

    bufferActor ! ReadBuffer
    expectMsg(BufferReadActor(probe.ref))
    bufferActor receive termMessage(probe.ref)
    verify(bufferManager).checkOutAndRemove()
  }

  it should "allow multiple read request in series" in {
    val bufferManager = createBufferFileManager()
    val path1 = BufferPath resolve "file1"
    val path2 = BufferPath resolve "file2"
    when(bufferManager.read).thenReturn(Some(path1), Some(path2))
    val probe = TestProbe()
    val bufferActor = TestActorRef(propsWithMockFactoryForMultipleReaders(bufferManager = Some
      (bufferManager), readers = List(probe.ref, probe.ref), writerActor = None))

    bufferActor ! ReadBuffer
    expectMsg(BufferReadActor(probe.ref))
    system stop probe.ref
    bufferActor ! ReadBuffer
    fishForMessage(max = 3.second) {
      case BufferBusy =>
        bufferActor ! ReadBuffer
        false

      case BufferReadActor(_) =>
        true
    }
  }

  it should "serve a read request when new data is available" in {
    val bufferManager = createBufferFileManager()
    when(bufferManager.read).thenReturn(None, Some(BufferPath))
    val probe = TestProbe()
    val bufferActor = TestActorRef(propsWithMockFactory(bufferManager = Some(bufferManager)))

    bufferActor.tell(ReadBuffer, probe.ref)
    bufferActor ! FillBuffer(testActor)
    simulateFilling(bufferActor, testBytes())
    probe.expectMsg(BufferReadActor(testActor))
  }

  it should "serve a fill request after space is available again in the buffer" in {
    val bufferManager = createBufferFileManager()
    when(bufferManager.read).thenReturn(Some(BufferPath))
    when(bufferManager.isFull).thenReturn(true, false)
    val probe = TestProbe()
    val bufferActor = TestActorRef(propsWithMockFactory(bufferManager = Some(bufferManager),
      readerActor = Some(probe.ref)))

    bufferActor ! FillBuffer(testActor)
    bufferActor ! FillBuffer(testActor)
    expectMsg(BufferBusy)
    bufferActor ! ReadBuffer
    expectMsg(BufferReadActor(probe.ref))
    system stop probe.ref
    expectMsgType[ReadData]
  }

  it should "reset pending fill requests when they have been processed" in {
    def readRequest(bufferActor: ActorRef, readActor: TestProbe): Unit = {
      bufferActor ! ReadBuffer
      expectMsg(BufferReadActor(readActor.ref))
      system stop readActor.ref
    }

    val readActor1 = TestProbe()
    val readActor2 = TestProbe()
    val readActor3 = TestProbe()
    val readActors = List(readActor1.ref, readActor2.ref, readActor3.ref)
    val bufferManager = createBufferFileManager()
    when(bufferManager.read).thenReturn(Some(BufferPath))
    when(bufferManager.isFull).thenReturn(true, false)
    val bufferActor = system.actorOf(propsWithMockFactoryForMultipleReaders(readActors, None,
      Some(bufferManager)))

    readActor3 watch readActor2.ref
    bufferActor ! FillBuffer(testActor)
    readRequest(bufferActor, readActor1)
    expectMsgType[ReadData]
    readRequest(bufferActor, readActor2)
    readActor3.expectMsgType[Terminated]
    bufferActor ! FillBuffer(testActor)
    expectMsg(BufferBusy)
    readRequest(bufferActor, readActor3)
  }

  it should "not crash when receiving an unexpected EoF message" in {
    val bufferActor = TestActorRef(LocalBufferActor())
    bufferActor receive EndOfFile(BufferPath)
  }

  /**
   * Prepares a test for the close operation of the buffer.
   * @return the buffer actor
   */
  private def prepareClosingTest(): ActorRef = {
    val bufferActor = system.actorOf(propsWithMockFactory())

    bufferActor ! CloseRequest
    expectMsg(CloseAck(bufferActor))
    bufferActor
  }

  it should "answer a close request if no operations are pending" in {
    prepareClosingTest()
  }

  it should "reject a fill request after the buffer was closed" in {
    val bufferActor = prepareClosingTest()
    bufferActor ! FillBuffer(testActor)

    expectMsg(BufferBusy)
  }

  it should "reject a read request after the buffer was closed" in {
    val bufferActor = prepareClosingTest()
    bufferActor ! ReadBuffer

    expectMsg(BufferBusy)
  }

  it should "terminate a fill operation on closing" in {
    val bufferActor = system.actorOf(propsWithMockFactory())
    bufferActor ! FillBuffer(testActor)
    simulateFilling(bufferActor, toBytes("some data"))

    bufferActor ! CloseRequest
    expectMsg(CloseRequest)
    bufferActor ! CloseAck(testActor)
    expectMsg(CloseAck(bufferActor))
  }

  it should "wait on closing until a read actor was stopped" in {
    val probe = TestProbe()
    val bufferManager = createBufferFileManager()
    when(bufferManager.read).thenReturn(Some(BufferPath))
    val bufferActor = system.actorOf(propsWithMockFactory(bufferManager = Some(bufferManager),
      readerActor = Some(probe.ref)))

    bufferActor ! ReadBuffer
    expectMsg(BufferReadActor(probe.ref))
    bufferActor ! CloseRequest
    bufferActor ! ReadBuffer
    expectMsg(BufferBusy)
    system stop probe.ref
    expectMsg(CloseAck(bufferActor))
    verify(bufferManager).checkOutAndRemove()
  }

  it should "perform cleanup on closing" in {
    val bufferManager = createBufferFileManager()
    val bufferActor = system.actorOf(propsWithMockFactory(bufferManager = Some(bufferManager)))

    bufferActor ! CloseRequest
    expectMsgType[CloseAck]
    verify(bufferManager).removeContainedPaths()
  }

  it should "ignore read results when closing" in {
    val probe = TestProbe()
    val bufferManager = createBufferFileManager()
    val bufferActor = system.actorOf(propsWithMockFactory(writerActor = Some(probe.ref),
      bufferManager = Some(bufferManager)))

    bufferActor ! FillBuffer(testActor)
    expectMsgType[ReadData]
    bufferActor ! CloseRequest
    bufferActor ! ReadResult(testBytes(), 10)
    expectMsg(CloseAck(bufferActor))
    bufferActor ! FillBuffer(testActor)
    expectMsg(BufferBusy)
    verify(bufferManager, never()).createPath()
  }

  it should "ignore write results when closing" in {
    val probe = TestProbe()
    val bufferActor = system.actorOf(propsWithMockFactory())

    bufferActor ! FillBuffer(probe.ref)
    probe.expectMsgType[ReadData]
    bufferActor.tell(ReadResult(testBytes(), 8), probe.ref)
    expectMsgType[InitFile]
    expectMsgType[ReadResult]
    bufferActor ! CloseRequest
    bufferActor ! WriteResult(FileWriterActor.WriteResultStatus.Ok, 8)
    expectMsg(CloseRequest)
    probe.expectNoMsg(1.second)
  }

  it should "allow to complete the current playlist" in {
    val bufferManager = createBufferFileManager()
    val bufferActor = system.actorOf(propsWithMockFactory(bufferManager = Some(bufferManager)))
    bufferActor ! FillBuffer(testActor)
    simulateFilling(bufferActor, toBytes("a small chunk of test data"))

    bufferActor ! SequenceComplete
    expectMsg(CloseRequest)
    bufferActor ! CloseAck(testActor)
    bufferActor ! FillBuffer(testActor)
    expectMsgType[ReadData]
    verify(bufferManager).append(any(classOf[Path]))
  }

  it should "do nothing on completion of the current playlist if no file is written" in {
    val bufferActor = system.actorOf(propsWithMockFactory())

    bufferActor ! SequenceComplete
    expectNoMsg(1.second)
  }

  it should "send a busy message when completing the playlist on closing" in {
    val bufferActor = system.actorOf(propsWithMockFactory())
    bufferActor ! FillBuffer(testActor)
    simulateFilling(bufferActor, toBytes("a small chunk"))
    bufferActor ! CloseRequest
    expectMsg(CloseRequest)

    bufferActor ! SequenceComplete
    expectMsg(BufferBusy)
  }

  it should "watch the current fill actor to react on a failed read operation" in {
    val strategy = OneForOneStrategy() {
      case _: IOException => Stop
    }
    val supervisionTestActor = SupervisionTestActor(system, strategy, Props(new Actor {
      override def receive: Receive = {
        case FileReaderActor.ReadData(_) =>
          throw new IOException("Test exception")
      }
    }))
    val bufferActor = system.actorOf(propsWithMockFactory())
    val fillActor = supervisionTestActor.underlyingActor.childActor

    bufferActor ! LocalBufferActor.FillBuffer(fillActor)
    val filledMsg = expectMsgType[LocalBufferActor.BufferFilled]
    filledMsg.readerActor should be (fillActor)
    filledMsg.sourceLength should be (0)
  }
}

/**
 * A helper class for storing information about a file that was written into the
 * buffer.
 * @param path the path of the file
 * @param data the data written
 */
private case class FileWriteData(path: Path, data: Array[Byte])
