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
package de.oliver_heger.linedj.metadata

import java.nio.file.Paths

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.config.ServerConfig
import de.oliver_heger.linedj.io.{ChannelHandler, FileReaderActor}
import de.oliver_heger.linedj.mp3.{ID3Header, ID3HeaderExtractor}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.util.Random

object ID3FrameReaderActorSpec {
  /** The read chunk size property. */
  private val ReadChunkSize = 128

  /** A path simulating the file to be read. */
  private val TestPath = Paths get "TestFile.mp3"

  /**
   * Creates a header object with the specified size.
   * @param size the header size
   * @return the header object
   */
  def createHeader(size: Int): ID3Header = ID3Header(size = size, version = 2)
}

/**
 * Test class for ''ID3FrameReaderActor''.
 */
class ID3FrameReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import ID3FrameReaderActorSpec._

  def this() = this(ActorSystem("ID3FrameReaderActorSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "An ID3FrameReaderActor" should "process a valid frame header" in {
    val helper = new ID3FrameReaderTestHelper
    val FrameSize = 64

    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    helper.handleHeaderRequest(Some(createHeader(FrameSize)))
    helper.readerProbe.expectMsg(FileReaderActor.ReadData(FrameSize))
  }

  it should "send frame content to the collector actor" in {
    val helper = new ID3FrameReaderTestHelper
    val FrameSize = 64
    val header = createHeader(FrameSize)
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)

    helper.handleHeaderRequest(Some(header))
    val frameContent = helper.handleReadRequest(FrameSize, FrameSize)
    helper.expectFrameMessage(header, frameContent, lastChunk = true)
  }

  it should "process the next frame after one has been completed" in {
    val helper = new ID3FrameReaderTestHelper
    val FrameSize = 64
    val header = createHeader(FrameSize)
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)

    helper.handleHeaderRequest(Some(header))
    helper.handleReadRequest(FrameSize, FrameSize)
    helper.handleHeaderRequest(Some(createHeader(32)))
    helper.handleReadRequest(32, 32)
  }

  it should "recognize an invalid frame" in {
    val helper = new ID3FrameReaderTestHelper
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    val headerBytes = helper handleHeaderRequest None

    val resultMsg = expectMsgType[FileReaderActor.ReadResult]
    resultMsg.data should be(headerBytes)
    resultMsg.length should be(headerBytes.length)
  }

  it should "stop searching for headers after detecting an invalid one" in {
    val helper = new ID3FrameReaderTestHelper
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    val headerBytes = helper handleHeaderRequest None
    expectMsgType[FileReaderActor.ReadResult]

    val readDataMsg = FileReaderActor.ReadData(ReadChunkSize)
    helper.frameReaderActor ! readDataMsg
    helper.readerProbe.expectMsg(readDataMsg)
  }

  it should "forward read data when header processing is complete" in {
    val helper = new ID3FrameReaderTestHelper
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    helper handleHeaderRequest None
    expectMsgType[FileReaderActor.ReadResult]

    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    val result = helper.handleReadRequest(ReadChunkSize, ReadChunkSize)
    val resultMsg = expectMsgType[FileReaderActor.ReadResult]
    resultMsg.length should be(ReadChunkSize)
    resultMsg.data should be(result)
  }

  it should "reset the processing state when another read operation starts" in {
    val helper = new ID3FrameReaderTestHelper
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    helper handleHeaderRequest Some(createHeader(ReadChunkSize))
    helper.readerProbe.expectMsgType[FileReaderActor.ReadData]

    helper.frameReaderActor ! ChannelHandler.InitFile(TestPath)
    helper.readerProbe.expectMsgType[ChannelHandler.InitFile]
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    helper.readerProbe.expectMsg(FileReaderActor.ReadData(ID3HeaderExtractor.ID3HeaderSize))
  }

  it should "handle a frame over multiple chunks" in {
    val FrameSize = 2 * ReadChunkSize + 32
    val header = createHeader(FrameSize)
    val helper = new ID3FrameReaderTestHelper
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    helper handleHeaderRequest Some(header)

    val chunk1 = helper.handleReadRequest(ReadChunkSize, ReadChunkSize)
    helper.expectFrameMessage(header, chunk1, lastChunk = false)
    val chunk2 = helper.handleReadRequest(ReadChunkSize, ReadChunkSize)
    helper.expectFrameMessage(header, chunk2, lastChunk = false)
    val remaining = FrameSize - 2 * ReadChunkSize
    val chunk3 = helper.handleReadRequest(remaining, remaining)
    helper.expectFrameMessage(header, chunk3, lastChunk = true)
  }

  it should "detect the end of file when processing frame content" in {
    val FrameSize = ReadChunkSize + 16
    val PartialSize = ReadChunkSize - 28
    val header = createHeader(FrameSize)
    val helper = new ID3FrameReaderTestHelper
    helper.frameReaderActor ! FileReaderActor.ReadData(ReadChunkSize)
    helper handleHeaderRequest Some(header)

    val chunk = helper.handleReadRequest(requestSize = ReadChunkSize, returnedSize = PartialSize)
    helper.readerProbe.expectMsg(FileReaderActor.ReadData(ReadChunkSize - PartialSize))
    helper.frameReaderActor ! FileReaderActor.EndOfFile(TestPath.toString)
    helper.expectFrameMessage(header, chunk, lastChunk = true)
    expectMsg(FileReaderActor.EndOfFile(TestPath.toString))
  }

  /**
   * A helper class managing typical objects needed for a test.
   */
  private class ID3FrameReaderTestHelper {
    /** Test probe for the collector actor. */
    val collectorProbe = TestProbe()

    /** Test probe for the underlying reader actor. */
    val readerProbe = TestProbe()

    /** A mock for the header extractor. */
    val headerExtractor = mock[ID3HeaderExtractor]

    /** The test actor. */
    val frameReaderActor = createAndInitTestActor()

    /** A Random object for generating random test data. */
    private val random = new Random

    /**
     * Handles a request for an ID3 header. The mock header extractor is
     * prepared to return the specified header.
     * @param optHeader an option for the header to be returned by the extractor
     * @return the array with the data of the header
     */
    def handleHeaderRequest(optHeader: Option[ID3Header]): Array[Byte] = {
      val headerBytes = testBytes(ID3HeaderExtractor.ID3HeaderSize)
      when(headerExtractor extractID3Header headerBytes).thenReturn(optHeader)
      handleReadRequest(ID3HeaderExtractor.ID3HeaderSize, headerBytes)
    }

    /**
     * Handles a request for reading data from the reader actor. This method
     * expects a read request of the specified size. It returns a read result
     * of a length which may defer from the request. The array with the frame
     * content is returned.
     * @param requestSize the requested size to be read
     * @param returnedSize the size of the result to be returned
     * @return the array with the data of the frame content
     */
    def handleReadRequest(requestSize: Int, returnedSize: Int): Array[Byte] = {
      handleReadRequest(requestSize, testBytes(returnedSize))
    }

    /**
     * Expects a ''ProcessID3FrameData'' to be sent to the collector actor.
     * Message properties are checked.
     * @param header the frame header
     * @param frameContent the expected content
     * @param lastChunk flag for the last chunk
     * @return the received message
     */
    def expectFrameMessage(header: ID3Header, frameContent: Array[Byte], lastChunk: Boolean):
    ProcessID3FrameData = {
      val msg = collectorProbe.expectMsgType[ProcessID3FrameData]
      msg.path should be(TestPath)
      msg.frameHeader should be(header)
      msg.data should be(frameContent)
      msg.lastChunk should be(lastChunk)
      msg
    }

    /**
     * Creates an array of test bytes with the given size.
     * @param size the size of the array
     * @return an array with test bytes of this size
     */
    def testBytes(size: Int): Array[Byte] = {
      val array = new Array[Byte](size)
      random nextBytes array
      array
    }

    /**
     * Expects a read request to be sent to the underlying reader actor and
     * returns a result based on the given bytes.
     * @param requestSize the request size
     * @param resultArray the result data to be returned
     * @return the result array
     */
    private def handleReadRequest(requestSize: Int, resultArray: Array[Byte]): Array[Byte] = {
      readerProbe.expectMsg(FileReaderActor.ReadData(requestSize))
      frameReaderActor ! FileReaderActor.ReadResult(resultArray, resultArray.length)
      resultArray
    }

    /**
     * Creates a test actor with mock parameters and performs basic
     * initialization. The actor is prepared for read tests.
     * @return the test actor
     */
    private def createAndInitTestActor(): ActorRef = {
      val actor = system.actorOf(Props(classOf[ID3FrameReaderActor], readerProbe.ref,
        createExtractionContext()))

      val initMsg = ChannelHandler.InitFile(TestPath)
      actor ! initMsg
      readerProbe.expectMsg(initMsg)
      actor
    }

    /**
     * Creates the extraction context for the test.
     * @return the extraction context
     */
    private def createExtractionContext(): MetaDataExtractionContext = {
      new MetaDataExtractionContext(collectorProbe.ref, createConfig()) {
        override val headerExtractor: ID3HeaderExtractor = ID3FrameReaderTestHelper.this
          .headerExtractor
      }
    }

    /**
     * Creates a mock for the server configuration.
     * @return the configuration mock
     */
    private def createConfig(): ServerConfig = {
      val config = mock[ServerConfig]
      when(config.metaDataReadChunkSize).thenReturn(ReadChunkSize)
      config
    }
  }

}
