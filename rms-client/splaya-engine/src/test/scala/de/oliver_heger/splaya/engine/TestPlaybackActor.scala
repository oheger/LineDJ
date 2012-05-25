package de.oliver_heger.splaya.engine

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Before
import org.junit.Test
import org.junit.After
import org.easymock.EasyMock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import org.easymock.IAnswer
import java.io.InputStream
import scala.actors.Actor
import scala.collection.mutable.ListBuffer
import de.oliver_heger.splaya.PlaybackError
import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackSourceStart
import de.oliver_heger.splaya.PlaybackPositionChanged
import de.oliver_heger.splaya.PlaybackSourceEnd
import de.oliver_heger.splaya.PlaybackStops
import de.oliver_heger.splaya.PlaybackStarts
import de.oliver_heger.splaya.PlaylistEnd
import de.oliver_heger.splaya.engine.io.SourceBufferManager
import de.oliver_heger.splaya.engine.io.SourceStreamWrapperFactory
import de.oliver_heger.splaya.tsthlp.StreamDataGenerator
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.engine.msg.Exit
import de.oliver_heger.tsthlp.WaitForExit
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.splaya.engine.msg.ChunkPlayed
import de.oliver_heger.splaya.engine.io.SourceStreamWrapper
import de.oliver_heger.splaya.engine.msg.PlayChunk
import de.oliver_heger.splaya.engine.msg.StopPlayback
import de.oliver_heger.splaya.engine.msg.StartPlayback
import de.oliver_heger.splaya.engine.msg.SkipCurrentSource
import de.oliver_heger.splaya.engine.io.TempFile
import de.oliver_heger.splaya.engine.io.TempFileFactory
import de.oliver_heger.splaya.tsthlp.ExceptionInputStream
import de.oliver_heger.splaya.engine.msg.SourceReadError
import de.oliver_heger.splaya.engine.msg.FlushPlayer
import de.oliver_heger.splaya.PlaybackSourceEnd
import de.oliver_heger.splaya.engine.msg.ActorExited

/**
 * Test class for ''PlaybackActor''.
 */
class TestPlaybackActor extends JUnitSuite with EasyMockSugar {
  /** Constant for a test audio format. */
  private val Format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1f,
    16, 2, 17, 10, true)

  /** Constant for a buffer size.*/
  private val BufferSize = 1024

  /** Constant for the length of the first test stream. */
  private val StreamLen1 = BufferSize + 100

  /** Constant for the length of the 2nd test stream. */
  private val StreamLen2 = 10 * BufferSize

  /** Constant for the length of the audio stream. */
  private val AudioStreamLen = 20000

  /** The mock for playback context factory. */
  private var ctxFactory: PlaybackContextFactory = _

  /** The mock for the source buffer manager. */
  private var bufMan: SourceBufferManager = _

  /** The mock for the stream factory. */
  private var streamFactory: SourceStreamWrapperFactory = _

  /** The stream generator. */
  private lazy val streamGen = StreamDataGenerator()

  /** The actor to be tested. */
  private var actor: PlaybackActor = _

  @Before def setUp() {
    ctxFactory = mock[PlaybackContextFactory]
    bufMan = createBufferManagerMock()
    streamFactory = mock[SourceStreamWrapperFactory]
    EasyMock.expect(streamFactory.bufferManager).andReturn(bufMan).anyTimes()
    Gateway.start()
  }

  @After def tearDown {
    if (actor != null) {
      actor ! Exit
    }
  }

  /**
   * Creates an prepares the mock for the source buffer manager.
   * @return the buffer manager mock
   */
  private def createBufferManagerMock(): SourceBufferManager = {
    val bm = mock[SourceBufferManager]
    EasyMock.expect(bm.updateCurrentStreamReadPosition(EasyMock.anyLong()))
      .anyTimes()
    EasyMock.expect(bm.streamRead(EasyMock.anyLong())).anyTimes()
    bm
  }

  /**
   * Creates a test actor instance.
   */
  private def setUpActor() {
    actor = new PlaybackActor(ctxFactory, streamFactory)
    actor.start()
  }

  /**
   * Causes the test actor to exit and waits until its shutdown is complete.
   */
  private def shutdownActor() {
    val exitCmd = new WaitForExit
    if (!exitCmd.shutdownActor(actor, 5000)) {
      fail("Actor did not exit!")
    }
    actor = null
  }

  /**
   * Creates a test actor for writing the line and installs it at the Gateway.
   * @param handler an optional handler for intercepting messages
   * @return the test actor
   */
  private def installLineWriterActor(handler: PartialFunction[Any, Unit]): QueuingActor = {
    val lineActor = new QueuingActor(handler)
    lineActor.start()
    Gateway += Gateway.ActorLineWrite -> lineActor
    lineActor
  }

  /**
   * Creates a mock for the playback context which operates on a test stream
   * with the given size.
   * @param line the line
   * @param streamSize the size of the input stream
   * @return the context object
   */
  private def createContext(line: SourceDataLine, streamSize: Int): PlaybackContext = {
    createContext(line, streamGen.generateStream(0, streamSize))
  }

  /**
   * Creates a mock for the playback context which operates on the given stream.
   * @param line the line
   * @param stream the stream managed by the context
   * @return the context object
   */
  private def createContext(line: SourceDataLine, stream: InputStream): PlaybackContext = {
    val ctx = mock[PlaybackContext]
    EasyMock.expect(ctx.format).andReturn(Format).anyTimes()
    EasyMock.expect(ctx.stream).andReturn(stream).anyTimes()
    EasyMock.expect(ctx.streamSize).andReturn(AudioStreamLen).anyTimes()
    EasyMock.expect(ctx.line).andReturn(line).anyTimes()
    EasyMock.expect(ctx.createPlaybackBuffer()).andAnswer(new IAnswer[Array[Byte]]() {
      def answer() = new Array[Byte](BufferSize)
    }).anyTimes()
    ctx
  }

  /**
   * Tests whether the actor exits gracefully.
   */
  @Test def testStartAndExit() {
    EasyMock.replay(streamFactory)
    val listener = installListener()
    setUpActor()
    val playbackActor = actor
    shutdownActor()
    listener.expectMessage(ActorExited(playbackActor))
    listener.ensureNoMessages()
    listener.shutdown()
    Gateway.unregister(listener)
  }

  /**
   * Tests that nothing is done if no audio source is available.
   */
  @Test def testPlayChunkNoSource() {
    setUpActor()
    val lineActor = installLineWriterActor(null)
    EasyMock.expect(bufMan.bufferSize).andReturn(10 * BufferSize).anyTimes()
    whenExecuting(bufMan, ctxFactory, streamFactory) {
      actor ! ChunkPlayed(BufferSize)
      lineActor.ensureNoMessages()
      lineActor.shutdown()
    }
  }

  /**
   * Expects a flush operation on the actor.
   * @param line the line mock
   */
  private def expectFlush(line: SourceDataLine) {
    line.stop()
    line.flush()
    bufMan.flush()
  }

  /**
   * Executes a standard test for reading multiple sources with multiple chunks
   * and passing them to the line actor.
   * @param line the line
   * @param lineActor the mock line actor
   * @param bufSize the buffer size to be returned by the buffer manager
   * @param skip the initial skip position
   */
  private def executePlaybackTestWithMultipleChunksAndSources(
    line: SourceDataLine, lineActor: Actor, bufSize: Int = 2 * 4096,
    skip: Long = 0) {
    executePlaybackTestWithMultipleChunksAndSourcesEnhanced(line, lineActor,
      bufSize, true, skip)(null);
  }

  /**
   * Executes a standard test for reading multiple sources with multiple chunks
   * and allows performing additional checks.
   * @param line the line
   * @param lineActor the mock line actor
   * @param bufSize the buffer size to be returned by the buffer manager
   * @param expFlush a flag whether flush operation is to be expected
   * @param skip the initial skip position
   * @param f an (optional) function which is called to execute additional test
   * steps
   */
  private def executePlaybackTestWithMultipleChunksAndSourcesEnhanced(
    line: SourceDataLine, lineActor: Actor, bufSize: Int = 2 * 4096,
    expFlush: Boolean = true, skip: Long = 0)(f: Unit => Unit) {
    val streamWrapper1 = mock[SourceStreamWrapper]
    val streamWrapper2 = mock[SourceStreamWrapper]
    val dataStream = mock[InputStream]
    val context1 = createContext(line, StreamLen1)
    EasyMock.expect(streamFactory.createStream(null, StreamLen1)).andReturn(streamWrapper1)
    EasyMock.expect(streamWrapper1.currentPosition).andReturn(StreamLen1 / 2).anyTimes()
    EasyMock.expect(ctxFactory.createPlaybackContext(streamWrapper1)).andReturn(context1)
    EasyMock.expect(bufMan.bufferSize).andReturn(PlaybackActor.MinimumBufferLimit).anyTimes()
    line.open(Format).times(2)
    line.start().times(2)
    if (expFlush) {
      expectFlush(line)
    }
    context1.close()
    EasyMock.expect(streamWrapper1.currentStream).andReturn(dataStream)
    val context2 = createContext(line, StreamLen2)
    EasyMock.expect(streamFactory.createStream(dataStream, StreamLen2)).andReturn(streamWrapper2)
    EasyMock.expect(ctxFactory.createPlaybackContext(streamWrapper2)).andReturn(context2)
    EasyMock.expect(streamWrapper2.currentPosition).andReturn(StreamLen2 / 2).anyTimes()
    context2.close()
    streamWrapper2.closeCurrentStream()
    whenExecuting(bufMan, ctxFactory, streamFactory, line, streamWrapper1,
      streamWrapper2, context1, context2) {
        actor ! AudioSource("uri1", 1, StreamLen1, skip, skip * 60)
        actor ! AudioSource("uri2", 2, StreamLen2, 0, 0)
        actor ! ChunkPlayed(BufferSize)
        actor ! ChunkPlayed(BufferSize)
        if (f != null) f()
        shutdownActor()
      }
  }

  /**
   * Converts the content of the buffer from the given PlayChunk message into a
   * string.
   * @param pc the message
   * @return the string
   */
  private def copyBufferToString(pc: PlayChunk): String = {
    val bytes = new Array[Byte](pc.len)
    System.arraycopy(pc.chunk, 0, bytes, 0, pc.len)
    new String(bytes)
  }

  /**
   * Tests collaboration between the test actor and the line actor for playing
   * some chunks from different sources.
   */
  @Test def testPlaybackMultipleChunksAndSources() {
    val line = mock[SourceDataLine]
    val strBuffer = new StringBuffer
    val lineActor = installLineWriterActor {
      case pc: PlayChunk =>
        assert(line === pc.line)
        assert(0 === pc.skipPos)
        strBuffer append copyBufferToString(pc)
    }
    setUpActor()
    executePlaybackTestWithMultipleChunksAndSources(line, lineActor)
    lineActor.ensureNoMessages(3)
    lineActor.shutdown()
    val expected = streamGen.generateStreamContent(0, StreamLen1) +
      streamGen.generateStreamContent(0, BufferSize)
    assert(expected === strBuffer.toString())
  }

  /**
   * Extracts the next PlayChunk message from the line actor or fails if none
   * is found.
   */
  private def extractPlayChunkMessage(lineActor: QueuingActor): PlayChunk = {
    lineActor.nextMessage() match {
      case pc: PlayChunk => pc
      case _ => fail("Unexpected message!")
    }
  }

  /**
   * Tests that the skip position is taken into account.
   */
  @Test def testPlaybackWithSkip() {
    val line = mock[SourceDataLine]
    val initSkip = 10000L
    setUpActor()
    val lineActor = installLineWriterActor(null)
    executePlaybackTestWithMultipleChunksAndSources(line = line,
      skip = initSkip, lineActor = lineActor)
    val pc1 = extractPlayChunkMessage(lineActor)
    assert(initSkip === pc1.skipPos)
    assert(0 === pc1.currentPos)
    val pc2 = extractPlayChunkMessage(lineActor)
    assert(initSkip === pc2.skipPos)
    assert(BufferSize === pc2.currentPos)
    val pc3 = extractPlayChunkMessage(lineActor)
    assert(0 === pc3.skipPos)
    lineActor.shutdown()
  }

  /**
   * Tests that no playback is performed if not enough data was streamed into
   * the buffer.
   */
  @Test def testPlaybackNotEnoughDataInBuffer() {
    setUpActor()
    val lineActor = installLineWriterActor(null)
    EasyMock.expect(bufMan.bufferSize)
      .andReturn(PlaybackActor.MinimumBufferLimit - 1).anyTimes()
    bufMan.flush()
    whenExecuting(bufMan, ctxFactory, streamFactory) {
      actor ! AudioSource("uri", 1, 1000, 0, 0)
      lineActor.ensureNoMessages()
      lineActor.shutdown()
      shutdownActor()
    }
  }

  /**
   * Tests whether messages for pausing playback are handled correctly.
   */
  @Test def testPlaybackPauseAndResume() {
    val line = mock[SourceDataLine]
    val lineActor = installLineWriterActor(null)
    setUpActor()
    line.stop()
    line.start()
    executePlaybackTestWithMultipleChunksAndSourcesEnhanced(line, lineActor) { f =>
      actor ! StopPlayback
      actor ! StartPlayback
      lineActor.nextMessage()
      lineActor.nextMessage()
      lineActor.nextMessage()
      lineActor.ensureNoMessages()
      lineActor.shutdown()
    }
  }

  /**
   * Tests whether a stop command is handled correctly before the first source
   * is added.
   */
  @Test def testStopPlaybackBeforeStart() {
    val lineActor = installLineWriterActor(null)
    setUpActor()
    whenExecuting(bufMan, ctxFactory, streamFactory) {
      actor ! StopPlayback
      actor ! AudioSource("uri", 1, 1000, 0, 0)
      lineActor.ensureNoMessages()
      lineActor.shutdown()
    }
  }

  /**
   * Tests that a start playback message is ignored if playback is already
   * active.
   */
  @Test def testStartAlreadyPlaying() {
    val line = mock[SourceDataLine]
    val lineaActor = installLineWriterActor(null)
    setUpActor()
    executePlaybackTestWithMultipleChunksAndSourcesEnhanced(line, lineaActor) { f =>
      actor ! StartPlayback
    }
  }

  /**
   * Tests that a stop playback message is ignored if playback is already
   * paused.
   */
  @Test def testStopAlreadyStopped() {
    val line = mock[SourceDataLine]
    line.stop()
    val lineaActor = installLineWriterActor(null)
    setUpActor()
    executePlaybackTestWithMultipleChunksAndSourcesEnhanced(line, lineaActor) { f =>
      actor ! StartPlayback
      actor ! StopPlayback
      actor ! StopPlayback
    }
  }

  /**
   * Tests whether data at the end of the playlist can be played (here the
   * buffer is almost empty).
   */
  @Test def testPlaybackAtEndOfPlaylist() {
    val line = mock[SourceDataLine]
    val lineActor = installLineWriterActor(null)
    setUpActor()
    actor ! PlaylistEnd
    executePlaybackTestWithMultipleChunksAndSources(line, lineActor, BufferSize)
    lineActor.nextMessage()
    lineActor.shutdown()
  }

  /**
   * Tests whether a skip message is processed correctly.
   */
  @Test def testSkip() {
    val line = mock[SourceDataLine]
    val ofs = StreamLen2 * 2
    val stream = createStreamWrapper(streamGen.generateStream(ofs, StreamLen2),
      StreamLen2)
    val src = AudioSource("uri", 1, StreamLen2, 0, 0)
    EasyMock.expect(streamFactory.createStream(null, StreamLen2)).andReturn(stream)
    val context = createContext(line, StreamLen2)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream)).andReturn(context)
    context.close()
    line.open(Format)
    line.start()
    line.stop()
    line.flush()
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    expectFlush(line)
    val lineActor = installLineWriterActor(null)
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context, line) {
      actor ! src
      actor ! SkipCurrentSource
      actor ! ChunkPlayed(BufferSize)
      lineActor.skipMessages(1)
      val pc = extractPlayChunkMessage(lineActor)
      assert(Long.MaxValue === pc.skipPos)
      val content = copyBufferToString(pc)
      assert(streamGen.generateStreamContent(ofs, BufferSize) === content)
      shutdownActor()
    }
    lineActor.ensureNoMessages()
    lineActor.shutdown()
  }

  /**
   * Tests the event produced for a fully played source which has been skipped.
   */
  @Test def testSkipEndSourceEvent() {
    val line = mock[SourceDataLine]
    val stream = createStreamWrapper(streamGen.generateStream(0, 10),
      10)
    val src = AudioSource("uri", 1, StreamLen1, 0, 0)
    EasyMock.expect(streamFactory.createStream(null, StreamLen1)).andReturn(stream)
    val context = createContext(line, StreamLen1)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream)).andReturn(context)
    context.close()
    line.open(Format)
    line.start()
    line.stop()
    line.flush()
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    bufMan.flush()
    val lineActor = installLineWriterActor(null)
    val listener = installListener()
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context, line) {
      actor ! src
      actor ! ChunkPlayed(BufferSize)
      actor ! SkipCurrentSource
      actor ! ChunkPlayed(BufferSize)
      actor ! ChunkPlayed(BufferSize)
      shutdownActor()
    }
    lineActor.shutdown()
    var found = false
    while (!found) {
      listener.nextMessage() match {
        case PlaybackSourceEnd(src, true) => found = true
        case _ =>
      }
    }
    Gateway.unregister(listener)
    listener.shutdown()
  }

  /**
   * Tests whether a new temporary file is added correctly to the buffer
   * manager.
   */
  @Test def testTempFileAdded() {
    val temp = mock[TempFile]
    expecting {
      bufMan += temp
      EasyMock.expect(bufMan.bufferSize).andReturn(BufferSize).anyTimes()
      bufMan.flush()
    }
    setUpActor()
    whenExecuting(bufMan, ctxFactory, streamFactory, temp) {
      actor ! temp
      shutdownActor()
    }
  }

  /**
   * Creates a test actor and installs it as listener at the Gateway.
   */
  private def installListener(): QueuingActor = {
    val listener = new QueuingActor
    listener.start()
    Gateway.register(listener)
    listener
  }

  /**
   * Tests whether the expected events for starting and ending playback of a
   * source are fired.
   */
  @Test def testPlaybackSourceEvents() {
    val line = mock[SourceDataLine]
    val lineActor = installLineWriterActor(null)
    val listener = installListener()
    setUpActor()
    executePlaybackTestWithMultipleChunksAndSources(line, lineActor)
    lineActor.shutdown()
    val src1 = AudioSource("uri1", 1, StreamLen1, 0, 0)
    val src2 = AudioSource("uri2", 2, StreamLen2, 0, 0)
    listener.expectMessage(PlaybackStarts)
    listener.expectMessage(PlaybackSourceStart(src1))
    listener.expectMessage(PlaybackPositionChanged(BufferSize, AudioStreamLen,
      StreamLen1 / 2, src1))
    listener.expectMessage(PlaybackSourceEnd(src1, false))
    listener.expectMessage(PlaybackSourceStart(src2))
    listener.ensureNoMessages(1)
    Gateway.unregister(listener)
    listener.shutdown()
  }

  /**
   * Tests whether the relative position can be obtained from a position changed
   * message if the length of the audio stream is known.
   */
  @Test def testPositionChangedMsgRelativePositionFromAudioStream() {
    val src = AudioSource("uri", 1, 200, 0, 0)
    val msg = PlaybackPositionChanged(750, 1000, 10, src)
    assert(75 === msg.relativePosition)
  }

  /**
   * Tests whether the relative position can be obtained from a position changed
   * message if the length of the audio stream has to be obtained from the
   * underlying stream.
   */
  @Test def testPositionChangedMsgRelativePositionFromOriginalStream() {
    val src = AudioSource("uri", 1, 1000, 0, 0)
    val msg = PlaybackPositionChanged(100, -1, 750, src)
    assert(75 === msg.relativePosition)
  }

  /**
   * Creates a ''SourceStreamWrapper'' with test stream content.
   * @param len the length of the stream
   * @return the newly created stream wrapper
   */
  private def createStreamWrapper(len: Int): SourceStreamWrapper = {
    createStreamWrapper(streamGen.generateStream(0, len), len)
  }

  /**
   * Creates a ''SourceStreamWrapper'' with the given wrapped stream.
   * @param stream the stream to wrap
   * @param len the length of the stream
   * @return the newly created stream wrapper
   */
  private def createStreamWrapper(stream: InputStream, len: Int): SourceStreamWrapper = {
    val tempFactory = mock[TempFileFactory]
    new SourceStreamWrapper(tempFactory, stream, len, bufMan)
  }

  /**
   * Tests whether an error event is produced if the context for a new source
   * could not be created. In this case, the affected source should be skipped.
   */
  @Test def testErrorCreateContext() {
    val len = StreamLen2
    val stream = createStreamWrapper(len)
    val ex = new RuntimeException("TestException")
    val src = AudioSource("uri", 1, len, 0, 0)
    EasyMock.expect(streamFactory.createStream(null, len)).andReturn(stream)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream)).andThrow(ex)
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    bufMan.flush()
    val strBuffer = new StringBuffer
    val lineActor = installLineWriterActor {
      case pc: PlayChunk =>
        assert(Long.MaxValue === pc.skipPos)
        strBuffer append copyBufferToString(pc)
    }
    val listener = installListener()
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan) {
      actor ! src
      actor ! StartPlayback
      actor ! ChunkPlayed(PlaybackActor.DefaultBufferSize)
      shutdownActor()
    }
    listener.expectMessage(PlaybackStarts)
    listener.expectMessage(PlaybackSourceStart(src))
    listener.expectMessage(
      PlaybackError("Cannot create PlaybackContext for source " + src,
        ex, false, src))
    listener.expectMessage(PlaybackStops)
    listener.expectMessage(PlaybackStarts)
    lineActor.ensureNoMessages(2)
    assert(streamGen.generateStreamContent(0, 2 * PlaybackActor.DefaultBufferSize)
      === strBuffer.toString)
    lineActor.shutdown()
    Gateway.unregister(listener)
    listener.shutdown()
  }

  /**
   * Checks whether the next message received by the actor is a ''PlaybackError''.
   * If yes, it is returned. Otherwise, an error is raised.
   * @param actor the actor
   * @return the next ''PlaybackError'' message
   */
  private def extractErrorMessage(actor: QueuingActor): PlaybackError = {
    actor.nextMessage() match {
      case err: PlaybackError => err
      case otherMsg => fail("Unexpected message: " + otherMsg)
    }
  }

  /**
   * Tests whether an error while reading the audio stream is handled correctly.
   */
  @Test def testErrorReadAudioStream() {
    val line = mock[SourceDataLine]
    val len = 100
    val stream = createStreamWrapper(len)
    val src = AudioSource("uri", 1, len, 0, 0)
    EasyMock.expect(streamFactory.createStream(null, len)).andReturn(stream)
    val context = createContext(line, new ExceptionInputStream("Error"))
    EasyMock.expect(ctxFactory.createPlaybackContext(stream)).andReturn(context)
    context.close()
    line.open(Format)
    line.start()
    line.stop().times(2)
    line.flush()
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    expectFlush(line)
    val lineActor = installLineWriterActor(null)
    val listener = installListener()
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context, line) {
      actor ! src
      actor ! StartPlayback
      actor ! ChunkPlayed(BufferSize)
      listener.skipMessages(2)
      val err = extractErrorMessage(listener)
      assert("Error when reading from audio stream for source " + src === err.msg)
      assert(false === err.fatal)
      listener.expectMessage(PlaybackStops)
      var pc = extractPlayChunkMessage(lineActor)
      assert(Long.MaxValue === pc.skipPos)
      pc = extractPlayChunkMessage(lineActor)
      val content = copyBufferToString(pc)
      assert(streamGen.generateStreamContent(0, len) === content)
      shutdownActor()
    }
    lineActor.ensureNoMessages()
    lineActor.shutdown()
    Gateway.unregister(listener)
    listener.shutdown()
  }

  /**
   * Tests whether a fatal error is thrown if reading from the original audio
   * stream causes another error.
   */
  @Test def testErrorReadOriginalStream() {
    val line = mock[SourceDataLine]
    val len = 100
    val src = AudioSource("uri", 1, len, 0, 0)
    val stream = new ExceptionInputStream("Error1")
    val streamWrapper = createStreamWrapper(stream, BufferSize)
    EasyMock.expect(streamFactory.createStream(null, len)).andReturn(streamWrapper)
    val context = createContext(line, new ExceptionInputStream("Error2"))
    EasyMock.expect(ctxFactory.createPlaybackContext(streamWrapper)).andReturn(context)
    context.close()
    line.open(Format)
    line.start()
    line.stop().times(3)
    line.flush()
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    expectFlush(line)
    val lineActor = installLineWriterActor(null)
    val listener = installListener()
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context, line) {
      actor ! src
      listener.skipMessages(2)
      val err1 = extractErrorMessage(listener)
      assert(false === err1.fatal)
      actor ! StartPlayback
      actor ! ChunkPlayed(BufferSize)
      listener.expectMessage(PlaybackStops)
      listener.expectMessage(PlaybackStarts)
      listener.skipMessages(1)  // position changed
      val err2 = extractErrorMessage(listener)
      assert("Error when reading from audio stream for source " + src === err2.msg)
      assert(true === err2.fatal)
      shutdownActor()
    }
    lineActor.shutdown()
    Gateway.unregister(listener)
    listener.shutdown()
  }

  /**
   * Tests whether a SourceReadError message is handled correctly if the source
   * has not yet been started.
   */
  @Test def testSourceReadErrorBeforeStart() {
    val line = mock[SourceDataLine]
    val len = 222
    val src = AudioSource("uri1", 1, 2 * BufferSize, 0, 0)
    val stream = createStreamWrapper(src.length.toInt)
    EasyMock.expect(bufMan.bufferSize).andReturn(0)
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    EasyMock.expect(streamFactory.createStream(null, len)).andReturn(stream)
    val context = createContext(line, src.length.toInt)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream)).andReturn(context)
    context.close()
    line.open(Format)
    line.start()
    expectFlush(line)
    val lineActor = installLineWriterActor(null)
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context, line) {
      actor ! src
      actor ! SourceReadError(len)
      actor ! AudioSource("uri2", 2, 8888, 0, 0)
      shutdownActor()
    }
    lineActor.ensureNoMessages(1)
    lineActor.shutdown()
  }

  /**
   * Tests whether a SourceReadError message is handled correctly if the current
   * source is affected.
   */
  @Test def testSourceReadErrorForCurrentSource() {
    val line = mock[SourceDataLine]
    val stream = mock[SourceStreamWrapper]
    val orgLength = 5 * BufferSize
    val newLength = 2 * BufferSize
    val src = AudioSource("uri1", 1, orgLength, 0, 0)
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    EasyMock.expect(streamFactory.createStream(null, orgLength)).andReturn(stream)
    val context = createContext(line, orgLength)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream)).andReturn(context)
    stream.changeLength(newLength)
    context.close()
    line.open(Format)
    line.start()
    expectFlush(line)
    stream.closeCurrentStream()
    val lineActor = installLineWriterActor(null)
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context, line, stream) {
      actor ! src
      actor ! SourceReadError(newLength)
      shutdownActor()
    }
    lineActor.shutdown()
  }

  /**
   * Tests whether a chunk that has not been fully played is handled correctly.
   */
  @Test def testChunkPlayedIncomplete() {
    val line = mock[SourceDataLine]
    val stream = mock[SourceStreamWrapper]
    val src = AudioSource("uri1", 1, StreamLen2, 0, 0)
    val written = 100
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    EasyMock.expect(streamFactory.createStream(null, StreamLen2)).andReturn(stream)
    val context = createContext(line, StreamLen2)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream)).andReturn(context)
    EasyMock.expect(stream.currentPosition).andReturn(src.length / 2).anyTimes()
    context.close()
    line.open(Format)
    line.start()
    expectFlush(line)
    stream.closeCurrentStream()
    val buffer = new StringBuffer
    val lineActor = installLineWriterActor {
      case pc: PlayChunk =>
        buffer append copyBufferToString(pc)
    }
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context, line, stream) {
      actor ! src
      actor ! ChunkPlayed(0)
      actor ! ChunkPlayed(written)
      shutdownActor()
    }
    lineActor.ensureNoMessages(3)
    lineActor.shutdown()
    val expected = streamGen.generateStreamContent(0, BufferSize) +
      streamGen.generateStreamContent(0, BufferSize) +
      streamGen.generateStreamContent(written, BufferSize)
    assert(expected === buffer.toString)
  }

  /**
   * Tests whether a partly written chunk is handled correctly if the end of
   * the audio stream is reached.
   */
  @Test def testChunkPlayedIncompleteEOS() {
    val line = mock[SourceDataLine]
    val stream = mock[SourceStreamWrapper]
    val streamLen = BufferSize / 2
    val src = AudioSource("uri1", 1, streamLen, 0, 0)
    val written = 100
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    EasyMock.expect(streamFactory.createStream(null, streamLen)).andReturn(stream)
    val context = createContext(line, streamLen)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream)).andReturn(context)
    EasyMock.expect(stream.currentPosition).andReturn(src.length / 2).anyTimes()
    context.close()
    line.open(Format)
    line.start()
    bufMan.flush()
    stream.closeCurrentStream()
    val buffer = new StringBuffer
    val posList = ListBuffer.empty[Long]
    val lineActor = installLineWriterActor {
      case pc: PlayChunk =>
        buffer append copyBufferToString(pc)
        posList += pc.currentPos
    }
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context, line, stream) {
      actor ! src
      actor ! ChunkPlayed(written)
      actor ! ChunkPlayed(streamLen - written)
      shutdownActor()
    }
    lineActor.ensureNoMessages(2)
    lineActor.shutdown()
    val expected = streamGen.generateStreamContent(0, streamLen) +
      streamGen.generateStreamContent(written, streamLen - written)
    assert(expected === buffer.toString)
    assert(List(0L, written) === posList.toList)
  }

  /**
   * Tests whether notifications are sent when the playback actor starts and
   * stops playback.
   */
  @Test def testPlaybackStartsAndStopsEvents() {
    val line = mock[SourceDataLine]
    line.stop()
    line.start()
    val lineActor = installLineWriterActor(null)
    val listener = installListener()
    setUpActor()
    executePlaybackTestWithMultipleChunksAndSourcesEnhanced(line, lineActor) { f =>
      actor ! StopPlayback
      actor ! StartPlayback
    }
    Gateway.unregister(listener)
    var startCount = 0
    var stopCount = 0
    while (startCount < 2) {
      listener.nextMessage() match {
        case PlaybackStops =>
          stopCount += 1
        case PlaybackStarts =>
          startCount += 1
        case _ =>
      }
    }
    listener.ensureNoMessages(1)  // ignore exit message
    assert(1 == stopCount)
    listener.shutdown()
  }

  /**
   * Tests whether a stop playback event is fired when the end of the playlist
   * is reached.
   */
  @Test def testStopEventAtEndOfPlaylist() {
    val line = mock[SourceDataLine]
    bufMan.flush()
    val lineActor = installLineWriterActor(null)
    val listener = installListener()
    setUpActor()
    executePlaybackTestWithMultipleChunksAndSourcesEnhanced(line = line,
      lineActor = lineActor, expFlush = false) { f =>
        actor ! PlaylistEnd
        for (i <- 1 until (StreamLen2 / BufferSize + 1)) {
          actor ! ChunkPlayed(BufferSize)
        }
      }
    Gateway.unregister(listener)
    var foundStop = false
    while (!foundStop) {
      listener.nextMessage() match {
        case PlaybackStops =>
          foundStop = true
        case _ =>
      }
    }
    listener.expectMessage(PlaylistEnd)
    listener.ensureNoMessages(1)
    listener.shutdown()
  }

  /**
   * Tests whether a flush message is correctly processed.
   */
  @Test def testFlush() {
    val line = mock[SourceDataLine]
    val stream1 = mock[SourceStreamWrapper]
    val stream2 = mock[SourceStreamWrapper]
    EasyMock.expect(bufMan.bufferSize).andReturn(Long.MaxValue).anyTimes()
    EasyMock.expect(streamFactory.createStream(null, StreamLen1)).andReturn(stream1)
    EasyMock.expect(streamFactory.createStream(null, StreamLen2)).andReturn(stream2)
    val context1 = createContext(line, StreamLen1)
    val context2 = createContext(line, StreamLen2)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream1)).andReturn(context1)
    EasyMock.expect(ctxFactory.createPlaybackContext(stream2)).andReturn(context2)
    EasyMock.expect(stream1.currentPosition).andReturn(StreamLen1 / 2).anyTimes()
    EasyMock.expect(stream2.currentPosition).andReturn(StreamLen2 / 2).anyTimes()
    context1.close()
    context2.close()
    line.open(Format).times(2)
    line.start().times(2)
    expectFlush(line)
    expectFlush(line)
    stream1.closeCurrentStream()
    stream2.closeCurrentStream()
    val lineActor = installLineWriterActor(null)
    setUpActor()
    whenExecuting(streamFactory, ctxFactory, bufMan, context1, context2, line,
      stream1, stream2) {
        actor ! AudioSource("uri0", 1, StreamLen1, 0, 0)
        for (i <- 1 until 10) {
          actor ! AudioSource("uri" + i, i + 1, StreamLen1 + i, 0, 0)
        }
        actor ! FlushPlayer
        actor ! AudioSource("uriNext", 2, StreamLen2, 0, 0)
        shutdownActor()
      }
  }
}
