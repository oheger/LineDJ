package de.oliver_heger.splaya.media

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.file.Paths

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.splaya.FileTestHelper
import de.oliver_heger.splaya.io.ChannelHandler.InitFile
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData, ReadResult, SkipData}
import de.oliver_heger.splaya.mp3.{ID3HeaderExtractor, ID3Header}
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object MediaFileReaderActorSpec {
  /** Chunk size for read operations. */
  private val ChunkSize = 32

  /** The initialization message with the test path. */
  private val InitMsg = InitFile(Paths.get("testPath"))

  /**
   * Creates an array that represents an ID header in binary form.
   * @param index an index to produce unique headers
   * @return the header data array
   */
  private def createHeaderData(index: Int): Array[Byte] = {
    val data = new Array[Byte](ID3HeaderExtractor.ID3HeaderSize)
    java.util.Arrays.fill(data, index.toByte)
    data
  }

  /**
   * Expects the processing of an ID3 header and its associated frame.
   * @param reader the test reader actor
   * @param readerProbe the probe representing the underlying reader
   * @param headerData the binary data of the header
   * @param frameSize the size of the ID3 frame
   */
  private def expectSkipFrame(reader: ActorRef, readerProbe: TestProbe, headerData: Array[Byte],
                              frameSize: Int): Unit = {
    expectHeaderDataRequest(reader, readerProbe, headerData)
    readerProbe.expectMsg(SkipData(frameSize))
  }

  /**
   * Expects a read request for the data of an ID3 header and sends the
   * specified result.
   * @param reader the reader actor
   * @param readerProbe the probe representing the underlying reader
   * @param headerData the header data to be sent as result
   */
  private def expectHeaderDataRequest(reader: ActorRef, readerProbe: TestProbe, headerData:
  Array[Byte]): Unit = {
    readerProbe.expectMsg(ReadData(ID3HeaderExtractor.ID3HeaderSize))
    reader ! ReadResult(headerData, headerData.length)
  }

  /**
   * Writes the given read result into the output stream.
   * @param out the output stream
   * @param result the read result
   * @return the output stream
   */
  private def write(out: ByteArrayOutputStream, result: ReadResult): ByteArrayOutputStream = {
    out.write(result.data, 0, result.length)
    out
  }
}

/**
 * Test class for ''MediaFileReaderActor''.
 */
class MediaFileReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import MediaFileReaderActorSpec._

  def this() = this(ActorSystem("MediaFileReaderActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Expects a read result and writes it into the given output stream.
   * @param output the output stream
   * @return the populated output stream
   */
  private def expectAndWriteReadResult(output: ByteArrayOutputStream): ByteArrayOutputStream = {
    write(output, expectMsgType[ReadResult])
    output
  }

  /**
   * Simulates read operations after all headers have been skipped.
   * @param reader the reader actor
   * @param readerProbe the probe representing the underlying reader
   * @param data the data of the file to be read
   * @param output the output stream
   * @return the populated output stream
   */
  private def simulateRead(reader: ActorRef, readerProbe: TestProbe, data: InputStream, output:
  ByteArrayOutputStream): ByteArrayOutputStream = {
    var read = 0
    do {
      reader ! ReadData(ChunkSize)
      readerProbe.expectMsg(ReadData(ChunkSize))
      val array = new Array[Byte](ChunkSize)
      read = data read array
      reader ! ReadResult(array, read)
      if (read == ChunkSize) {
        expectAndWriteReadResult(output)
      }
    } while (read == ChunkSize)

    readerProbe.expectMsg(ReadData(ChunkSize - read))
    reader ! EndOfFile(InitMsg.path)
    expectAndWriteReadResult(output)
    reader ! ReadData(ChunkSize)
    expectMsg(EndOfFile(InitMsg.path))
    output
  }

  "A MediaFileReaderActor" should "skip ID3 data" in {
    val extractor = mock[ID3HeaderExtractor]
    val readerProbe = TestProbe()
    val headerData1 = createHeaderData(1)
    val headerData2 = createHeaderData(2)
    val dataStream = new ByteArrayInputStream(FileTestHelper.testBytes())
    val chunk3 = new Array[Byte](ID3HeaderExtractor.ID3HeaderSize)
    dataStream.read(chunk3)
    val header1 = ID3Header(1, 128)
    val header2 = ID3Header(1, 256)

    when(extractor.extractID3Header(aryEq(headerData1))).thenReturn(Some(header1))
    when(extractor.extractID3Header(aryEq(headerData2))).thenReturn(Some(header2))
    when(extractor.extractID3Header(aryEq(chunk3))).thenReturn(None)

    val reader = system.actorOf(Props(classOf[MediaFileReaderActor], readerProbe.ref, extractor))
    reader ! InitMsg
    readerProbe.expectMsg(InitMsg)
    reader ! ReadData(ChunkSize)
    expectSkipFrame(reader, readerProbe, headerData1, header1.size)
    expectSkipFrame(reader, readerProbe, headerData2, header2.size)
    expectHeaderDataRequest(reader, readerProbe, chunk3)

    val readData = new ByteArrayOutputStream
    write(readData, expectMsgType[ReadResult])
    simulateRead(reader, readerProbe, dataStream, readData).toByteArray should be(FileTestHelper
      .testBytes())
  }

  it should "allow reading multiple files" in {
    val extractor = mock[ID3HeaderExtractor]
    val readerProbe = TestProbe()
    val chunk = FileTestHelper.testBytes() take ID3HeaderExtractor.ID3HeaderSize
    when(extractor.extractID3Header(aryEq(chunk))).thenReturn(None)

    val reader = system.actorOf(Props(classOf[MediaFileReaderActor], readerProbe.ref, extractor))
    reader ! InitMsg
    readerProbe.expectMsg(InitMsg)
    reader ! ReadData(ChunkSize)
    expectHeaderDataRequest(reader, readerProbe, chunk)
    val msg = expectMsgType[ReadResult]
    msg.length should be(ID3HeaderExtractor.ID3HeaderSize)
    msg.data should be(chunk)

    reader ! InitMsg
    readerProbe.expectMsg(InitMsg)
    reader ! ReadData(ChunkSize)
    readerProbe.expectMsg(ReadData(ID3HeaderExtractor.ID3HeaderSize))
  }
}
