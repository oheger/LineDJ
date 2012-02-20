package de.oliver_heger.splaya.engine

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.After
import org.junit.Test
import org.easymock.EasyMock
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.BeforeClass
import org.junit.Before

/**
 * Test class for ''SourceReaderActor''.
 */
class TestSourceReaderActor extends JUnitSuite with EasyMockSugar {
  /** Constant for a test string for generating test streams. */
  private val StreamText = "TestStreamContent_";

  /** Constant for the prefix of a URI. */
  private val URIPrefix = "file:testFile"

  /** Constant for the number of digits in indices. */
  private val Digits = 5

  /** Constant for the length of a block of the test stream. */
  private val BlockLen = StreamText.length + Digits

  /** Constant for the chunk size used by the tests. */
  private val ChunkSize = 1000 * BlockLen

  /** A mock for the source resolver. */
  private var resolver: SourceResolver = _

  /** A mock for the temporary file factory. */
  private var factory: TempFileFactory = _

  /** A mock for the buffer manager. */
  private var bufferMan: SourceBufferManager = _

  /** The current position in the test source stream. */
  private var streamPosition: Int = 0

  /** The current index of a source stream. */
  private var playlistIndex = 0

  /** The actor to be tested. */
  private var actor: SourceReaderActor = _

  @Before def setUp() {
    Gateway.start()
  }

  @After def tearDown {
    if (actor != null) {
      actor ! Exit
    }
  }

  /**
   * Causes the test actor to exit and waits until its shutdown is complete.
   */
  private def shutdownActor() {
    val exitCmd = new WaitForExit
    if (!exitCmd.shutdownActor(actor)) {
      fail("Actor did not exit!")
    }
    actor = null
  }

  /**
   * Generates content of a test stream. This method is able to generate a
   * stream of (almost) arbitrary length consisting of constant blocks followed
   * by indices. A substring of this stream can be returned.
   * @param pos the start position of the substring
   * @param length the length of the fragment
   * @return the specified substring of the test stream
   */
  private def generateStreamContent(pos: Int, length: Int): String = {
    val startIdx = pos / BlockLen
    val count = length / BlockLen + 2
    val buf = new StringBuilder(count * BlockLen)
    for (i <- 0 until count) {
      buf.append(StreamText)
      val idx = (i + startIdx).toString()
      buf.append("0" * (Digits - idx.length)).append(idx)
    }
    val startPos = pos % BlockLen
    buf.substring(startPos, startPos + length)
  }

  /**
   * Generates an input stream with the specified test content.
   * @param pos the start position of the stream
   * @param length the length of the stream
   * @return the input stream with this content
   */
  private def generateStream(pos: Int, length: Int): InputStream =
    new ByteArrayInputStream(generateStreamContent(pos, length).getBytes())

  /**
   * Returns the next input stream from the test sequence. Each method
   * invocation obtains the next portion of the test stream.
   * @param length the length of the next stream
   * @return the input stream
   */
  private def nextStream(length: Int): InputStream = {
    val startPos = streamPosition
    streamPosition += length
    generateStream(startPos, length)
  }

  /**
   * Generates a URI for the test stream with the given index.
   * @param index the index
   * @return the URI for this stream
   */
  private def streamURI(index: Int): String = URIPrefix + index

  /**
   * Prepares the resolver mock to resolve a new test source stream. The length
   * of the stream can be specified. The corresponding data object is returned.
   * @param length the length of the stream
   * @return the data object for the next source stream
   */
  private def prepareStream(length: Int): AddSourceStream = {
    val ssrc = mock[StreamSource]
    EasyMock.expect(ssrc.size).andReturn(length).anyTimes()
    EasyMock.expect(ssrc.openStream).andReturn(nextStream(length)).anyTimes()
    EasyMock.replay(ssrc)
    val plIdx = playlistIndex
    playlistIndex += 1
    val uri = streamURI(plIdx)
    EasyMock.expect(resolver.resolve(uri)).andReturn(ssrc)
    AddSourceStream(uri, plIdx)
  }

  /**
   * Prepares a mock for a temporary file. The mock is assigned an output stream
   * which is always returned.
   * @param addToBufMan a flag whether the file should be added to the buffer
   * manager
   * @return a tuple with the mock temporary file and the associated stream
   */
  private def prepareTempFile(addToBufMan: Boolean) = {
    val tempFile = mock[TempFile]
    val os = new ByteArrayOutputStream
    EasyMock.expect(tempFile.outputStream()).andReturn(os).anyTimes()
    EasyMock.expect(factory.createFile()).andReturn(tempFile)
    if (addToBufMan) {
      bufferMan += tempFile
    }
    (tempFile, os)
  }

  /**
   * Creates the test actor with default settings.
   */
  private def setUpActor() {
    resolver = mock[SourceResolver]
    factory = mock[TempFileFactory]
    bufferMan = mock[SourceBufferManager]
    actor = new SourceReaderActor(resolver, factory, bufferMan, ChunkSize)
  }

  /**
   * Tests whether the actor can exit gracefully.
   */
  @Test def testStartAndExit() {
    setUpActor();
    actor.start()
    shutdownActor
  }

  private def installPlaybackActor(): QueuingActor = {
    val qa = new QueuingActor
    qa.start()
    Gateway += Gateway.ActorPlayback -> qa
    qa
  }

  /**
   * Checks whether the specified stream contains the expected data.
   * @param bos the stream to check
   * @param startIdx the start index in the test stream
   * @param length the length
   */
  private def checkStream(bos: ByteArrayOutputStream, startIdx: Int, length: Int) {
    val content = bos.toByteArray()
    assert(length === content.length)
    val strContent = new String(content)
    assert(generateStreamContent(startIdx, length) === strContent)
  }

  /**
   * Tests whether a single source stream is directly copied when it is added to
   * the actor.
   */
  @Test def testAddSingleSource() {
    setUpActor()
    val qa = installPlaybackActor()
    val len = 111
    val src = prepareStream(len)
    val tempData = prepareTempFile(false)
    EasyMock.expect(tempData._1.delete()).andReturn(true)
    actor.start()
    whenExecuting(bufferMan, factory, resolver, tempData._1) {
      actor ! src
      qa.expectMessage(AudioSource(streamURI(0), 0, len))
      qa.shutdown()
      shutdownActor
      checkStream(tempData._2, 0, len)
    }
  }

  /**
   * Tests whether a full chunk of data can be copied.
   */
  @Test def testCopyFullChunk() {
    setUpActor()
    val qa = installPlaybackActor()
    val len1 = ChunkSize - 1
    val len2 = ChunkSize + 100
    val src1 = prepareStream(len1)
    val src2 = prepareStream(len2)
    val tempData1 = prepareTempFile(true)
    val tempData2 = prepareTempFile(true)
    actor.start()
    whenExecuting(bufferMan, factory, resolver, tempData1._1, tempData2._1) {
      actor ! src1
      actor ! src2
      qa.expectMessage(AudioSource(streamURI(0), 0, len1))
      qa.expectMessage(AudioSource(streamURI(1), 1, len2))
      qa.shutdown()
      shutdownActor
      checkStream(tempData1._2, 0, ChunkSize)
      checkStream(tempData2._2, ChunkSize, ChunkSize)
    }
  }

  /**
   * Tests whether further data is written after the receiver has processed a
   * chunk.
   */
  @Test def testCopyChunkAfterRead() {
    setUpActor()
    val qa = installPlaybackActor()
    val len1 = ChunkSize + 100
    val len2 = ChunkSize + 200
    val src1 = prepareStream(len1)
    val src2 = prepareStream(len2)
    val tempData1 = prepareTempFile(true)
    val tempData2 = prepareTempFile(true)
    val tempData3 = prepareTempFile(false)
    EasyMock.expect(tempData3._1.delete()).andReturn(true)
    actor.start()
    whenExecuting(bufferMan, factory, resolver, tempData1._1, tempData2._1,
      tempData3._1) {
        actor ! src1
        actor ! src2
        actor ! ReadChunk
        qa.expectMessage(AudioSource(streamURI(0), 0, len1))
        qa.expectMessage(AudioSource(streamURI(1), 1, len2))
        qa.shutdown()
        shutdownActor
        checkStream(tempData1._2, 0, ChunkSize)
        checkStream(tempData2._2, ChunkSize, ChunkSize)
        checkStream(tempData3._2, 2 * ChunkSize, len1 + len2 - 2 * ChunkSize)
      }
  }
}
