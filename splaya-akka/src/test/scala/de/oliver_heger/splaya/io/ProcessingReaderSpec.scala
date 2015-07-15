package de.oliver_heger.splaya.io

import java.io.ByteArrayOutputStream

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.splaya.io.ChannelHandler.InitFile
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData, ReadResult, SkipData}
import de.oliver_heger.splaya.{FileTestHelper, SupervisionTestActor}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object ProcessingReaderSpec {
  /** The chunk size used for read operations. */
  private val ChunkSize = 32

  /** An addendum written after the end of the file. */
  private val Addendum = " - And some more!"

  /**
   * A processing function which filters spaces from the given array.
   * @param data the array to be filtered
   * @return the processed array
   */
  private def filterSpaces(data: Array[Byte]): Array[Byte] =
    data filterNot (_ == 0x20)

  /**
   * A processing function which duplicates every byte in the given array.
   * The resulting array is twice as long.
   * @param data the array to be processed
   * @return the processed array
   */
  private def duplicate(data: Array[Byte]): Array[Byte] = {
    def duplicatedList(index: Int): List[Byte] =
      if (index >= data.length) Nil
      else data(index) :: data(index) :: duplicatedList(index + 1)

    duplicatedList(0).toArray
  }

  /**
   * A processing function which always skips the passed in array. Result is an
   * empty array.
   * @param data the array to be processed
   * @return the processed array
   */
  private def skip(data: Array[Byte]): Array[Byte] = Array.empty
}

/**
 * Test class for ''ProcessingReader''.
 */
class ProcessingReaderSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with FileTestHelper {

  import FileTestHelper._
  import ProcessingReaderSpec._

  def this() = this(ActorSystem("ProcessingReaderSpec"))

  /** The test file used by several test cases. */
  private lazy val testFile = createDataFile()

  /** A test file with a trailing space.  This is useful for tests that skip
    * the last chunk of the file.
    */
  private lazy val fileWithTrailingSpace = createDataFile(TestData + ' ')

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
    tearDownTestFile()
  }

  /**
   * Reads all data from the given reader actor and collects it in a byte array.
   * @param reader the reader actor
   * @return an array with the bytes read
   */
  private def read(reader: ActorRef, chunkSize: Int = ChunkSize): Array[Byte] = {
    val out = new ByteArrayOutputStream
    reader ! ReadData(chunkSize)

    fishForMessage() {
      case ReadResult(data, len) =>
        len should be <= chunkSize
        out.write(data, 0, len)
        reader ! ReadData(chunkSize)
        false

      case EndOfFile(_) =>
        true
    }

    out.toByteArray
  }

  /**
   * Reads the test file using the specified reader actor. This method first
   * initializes the reader actor with the test file, and then delegates to
   * ''read()''.
   * @param reader the reader actor
   * @return an array with the bytes read
   */
  private def readTestFile(reader: ActorRef): Array[Byte] = {
    reader ! InitFile(testFile)
    read(reader)
  }

  /**
   * Convenience method for creating a wrapped reader actor instance.
   * @return the reader actor
   */
  private def readerActor(): ActorRef = system.actorOf(Props[FileReaderActor])

  /**
   * Creates a ''Props'' for a dummy processing reader. This is a processing
   * reader implementation which does not do any processing, but directly
   * returns the data received from the wrapped reader.
   * @param wrappedReader the wrapped reader
   * @return properties for the dummy processing reader
   */
  private def dummyProcessingReaderProps(wrappedReader: ActorRef): Props = {
    Props(new ProcessingReader {
      override val readerActor = wrappedReader
    })
  }

  "A ProcessingReader" should "read data without changes" in {
    val wrappedReader = readerActor()
    val processingReader = system.actorOf(dummyProcessingReaderProps(wrappedReader))

    readTestFile(processingReader) should be(testBytes())
  }

  it should "indicate that the file was completely read" in {
    val wrappedReader = readerActor()
    val processingReader = TestActorRef[ProcessingReader](dummyProcessingReaderProps(wrappedReader))

    def checkEndOfFile(expected: Boolean): Unit = {
      processingReader.underlyingActor.endOfFile shouldBe expected
    }

    checkEndOfFile(expected = true)
    processingReader ! InitFile(testFile)
    checkEndOfFile(expected = false)
    read(processingReader)
    checkEndOfFile(expected = true)
  }

  /**
   * Checks whether a processing reader using the specified processing function
   * produces correct results.
   * @param p the processing function to be used
   */
  private def checkProcessingFunction(p: Array[Byte] => Array[Byte]): Unit = {
    val wrappedReader = readerActor()
    val processingReader = system.actorOf(propsWithProcessingFunction(wrappedReader, p))

    readTestFile(processingReader) should be(p(testBytes()))
  }

  /**
   * Creates a ''Props'' object for a processing reader actor that uses the
   * specified processing function.
   * @param wrappedReader the wrapped reader
   * @param p the processing function
   * @param checkChunkSize flag whether the test actor should check the chunk size
   * @return the ''Props'' for this processing reader actor
   */
  private def propsWithProcessingFunction(wrappedReader: ActorRef, p: (Array[Byte]) =>
    Array[Byte], checkChunkSize: Boolean = true): Props = Props(new ProcessingReaderTestImpl
  (wrappedReader, p, checkChunkSize))

  it should "support removing data during processing" in {
    checkProcessingFunction(filterSpaces)
  }

  it should "handle a read request after the end of file" in {
    val processingReader = system.actorOf(dummyProcessingReaderProps(readerActor()))
    readTestFile(processingReader)

    processingReader ! ReadData(1)
    expectMsg(EndOfFile(testFile))
  }

  it should "clear its read buffer when receiving an init message" in {
    val probe = TestProbe()
    val processingReader = system.actorOf(dummyProcessingReaderProps(probe.ref))
    processingReader ! InitFile(testFile)
    probe.expectMsg(InitFile(testFile))
    processingReader ! ReadData(ChunkSize)
    probe.expectMsg(ReadData(ChunkSize))
    val result = toBytes("result")
    processingReader ! ReadResult(result, result.length)
    probe.expectMsgType[ReadData]

    processingReader ! InitFile(testFile)
    probe.expectMsg(InitFile(testFile))
    processingReader ! ReadData(2)
    probe.expectMsg(ReadData(2))
    val nextResult = Array[Byte](1, 2)
    processingReader ! ReadResult(nextResult, nextResult.length)
    val resultMsg = expectMsgType[ReadResult]
    resultMsg.data should be(nextResult)
  }

  it should "clear the end of file flag when receiving an init message" in {
    val processingReader = system.actorOf(dummyProcessingReaderProps(readerActor()))
    readTestFile(processingReader)

    readTestFile(processingReader) should be(testBytes())
  }

  it should "support writing a preamble" in {
    val Prefix = "Some prefix: "
    val wrappedReader = readerActor()
    val processingReader = system.actorOf(Props(new ProcessingReader {
      override val readerActor = wrappedReader

      override protected def readOperationInitialized(initFile: InitFile): Unit = {
        publish(toBytes(Prefix))
      }
    }))

    val expectedResult = toBytes(Prefix + TestData)
    readTestFile(processingReader) should be(expectedResult)
  }

  it should "support adding data during processing" in {
    checkProcessingFunction(duplicate)
  }

  it should "clear its publish buffer when receiving an init message" in {
    val processingReader = system.actorOf(propsWithProcessingFunction(readerActor(), duplicate))
    processingReader ! InitFile(testFile)
    processingReader ! ReadData(ChunkSize)
    expectMsgType[ReadResult]

    readTestFile(processingReader) should be(duplicate(testBytes()))
  }

  it should "be able to handle multiple publish operations for a single request" in {
    val wrappedReader = readerActor()
    val processingReader = system.actorOf(Props(new ProcessingReader {
      override val readerActor = wrappedReader

      override protected def dataRead(data: Array[Byte]): Unit = {
        data.sliding(ChunkSize / 2, ChunkSize / 2) foreach publish
      }
    }))

    processingReader ! InitFile(testFile)
    processingReader ! ReadData(ChunkSize)
    expectMsgType[ReadResult].length should be(ChunkSize / 2)
    expectNoMsg(1.second)
    processingReader ! ReadData(ChunkSize)
    expectMsgType[ReadResult].data should be(testBytes().slice(ChunkSize / 2, ChunkSize))
    processingReader ! CloseRequest
    expectMsgType[CloseAck]
  }

  it should "forward a close request to the wrapped actor" in {
    val processingReader = system.actorOf(dummyProcessingReaderProps(readerActor()))

    processingReader ! CloseRequest
    expectMsg(CloseAck(processingReader))
  }

  it should "not fail on an unexpected close ack" in {
    val strategy = OneForOneStrategy() {
      case _: Exception => Stop
    }
    val wrappedReader = readerActor()
    val supervisionActor = SupervisionTestActor(system, strategy, dummyProcessingReaderProps
      (wrappedReader))
    val processingReader = supervisionActor.underlyingActor.childActor

    processingReader ! CloseAck(wrappedReader)
    readTestFile(processingReader) should be(testBytes())
  }

  it should "allow ignoring a read result" in {
    // In this test we read the test file byte-wise with the filtering
    // function. This will cause single blocks to be completely removed (the
    // ones containing only a space).
    val processingReader = system.actorOf(propsWithProcessingFunction(readerActor(),
      filterSpaces, checkChunkSize = false))
    processingReader ! InitFile(testFile)
    read(processingReader, 1) should be(filterSpaces(testBytes()))
  }

  it should "support nesting processing readers" in {
    val processingReader1 = system.actorOf(propsWithProcessingFunction(readerActor(),
      filterSpaces, checkChunkSize = false), "procReader1")
    val processingReader2 = system.actorOf(propsWithProcessingFunction(processingReader1,
      duplicate, checkChunkSize = false), "procReader2")

    val expected = duplicate(filterSpaces(testBytes()))
    readTestFile(processingReader2) should be(expected)
  }

  it should "support nesting processing readers in another order" in {
    val processingReader1 = system.actorOf(propsWithProcessingFunction(readerActor(), duplicate,
      checkChunkSize = false), "procReader_1")
    val processingReader2 = system.actorOf(propsWithProcessingFunction(processingReader1,
      filterSpaces, checkChunkSize = false), "procReader_2")

    val expected = filterSpaces(duplicate(testBytes()))
    readTestFile(processingReader2) should be(expected)
  }

  it should "allow ignoring a read result at the end of the file" in {
    val processingReader = system.actorOf(propsWithProcessingFunction(readerActor(),
      filterSpaces, checkChunkSize = false))

    processingReader ! InitFile(fileWithTrailingSpace)
    read(processingReader, 1) should be(filterSpaces(testBytes()))
  }

  it should "support writing an addendum" in {
    val wrappedReader = readerActor()
    val props = Props(new ProcessingReader with AddendumProcessingReader {
      override val readerActor: ActorRef = wrappedReader
    })
    val processingReader = system.actorOf(props)

    readTestFile(processingReader) should be(toBytes(TestData + Addendum))
  }

  it should "support writing an addendum if there is no remaining data at the end" in {
    val wrappedReader = readerActor()
    val props = Props(new ProcessingReader with AddendumProcessingReader {
      override val readerActor: ActorRef = wrappedReader
    })
    val processingReader = system.actorOf(props)

    processingReader ! InitFile(testFile)
    read(processingReader, testBytes().length) should be(toBytes(TestData + Addendum))
  }

  it should "support writing an addendum if the last read result is skipped" in {
    val wrappedReader = readerActor()
    val props = Props(new ProcessingReaderTestImpl(wrappedReader, skip, checkChunkSize = false) with
      AddendumProcessingReader)
    val processingReader = TestActorRef[ProcessingReaderTestImpl with AddendumProcessingReader](props)

    readTestFile(processingReader) should be(toBytes(Addendum))
    processingReader ! CloseRequest
    expectMsgType[CloseAck]
    processingReader.underlyingActor.afterProcessingInvocations should be(1)
  }

  /**
   * Creates a ''Props'' object for a processing reader that is configured to
   * pass skip messages through.
   * @param wrappedReader the underlying reader actor
   * @return the ''Props''
   */
  private def propsWithPassThroughSkipHandling(wrappedReader: ActorRef): Props =
    Props(new ProcessingReader {
      override val readerActor: ActorRef = wrappedReader
      override val passSkipMessagesThrough: Boolean = true
    })

  it should "support pass through skip handling" in {
    val wrappedReader = TestProbe()
    val processingReader = system.actorOf(propsWithPassThroughSkipHandling(wrappedReader.ref))

    val skipMsg = SkipData(42)
    processingReader ! skipMsg
    wrappedReader.expectMsg(skipMsg)
  }

  it should "produce correct data in pass through skip mode" in {
    val processingReader = system.actorOf(propsWithPassThroughSkipHandling(readerActor()))
    processingReader ! InitFile(testFile)

    processingReader ! ReadData(8)
    expectMsgType[ReadResult]
    processingReader ! SkipData(16)
    read(processingReader) should be(testBytes() drop 24)
  }

  it should "support simple skip handling based on ignoring published data" in {
    val processingReader = system.actorOf(propsWithProcessingFunction(readerActor(), duplicate))

    processingReader ! InitFile(testFile)
    processingReader ! SkipData(ChunkSize - 8)
    val expected = duplicate(testBytes()) drop (ChunkSize - 8)
    read(processingReader) should be(expected)
  }

  it should "support skip operations over more than a single chunk" in {
    val processingReader = system.actorOf(propsWithProcessingFunction(readerActor(), filterSpaces))

    processingReader ! InitFile(testFile)
    val skipMsg = SkipData(2 * ChunkSize)
    processingReader ! skipMsg
    val expected = filterSpaces(testBytes()) drop skipMsg.count
    read(processingReader) should be (expected)
  }

  it should "support skipping until the end of file" in {
    val processingReader = system.actorOf(propsWithProcessingFunction(readerActor(), filterSpaces))
    processingReader ! InitFile(testFile)
    processingReader ! ReadData(ChunkSize)
    expectMsgType[ReadResult].data should be(filterSpaces(testBytes() take ChunkSize))

    processingReader ! SkipData(5 * TestData.length)
    processingReader ! ReadData(ChunkSize)
    expectMsg(EndOfFile(testFile))
  }

  /**
   * A test implementation of ''ProcessingReader'' which uses the specified
   * processing function to manipulate data received from the wrapped reader.
   * @param readerActor the wrapped reader actor
   * @param processingFunc the processing function
   * @param checkChunkSize flag whether the chunk size should be checked
   */
  private class ProcessingReaderTestImpl(override val readerActor: ActorRef, processingFunc:
  Array[Byte] => Array[Byte], checkChunkSize: Boolean) extends ProcessingReader {
    override protected def dataRead(data: Array[Byte]): Unit = {
      val processedData = processingFunc(checkChunk(data))
      if (processedData.nonEmpty) {
        publish(processedData)
      } else {
        readFromWrappedActor(currentReadRequestSize)
      }
    }

    /**
     * Tests whether the current chunk has the expected size. A chunk must
     * never be greater than the requested size. In fact, it should have the
     * requested size unless the end of file has been reached.
     * @param data the current chunk
     * @return the chunk for method chaining
     */
    private def checkChunk(data: Array[Byte]): Array[Byte] = {
      data.length should be <= ChunkSize
      if (checkChunkSize && !endOfFile) {
        data should have length ChunkSize
      }
      data
    }
  }

  /**
   * A specialized processing reader trait that adds additional data after the
   * end of the processed file.
   */
  private trait AddendumProcessingReader extends ProcessingReader {
    var afterProcessingInvocations = 0

    abstract override def afterProcessing(): Unit = {
      publish(toBytes(Addendum))
      afterProcessingInvocations += 1
    }
  }

}
