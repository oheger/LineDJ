package de.oliver_heger.splaya.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler}
import java.nio.file.Path

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.splaya.io.ChannelHandler.{ArraySource, InitFile}
import de.oliver_heger.splaya.{FileTestHelper, SupervisionTestActor}
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{eq => eqParam, _}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

/**
 * Test class for ''FileWriterActor''.
 */
class FileWriterActorSpec(actorSystem: ActorSystem) extends TestKit(actorSystem)
with ImplicitSender with Matchers with FlatSpecLike with BeforeAndAfterAll with BeforeAndAfter
with MockitoSugar with FileTestHelper {

  import FileTestHelper._
  import de.oliver_heger.splaya.io.FileWriterActor._

  def this() = this(ActorSystem("FileWriterActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  after {
    tearDownTestFile()
  }

  /**
   * Creates an instance of ''FileWriterActor'' and returns a reference to it.
   * @return the reference to the test actor
   */
  private def fileWriterActor(): ActorRef = {
    system.actorOf(Props[FileWriterActor])
  }

  /**
   * Creates a ''Props'' object for obtaining a ''FileWriterActor'' which
   * is initialized with a mock channel factory that returns the specified
   * channel.
   * @param channel the channel to be used by the test actor
   * @return a ''Props'' instance for creating such a test actor
   */
  def propsForWriterActorWithChannel(channel: AsynchronousFileChannel): Props = {
    Props(classOf[FileWriterActor], createMockChannelFactory(channel))
  }

  /**
   * Creates a mock channel factory which always returns the given file channel.
   * @param channel the channel to be returned
   * @return the mock channel factory
   */
  private def createMockChannelFactory(channel: AsynchronousFileChannel): FileChannelFactory = {
    val factory = mock[FileChannelFactory]
    when(factory.createChannel(any(classOf[Path]), anyVararg())).thenReturn(channel)
    factory
  }

  /**
   * Sends a close message to the given actor and expects the acknowledgement.
   * @param writer the test actor
   */
  private def closeWriter(writer: ActorRef): Unit = {
    writer ! CloseRequest
    expectMsgType[CloseAck].actor should be(writer)
  }

  /**
   * Creates a request for a write operation with the full test data.
   * @return the request object
   */
  private def writeRequest(): WriteRequest = {
    val data = testBytes()
    WriteRequest(data, data.length, 0)
  }

  "A FileWriterActor" should "send a special write result if no channel is open" in {
    val writer = fileWriterActor()
    writer ! FileReaderActor.ReadResult(Array.empty, 0)

    val message = expectMsgType[WriteResult]
    message.status should be(FileWriterActor.WriteResultStatus.NoChannel)
    message.bytesWritten should be (0)
  }

  /**
   * Extracts the completion handler passed to a mock channel.
   * @param channel the mock channel
   * @return the completion handler
   */
  private def fetchCompletionHandler(channel: AsynchronousFileChannel):
  CompletionHandler[Integer, ActorRef] = {
    val captor = ArgumentCaptor.forClass(classOf[CompletionHandler[Integer, ActorRef]])
    verify(channel).write(any(classOf[ByteBuffer]), anyLong(), any(classOf[ActorRef]), captor
      .capture())
    captor.getValue
  }

  it should "write a file successfully" in {
    val file = createFileReference()
    val writer = fileWriterActor()
    writer ! InitFile(file)

    var count = 0
    for (w <- TestData.split("\\s")) {
      val bytes = toBytes(" " + w)
      val request = WriteRequest(data = bytes,
        length = if (count == 0) bytes.length - 1 else bytes.length,
        offset = if (count == 0) 1 else 0)
      count += 1
      writer ! request
      val message = expectMsgType[WriteResult]
      message.status should be(FileWriterActor.WriteResultStatus.Ok)
      message.bytesWritten should be(request.length)
    }
    closeWriter(writer)

    readDataFile(file) should be(TestData.replace("\r\n", "  "))
  }

  it should "trigger a second write if there are remaining bytes" in {
    val channel = mock[AsynchronousFileChannel]
    val writer = TestActorRef(propsForWriterActorWithChannel(channel))
    writer receive InitFile(createFileReference())
    writer receive writeRequest()

    val handler = fetchCompletionHandler(channel)
    handler.completed(1, testActor)
    closeWriter(writer)
    val captor = ArgumentCaptor.forClass(classOf[ByteBuffer])
    verify(channel).write(captor.capture(), eqParam(1L), eqParam(writer), any
      (classOf[CompletionHandler[Integer, ActorRef]]))
    val buffer = captor.getValue
    buffer.get() should be(testBytes()(1))
  }

  it should "handle exceptions reported to the completion handler" in {
    val file = createFileReference()
    val channel = mock[AsynchronousFileChannel]
    when(channel.write(any(classOf[ByteBuffer]), anyLong(), any(classOf[ActorRef]),
      any(classOf[CompletionHandler[Integer, ActorRef]]))).thenAnswer(new Answer[Void] {
      override def answer(invocation: InvocationOnMock): Void = {
        val handler = invocation.getArguments()(3).asInstanceOf[CompletionHandler[Integer,
          ActorRef]]
        handler.failed(new Throwable, testActor)
        null
      }
    })
    val strategy = OneForOneStrategy() {
      case _: IOException => Stop
    }
    val supervisionTestActor = SupervisionTestActor(system, strategy,
      propsForWriterActorWithChannel(channel))
    val probe = TestProbe()
    val writer = supervisionTestActor.underlyingActor.childActor
    probe watch writer
    writer ! InitFile(file)
    writer ! writeRequest()

    val termMsg = probe.expectMsgType[Terminated]
    termMsg.actor should be(writer)
    verify(channel).close()
  }

  it should "ignore stale write results" in {
    val channel = mock[AsynchronousFileChannel]
    val file = createFileReference()
    val writer = TestActorRef(propsForWriterActorWithChannel(channel))
    writer receive InitFile(file)
    writer receive writeRequest()
    writer receive InitFile(file)

    val handler = fetchCompletionHandler(channel)
    handler.failed(new IOException, testActor)
    expectNoMsg()
  }
}

/**
 * A simple case class for sending write requests to the test actor.
 * @param data the data array
 * @param length the length
 * @param offset the start offset in the array
 */
case class WriteRequest(data: Array[Byte], length: Int, override val offset: Int) extends
ArraySource
