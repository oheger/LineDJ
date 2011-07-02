package de.olix.playa.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.olix.playa.engine.AudioPlayerEvent.Type;

/**
 * Test class for AudioPlayer.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestAudioPlayer
{
    /** Constant for the length in the write chunk tests. */
    private static final int CHUNK_LEN = 30;

    /** Constant for the name of the test file. */
    private static final String TEST_FILE = "/test.mp3";

    /** Constant for the name of the test property. */
    private static final String TEST_PROP = "TEST";

    /** Constant for a wait period. */
    private static final long WAIT_TIME = 100;

    /** A mock for the audio source. */
    private AudioStreamSource mockSource;

    /** The player object to be tested. */
    private AudioPlayerTestImpl player;

    @Before
    public void setUp() throws Exception
    {
        mockSource = EasyMock.createMock(AudioStreamSource.class);
        player = new AudioPlayerTestImpl(mockSource);
    }

    /**
     * Tries to create an instance without a source.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoSource()
    {
        new AudioPlayer(null);
    }

    /**
     * Tests the writeChunk() method when no skip position is set.
     */
    @Test
    public void testWriteChunkNoSkip()
    {
        checkWriteChunk(0, 0, CHUNK_LEN);
    }

    /**
     * Tests the writeChunk() method when the chunk is fully skipped.
     */
    @Test
    public void testWriteChunkSkip()
    {
        checkWriteChunk(CHUNK_LEN, 0, 0);
    }

    /**
     * Tests the writeChunk() method when the chunk is partly skipped, i.e. the
     * chunk overlaps with the skip position.
     */
    @Test
    public void testWriteChunkPartlySkip()
    {
        checkWriteChunk(10, 10, CHUNK_LEN - 10);
        checkWriteChunk(CHUNK_LEN - 1, CHUNK_LEN - 1, 1);
    }

    /**
     * Tests writing a chunk when multiple calls to the line are necessary.
     */
    @Test
    public void testWriteChunkMultipleCalls()
    {
        byte[] chunk = new byte[CHUNK_LEN];
        final int partSize = 10;
        SourceDataLine mockLine = EasyMock.createMock(SourceDataLine.class);
        EasyMock.expect(mockLine.write(chunk, 0, CHUNK_LEN))
                .andReturn(partSize);
        EasyMock.expect(mockLine.write(chunk, partSize, CHUNK_LEN - partSize))
                .andReturn(CHUNK_LEN - partSize);
        EasyMock.replay(mockLine);
        player.setLine(mockLine);
        player.writeChunk(chunk, CHUNK_LEN);
        EasyMock.verify(mockLine);
    }

    /**
     * Tests writing a chunk when the skip flag is set. In this case nothing
     * should be written and the operation should be aborted.
     */
    @Test
    public void testWriteChunkWithSkip()
    {
        SourceDataLine mockLine = EasyMock.createMock(SourceDataLine.class);
        EasyMock.replay(mockLine);
        player.setLine(mockLine);
        player.setSkipPosition(AudioPlayer.SKIP_STREAM);
        byte[] chunk = new byte[CHUNK_LEN];
        assertFalse("Skip was not detected",
                player.writeChunk(chunk, CHUNK_LEN));
        EasyMock.verify(mockLine);
    }

    /**
     * Tests setting a skip time. When the skip position is reached in
     * writeChunk() a offset for the current time must be set.
     */
    @Test
    public void testWriteChunkSkipTime()
    {
        final long skipTime = 10000;
        player.getTimer().start();
        player.setSkipTime(skipTime);
        checkWriteChunk(10, 10, CHUNK_LEN - 10);
        AudioPlayerEvent event =
                player.createEvent(AudioPlayerEvent.Type.POSITION_CHANGED, null);
        assertTrue("Skip time was not set: " + event.getPlaybackTime(),
                event.getPlaybackTime() >= skipTime);
    }

    /**
     * Tests the writeChunk() method for a specified skip position.
     *
     * @param skipPos the skip position
     * @param expOfs the expected offset in the data buffer
     * @param expLen the expected length of bytes to write
     */
    private void checkWriteChunk(long skipPos, int expOfs, int expLen)
    {
        player.setSkipPosition(skipPos);
        byte[] chunk = new byte[CHUNK_LEN];
        SourceDataLine mockLine = EasyMock.createMock(SourceDataLine.class);
        if (expLen > 0)
        {
            EasyMock.expect(mockLine.write(chunk, expOfs, expLen)).andReturn(
                    expLen);
        }
        EasyMock.replay(mockLine);
        player.setLine(mockLine);
        assertTrue("Wrong skip flag", player.writeChunk(chunk, CHUNK_LEN));
        EasyMock.verify(mockLine);
    }

    /**
     * Tests whether a correct command thread is created.
     */
    @Test
    public void testCreateCommandThread() throws InterruptedException
    {
        CommandDispatchThread thread =
                new AudioPlayer(mockSource).createCommandThread();
        assertNotNull("No command thread created", thread);
        assertTrue("Thread is not alive", thread.isAlive());
        thread.exit();
        thread.join();
    }

    /**
     * Tests adding a new audio player listener.
     */
    @Test
    public void testAddAudioPlayerListener()
    {
        AudioPlayerListener l = EasyMock.createMock(AudioPlayerListener.class);
        EasyMock.replay(l);
        player.addAudioPlayerListener(l);
        assertTrue("Listener was already added", player
                .getAudioPlayerListeners().isEmpty());
        player.nextCommand();
        assertEquals("Listener was not added", 1, player
                .getAudioPlayerListeners().size());
        assertEquals("Wrong listener added", l, player
                .getAudioPlayerListeners().iterator().next());
    }

    /**
     * Tests adding a null audio listener. This should cause an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testAddAudioPlayerListenerNull()
    {
        player.addAudioPlayerListener(null);
    }

    /**
     * Tests removing an audio player listener.
     */
    @Test
    public void testRemoveAudioPlayerListener()
    {
        AudioPlayerListener l = EasyMock.createMock(AudioPlayerListener.class);
        EasyMock.replay(l);
        player.addAudioPlayerListener(l);
        player.nextCommand();
        player.removeAudioPlayerListener(l);
        assertEquals("Listener was already removed", 1, player
                .getAudioPlayerListeners().size());
        player.nextCommand();
        assertTrue("Listener still registered", player
                .getAudioPlayerListeners().isEmpty());
    }

    /**
     * Tests removing audio player listener, which are not registered.
     */
    @Test
    public void testRemoveAudioPlayerListenerUnRegistered()
    {
        AudioPlayerListener l1 = EasyMock.createMock(AudioPlayerListener.class);
        AudioPlayerListener l2 = EasyMock.createMock(AudioPlayerListener.class);
        player.addAudioPlayerListener(l1);
        player.nextCommand();
        player.removeAudioPlayerListener(l2);
        player.nextCommand();
        assertEquals("Listener no longer registered", 1, player
                .getAudioPlayerListeners().size());
        player.removeAudioPlayerListener(null);
        assertEquals("Listener no longer registered", 1, player
                .getAudioPlayerListeners().size());
    }

    /**
     * Tests obtaining the current audio format. This is the format object
     * associated with the source audio stream.
     */
    @Test
    public void testGetAudioFormat()
    {
        player.setCurrentSourceInputStream(setUpAudioInputStream(1024));
        AudioFormat format = player.getAudioFormat();
        assertEquals("Wrong encoding", AudioFormat.Encoding.PCM_SIGNED,
                format.getEncoding());
        assertTrue("Test property not found",
                format.properties().containsKey(TEST_PROP));
        assertEquals("Wrong sample rate", 44000, format.getSampleRate(), 0.001f);
        assertEquals("Wrong sample size", 16, format.getSampleSizeInBits());
    }

    /**
     * Tests obtaining the audio format if the source stream is undefined. In
     * this case the result should be null.
     */
    @Test
    public void testGetAudioFormatUndefined()
    {
        assertNull("Could obtain undefined audio format",
                player.getAudioFormat());
    }

    /**
     * Tests creating an audio player event.
     */
    @Test
    public void testCreateEvent()
    {
        AudioInputStream stream = setUpAudioInputStream(1024);
        player.setCurrentSourceInputStream(stream);
        player.setCurrentInputStream(stream);
        AudioStreamData mockData = EasyMock.createMock(AudioStreamData.class);
        EasyMock.expect(mockData.getName()).andReturn(TEST_FILE);
        EasyMock.replay(mockData);
        player.setStreamData(mockData);
        Throwable ex = new Exception("Test");

        AudioPlayerEvent event =
                player.createEvent(AudioPlayerEvent.Type.START_SONG, ex);
        assertEquals("Wrong stream name", TEST_FILE, event.getStreamName());
        assertSame("Wrong stream data", mockData, event.getStreamData());
        assertSame("Wrong source", player, event.getSource());
        Map<String, Object> props = event.getFormat().properties();
        assertEquals("Test property not found", Boolean.TRUE,
                props.get(TEST_PROP));
        assertEquals("Wrong stream length", 1024 * 64, event.getStreamLength());
        assertEquals("Wrong position", 0, event.getPosition());
        assertEquals("Wrong exception", ex, event.getException());
        assertEquals("Wrong percentual position", 0,
                event.getRelativePosition());
        assertFalse("Stream was skipped", event.isSkipped());
        EasyMock.verify(mockData);
    }

    /**
     * Tests creating an event when the source audio stream is undefined. Then
     * some of the properties are not available.
     */
    @Test
    public void testCreateEventUndefinedSourceStream()
    {
        AudioPlayerEvent event =
                player.createEvent(AudioPlayerEvent.Type.PLAYLIST_END, null);
        assertNull("Exception available", event.getException());
        assertEquals("Wrong type", AudioPlayerEvent.Type.PLAYLIST_END,
                event.getType());
        assertNull("Audio format available", event.getFormat());
        assertNull("Stream name available", event.getStreamName());
        assertNull("Stream data available", event.getStreamData());
        assertNull("Properties available", event.getStreamProperties());
        assertEquals("Non unknown stream length",
                AudioPlayerEvent.UNKNOWN_STREAM_LENGTH, event.getStreamLength());
        assertEquals("Percentual position available",
                AudioPlayerEvent.UNKNOWN_STREAM_LENGTH,
                event.getRelativePosition());
    }

    /**
     * Tests creating an event object. The percental position will be obtained
     * from the stream data object.
     */
    @Test
    public void testCreateEventPercentalPositionFromStreamData()
    {
        AudioInputStream stream = setUpAudioInputStream(-1);
        player.setCurrentSourceInputStream(stream);
        player.setCurrentInputStream(stream);
        AudioStreamData mockData = EasyMock.createMock(AudioStreamData.class);
        EasyMock.expect(mockData.getName()).andStubReturn(TEST_FILE);
        EasyMock.expect(mockData.size()).andStubReturn(100L);
        EasyMock.expect(mockData.getPosition()).andReturn(50L);
        EasyMock.replay(mockData);
        player.setStreamData(mockData);

        AudioPlayerEvent ev =
                player.createEvent(AudioPlayerEvent.Type.POSITION_CHANGED, null);
        assertEquals("Wrong percentual position", 50, ev.getRelativePosition());
        EasyMock.verify(mockData);
    }

    /**
     * Tests whether the timer is suspended when playback stops.
     */
    @Test
    public void testCreateEventSuspendTimer() throws InterruptedException
    {
        SourceDataLine line = EasyMock.createMock(SourceDataLine.class);
        line.stop();
        EasyMock.replay(line);
        player.setLine(line);
        player.getTimer().start();
        player.stopPlayback();
        AudioPlayerEvent event1 =
                player.createEvent(AudioPlayerEvent.Type.POSITION_CHANGED, null);
        Thread.sleep(WAIT_TIME * 5);
        AudioPlayerEvent event2 =
                player.createEvent(AudioPlayerEvent.Type.POSITION_CHANGED, null);
        assertEquals("Timer was not suspended", event1.getPlaybackTime(),
                event2.getPlaybackTime());
        EasyMock.verify(line);
    }

    /**
     * Tests whether created events contain a running playback time after the
     * timer has been resumed.
     */
    @Test
    public void testCreateEventResumeTimer() throws InterruptedException
    {
        SourceDataLine line = EasyMock.createMock(SourceDataLine.class);
        line.stop();
        line.start();
        EasyMock.replay(line);
        player.setLine(line);
        player.getTimer().start();
        player.stopPlayback();
        AudioPlayerEvent event1 =
                player.createEvent(AudioPlayerEvent.Type.POSITION_CHANGED, null);
        player.startPlayback();
        Thread.sleep(WAIT_TIME * 5);
        AudioPlayerEvent event2 =
                player.createEvent(AudioPlayerEvent.Type.POSITION_CHANGED, null);
        assertTrue("Timer not running",
                event2.getPlaybackTime() > event1.getPlaybackTime());
    }

    /**
     * Tests whether the skipped flag in the event is set correctly.
     */
    @Test
    public void testCreateEventSkipped()
    {
        player.setSkipPosition(AudioPlayer.SKIP_STREAM);
        AudioPlayerEvent event =
                player.createEvent(AudioPlayerEvent.Type.END_SONG, null);
        assertTrue("Wrong skip flag", event.isSkipped());
    }

    /**
     * Creates a dummy audio stream with default data for testing.
     *
     * @param length the size of the stream
     * @return the new stream
     */
    private AudioInputStream setUpAudioInputStream(long length)
    {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(TEST_PROP, Boolean.TRUE);
        AudioFormat format =
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44000, 16, 2,
                        64, 10, false, props);
        return new AudioInputStream(StreamHelper.createTestStream(1024),
                format, length);
    }

    /**
     * Tests writing a complete source stream.
     */
    @Test
    public void testWriteStream() throws IOException
    {
        final int chunks = 10;
        final int remaining = 16;
        byte[] buffer = new byte[CHUNK_LEN];
        InputStream testStream =
                StreamHelper.createTestStream(chunks * CHUNK_LEN + remaining);
        InputStream testDataStream = StreamHelper.createTestStream(1);
        AudioPlayerEvent event =
                new AudioPlayerEvent(player, AudioPlayerEvent.Type.START_SONG,
                        null);
        player.setTestEvent(event);

        SourceDataLine line = EasyMock.createStrictMock(SourceDataLine.class);
        AudioPlayerListener listener =
                EasyMock.createStrictMock(AudioPlayerListener.class);
        for (int i = 0; i < chunks; i++)
        {
            byte[] testBytes =
                    StreamHelper.createTestBytes(i * CHUNK_LEN, (i + 1)
                            * CHUNK_LEN);
            EasyMock.expect(
                    line.write(EasyMock.aryEq(testBytes), EasyMock.eq(0),
                            EasyMock.eq(CHUNK_LEN))).andReturn(CHUNK_LEN);
            listener.positionChanged(event);
        }
        EasyMock.expect(line.write(buffer, 0, remaining)).andReturn(remaining);
        listener.positionChanged(event);
        EasyMock.replay(line, listener);

        player.addAudioPlayerListener(listener);
        player.setLine(line);
        player.writeStream(testStream, testDataStream, buffer);
        player.executeAllCommands();
        EasyMock.verify(line, listener);
        assertEquals("Wrong position", chunks * CHUNK_LEN + remaining,
                player.getPosition());
        assertTrue("Data stream was read", testDataStream.read() != -1);
    }

    /**
     * Tests the writeStream() method when the terminate flag is set. In this
     * case nothing should be written.
     */
    @Test
    public void testWriteStreamWithTerminate() throws IOException
    {
        SourceDataLine line = EasyMock.createMock(SourceDataLine.class);
        AudioPlayerListener listener =
                EasyMock.createMock(AudioPlayerListener.class);
        EasyMock.replay(line, listener);
        player.setLine(line);
        player.addAudioPlayerListener(listener);
        player.terminate();

        player.writeStream(StreamHelper.createTestStream(10 * CHUNK_LEN),
                StreamHelper.createTestStream(1), new byte[CHUNK_LEN]);
        player.executeAllCommands();
        EasyMock.verify(line, listener);
    }

    /**
     * Tests the writeStream() method when the skip flag is set. In this case no
     * data should be written into the data line and the source stream should be
     * read.
     */
    @Test
    public void testWriteStreamWithSkip() throws IOException
    {
        SourceDataLine line = EasyMock.createMock(SourceDataLine.class);
        AudioPlayerListener listener =
                EasyMock.createMock(AudioPlayerListener.class);
        EasyMock.replay(line, listener);
        player.setLine(line);
        player.addAudioPlayerListener(listener);
        player.setSkipPosition(AudioPlayer.SKIP_STREAM);
        InputStream audioStream = StreamHelper.createTestStream(CHUNK_LEN);
        InputStream dataStream = StreamHelper.createTestStream(CHUNK_LEN);
        player.writeStream(audioStream, dataStream, new byte[CHUNK_LEN / 2]);
        player.executeAllCommands();
        EasyMock.verify(line, listener);
        assertTrue("Audio stream was read", audioStream.read() != -1);
        assertEquals("Data stream was not read", -1, dataStream.read());
    }

    /**
     * Tests the stop playback method.
     */
    @Test
    public void testStopPlayback()
    {
        player.getTimer().start();
        SourceDataLine line = EasyMock.createMock(SourceDataLine.class);
        line.stop();
        EasyMock.replay(line);
        player.setLine(line);
        player.stopPlayback();
        EasyMock.verify(line);
        assertFalse("Playing flag is set", player.isPlaying());
    }

    /**
     * Tests waiting for the end of playback.
     */
    @Test
    public void testWaitForPlaybackEnds() throws InterruptedException
    {
        SourceDataLine line = EasyMock.createMock(SourceDataLine.class);
        line.start();
        line.drain();
        EasyMock.replay(line);
        player.setLine(line);
        player.getTimer().start();
        player.getTimer().suspend();
        player.startPlayback();
        player.waitForPlaybackEnd();
        EasyMock.verify(line);
    }

    /**
     * Tests waiting for playback end when the stopPlayback() method is called.
     * In this case two calls to update() are necessary to end the waiting.
     */
    @Test
    public void testWaitForPlaybackEndsWithStopPlayback()
            throws InterruptedException
    {
        SourceDataLine line = EasyMock.createStrictMock(SourceDataLine.class);
        line.start();
        line.drain();
        EasyMock.replay(line);
        player.setLine(line);
        WaitPlaybackEndThread thread = new WaitPlaybackEndThread();
        thread.start();
        assertTrue("Thread is not waiting", thread.waiting);
        player.getTimer().start();
        player.getTimer().suspend();
        player.startPlayback();
        Thread.sleep(WAIT_TIME);
        thread.join();
        EasyMock.verify(line);
    }

    /**
     * Tests waiting for the end of playback when the thread is interrupted.
     */
    @Test
    public void testWaitForPlaybackEndsInterrupted()
            throws InterruptedException
    {
        SourceDataLine line = EasyMock.createMock(SourceDataLine.class);
        EasyMock.replay(line);
        player.setLine(line);
        WaitPlaybackEndThread thread = new WaitPlaybackEndThread();
        thread.start();
        assertTrue("Thread is not waiting", thread.waiting);
        thread.interrupt();
        thread.join();
        assertFalse("Thread is still waiting after interrupt", thread.waiting);
        EasyMock.verify(line);
    }

    /**
     * Tests the error method.
     */
    @Test
    public void testError()
    {
        Exception ex = new Exception("Test exception");
        AudioPlayerEvent event =
                player.createEvent(AudioPlayerEvent.Type.EXCEPTION, ex);
        player.setTestEvent(event);
        AudioPlayerListener listener =
                EasyMock.createMock(AudioPlayerListener.class);
        listener.error(event);
        EasyMock.replay(listener);
        player.addAudioPlayerListener(listener);
        player.error(ex);
        player.executeAllCommands();
        EasyMock.verify(listener);
    }

    /**
     * Tests raising a fatal error and then waiting until recover() is called.
     */
    @Test
    public void testFatalError() throws InterruptedException
    {
        Exception ex = new Exception("Test exception");
        AudioPlayerEvent event =
                player.createEvent(AudioPlayerEvent.Type.EXCEPTION, ex);
        player.setTestEvent(event);
        AudioPlayerListener listener =
                EasyMock.createMock(AudioPlayerListener.class);
        listener.fatalError(event);
        EasyMock.replay(listener);
        player.addAudioPlayerListener(listener);
        WaitFatalErrorThread thread = new WaitFatalErrorThread();
        thread.start();
        assertTrue("Thread is not waiting", thread.waiting);
        assertTrue("Not waiting after fatal error",
                player.isWaitAfterFatalError());
        player.recover();
        assertFalse("Still wait after fatal error",
                player.isWaitAfterFatalError());
        thread.join();
        assertFalse("Thread is still waiting", thread.waiting);
        player.executeAllCommands();
        EasyMock.verify(listener);
    }

    /**
     * Tests waiting after a fatal error when the waiting is interrupted.
     */
    @Test
    public void testFatalErrorInterrupted() throws InterruptedException
    {
        WaitFatalErrorThread thread = new WaitFatalErrorThread();
        thread.start();
        assertTrue("Thread is not waiting", thread.waiting);
        assertTrue("Not waiting after fatal error",
                player.isWaitAfterFatalError());
        thread.interrupt();
        thread.join();
        assertFalse("Thread is still waiting", thread.waiting);
        assertTrue("Fatal error was reset", player.isWaitAfterFatalError());
    }

    /**
     * Tests setting up the next audio source stream. Here we can only check
     * whether a non null stream is returned and no exception is thrown.
     */
    @Test
    public void testSetUpSourceStream() throws Exception
    {
        AudioInputStream ais = player.setUpSourceStream(createTestStream());
        assertNotNull("No stream returned", ais);
        ais.close();
    }

    /**
     * Tests setting up an encoded audio stream. We can here at least test some
     * properties of the decoded stream's format.
     */
    @Test
    public void testSetUpDecodedStream() throws Exception
    {
        player.setCurrentSourceInputStream(player
                .setUpSourceStream(createTestStream()));
        AudioInputStream ais =
                player.setUpDecodedStream(player.getCurrentSourceInputStream());
        assertNotNull("No stream returned", ais);
        assertNotNull("Audio format was not set", player.getAudioFormat());
        AudioFormat format = ais.getFormat();
        assertNotSame("Encoded audio format is the same",
                player.getAudioFormat(), format);
        assertEquals("Wrong encoding", AudioFormat.Encoding.PCM_SIGNED,
                format.getEncoding());
        assertEquals("Wrong sample size in bits", 16,
                format.getSampleSizeInBits());
        assertEquals("Wrong sample rate", player.getAudioFormat()
                .getSampleRate(), format.getSampleRate(), 0.001f);
        ais.close();
        player.getCurrentSourceInputStream().close();
    }

    /**
     * Creates a stream for the test MP3 file.
     *
     * @return the test stream
     */
    private InputStream createTestStream()
    {
        InputStream in = getClass().getResourceAsStream(TEST_FILE);
        assertNotNull("Test MP3 file could not be found", in);
        return in;
    }

    /**
     * Tests creating a copy buffer with simple parameters.
     */
    @Test
    public void testCreateCopyBuffer()
    {
        AudioFormat format =
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2,
                        4, 44100, false, new HashMap<String, Object>());
        byte[] buffer = player.createCopyBuffer(format);
        assertEquals("Wrong buffer length", AudioPlayer.DEFAULT_CHUNK_SIZE,
                buffer.length);
    }

    /**
     * Tests creating a copy buffer when the frame size is unspecified.
     */
    @Test
    public void testCreateCopyBufferUndefFrames()
    {
        AudioFormat format =
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2,
                        4, AudioSystem.NOT_SPECIFIED, false,
                        new HashMap<String, Object>());
        byte[] buffer = player.createCopyBuffer(format);
        assertEquals("Wrong buffer size", AudioPlayer.DEFAULT_CHUNK_SIZE,
                buffer.length);
    }

    /**
     * Tests creating a copy buffer when the a round operation is necessary to
     * obtain an integral number of frames.
     */
    @Test
    public void testCreateCopyBufferRounded()
    {
        AudioFormat format =
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2,
                        10, 100.5f, true);
        player.setChunkSize(999);
        assertEquals("Wrong rounded buffer size", 1000,
                player.createCopyBuffer(format).length);
    }

    /**
     * Tests setting up a data line. We can only test here that a non null line
     * is returned and no exception is thrown.
     */
    @Test
    public void testSetUpLine() throws LineUnavailableException
    {
        AudioFormat format =
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 16, 2,
                        4, 44100, false, new HashMap<String, Object>());
        SourceDataLine line = player.setUpLine(format);
        assertNotNull(line);
    }

    /**
     * Tests the playback loop.
     */
    @Test
    public void testPlayback() throws Exception
    {
        SourceDataLine mockLine = EasyMock.createMock(SourceDataLine.class);
        mockLine.open((AudioFormat) EasyMock.anyObject());
        mockLine.start();
        EasyMock.expect(
                mockLine.write((byte[]) EasyMock.anyObject(),
                        EasyMock.anyInt(), EasyMock.anyInt())).andReturn(16)
                .anyTimes();
        mockLine.drain();
        mockLine.close();
        player.setLine(mockLine);
        player.setTestLine(mockLine);

        AudioPlayerListener mockListener =
                EasyMock.createMock(AudioPlayerListener.class);
        AudioPlayerEvent event =
                player.createEvent(AudioPlayerEvent.Type.START_SONG, null);
        player.setTestEvent(event);
        mockListener.streamStarts(event);
        mockListener.positionChanged(event);
        EasyMock.expectLastCall().anyTimes();
        mockListener.streamEnds(event);
        mockListener.playListEnds(event);
        player.addAudioPlayerListener(mockListener);
        player.setSkipTime(12345);

        Collection<AudioStreamData> streams = new ArrayList<AudioStreamData>(2);
        setUpAudioSource(streams);

        EasyMock.replay(mockLine, mockListener);
        player.run();
        player.executeAllCommands();
        EasyMock.verify(mockLine, mockListener, mockSource);
        EasyMock.verify(streams.toArray());
        assertEquals("Skip time was not reset", 0, player.getSkipTime());
        assertEquals("Timer offset was not reset", 0, player.getTimeOffset());
    }

    /**
     * Tests the playback method when an error occurs.
     */
    @Test
    public void testPlaybackWithError() throws Exception
    {
        checkPlaybackWithException(new IllegalArgumentException(
                "Unknown audio format!"));
        assertEquals("Wrong number of errors", 1, player.errorCount);
        assertEquals("Wrong number of fatal errors", 0, player.fatalErrorCount);
    }

    /**
     * Tests the playback method when a fatal error occurs.
     */
    @Test
    public void testPlaybackWithFatalError() throws Exception
    {
        checkPlaybackWithException(new LineUnavailableException(
                "Unknown audio format!"));
        assertEquals("Wrong number of errors", 0, player.errorCount);
        assertEquals("Wrong number of fatal errors", 1, player.fatalErrorCount);
    }

    /**
     * Tests the playback method when an exception is thrown.
     *
     * @param ex the exception to throw by the line
     */
    private void checkPlaybackWithException(Throwable ex) throws Exception
    {
        SourceDataLine mockLine = EasyMock.createMock(SourceDataLine.class);
        mockLine.open((AudioFormat) EasyMock.anyObject());
        EasyMock.expectLastCall().andThrow(ex);
        player.setTestLine(mockLine);
        Collection<AudioStreamData> streams = new ArrayList<AudioStreamData>(2);
        setUpAudioSource(streams);
        player.setIgnoreErrors(true);
        EasyMock.replay(mockLine);

        player.playback();
        player.executeAllCommands();
        EasyMock.verify(mockSource, mockLine);
        EasyMock.verify(streams.toArray());
    }

    /**
     * Initializes the audio source mock for returning some test streams. One
     * real stream and one end mark stream will be returned.
     *
     * @param streams a collection for storing the stream mock objects
     */
    private void setUpAudioSource(Collection<AudioStreamData> streams)
    {
        try
        {
            AudioStreamData mockData =
                    EasyMock.createMock(AudioStreamData.class);
            EasyMock.expect(mockData.getStream()).andReturn(createTestStream())
                    .times(1, 2);
            EasyMock.expect(mockData.getName()).andStubReturn(TEST_FILE);
            EasyMock.expect(mockData.size()).andReturn(1000L);
            EasyMock.expect(mockSource.nextAudioStream()).andReturn(mockData);
            AudioStreamData mockEndData =
                    EasyMock.createMock(AudioStreamData.class);
            EasyMock.expect(mockEndData.getName()).andStubReturn(null);
            EasyMock.expect(mockEndData.size()).andReturn(-1L);
            EasyMock.expect(mockSource.nextAudioStream())
                    .andReturn(mockEndData);
            EasyMock.replay(mockData, mockEndData, mockSource);
            streams.add(mockData);
            streams.add(mockEndData);
        }
        catch (InterruptedException iex)
        {
            fail("Strange exception occurred: " + iex);
        }
        catch (IOException ioex)
        {
            fail("Strange exception occurred: " + ioex);
        }
    }

    /**
     * Tests the startPlayback() method.
     */
    @Test
    public void testStartPlayback()
    {
        SourceDataLine mockLine = EasyMock.createMock(SourceDataLine.class);
        mockLine.start();
        EasyMock.replay(mockLine);
        player.setLine(mockLine);
        player.getTimer().start();
        player.getTimer().suspend();
        player.startPlayback();
        EasyMock.verify(mockLine);
        assertTrue("Playing flag not set", player.isPlaying());
    }

    /**
     * Tests skipping a stream.
     */
    @Test
    public void testSkipStream()
    {
        setUpSkipLine();
        player.skipStream();
        EasyMock.verify(player.getLine());
        assertEquals("Skip position was not set", AudioPlayer.SKIP_STREAM,
                player.getSkipPosition());
    }

    /**
     * Tests skipping the current stream when playback is not running.
     */
    @Test
    public void testSkipStreamNoPlaying()
    {
        SourceDataLine mockLine = EasyMock.createMock(SourceDataLine.class);
        mockLine.flush();
        EasyMock.replay(mockLine);
        player.setLine(mockLine);
        player.skipStream();
        EasyMock.verify(player.getLine());
    }

    /**
     * Tests shutting down the audio player. This should cause the main loop to
     * exit as soon as possible.
     */
    @Test
    public void testShutdown()
    {
        setUpSkipLine();
        player.shutdown();
        EasyMock.verify(player.getLine());
        assertTrue("Terminate flag not set", player.isTerminate());
        assertFalse("Playing flag is set", player.isPlaying());
    }

    /**
     * Tests shutting down the audio player when it is blocked at the audio
     * source.
     */
    @Test
    public void testShutdownWithInterrupt() throws InterruptedException
    {
        AudioStreamSource source = new AudioStreamSource()
        {
            public AudioStreamData nextAudioStream()
                    throws InterruptedException
            {
                BlockingQueue<AudioStreamData> queue =
                        new ArrayBlockingQueue<AudioStreamData>(1);
                // This will block forever
                return queue.take();
            }
        };
        player = new AudioPlayerTestImpl(source);
        player.start();
        Thread.sleep(WAIT_TIME);
        assertTrue("Player is not playing", player.isPlaying());
        player.shutdown();
        player.join();
        assertFalse("Player is still playing", player.isPlaying());
    }

    /**
     * Sets up a data line mock that expects to be stopped and flushed.
     */
    private void setUpSkipLine()
    {
        SourceDataLine mockLine = EasyMock.createMock(SourceDataLine.class);
        mockLine.start();
        mockLine.stop();
        mockLine.flush();
        EasyMock.replay(mockLine);
        player.getTimer().start();
        player.getTimer().suspend();
        player.setLine(mockLine);
        player.startPlayback();
    }

    /**
     * Tests starting playback when the line is not yet defined. This should be
     * a noop.
     */
    @Test
    public void testStartPlaybackNullLine()
    {
        assertFalse("Already playing", player.isPlaying());
        player.startPlayback();
        assertFalse("Now playing", player.isPlaying());
    }

    /**
     * A special AudioPlayer implementation used for testing. Some methods are
     * overloaded in a way so that they can be easier tested.
     */
    private static class AudioPlayerTestImpl extends AudioPlayer
    {
        /** The number of invocations of the error method. */
        public int errorCount;

        /** the number of invocations of the fatalError method. */
        public int fatalErrorCount;

        /** Stores a test event. */
        private AudioPlayerEvent testEvent;

        /** Stores the test data line. */
        private SourceDataLine testLine;

        /** Stores the ignore errors flag. */
        private boolean ignoreErrors;

        public AudioPlayerTestImpl(AudioStreamSource source)
        {
            super(source);
        }

        public AudioPlayerEvent getTestEvent()
        {
            return testEvent;
        }

        /**
         * Allows to set a test event. If here an event object is set, this
         * object will be returned by createEvent().
         *
         * @param testEvent the test event
         */
        public void setTestEvent(AudioPlayerEvent testEvent)
        {
            this.testEvent = testEvent;
        }

        public SourceDataLine getTestLine()
        {
            return testLine;
        }

        /**
         * Allows to set a test data line. If here a line is specified, it will
         * be returned. Otherwise the super method is called.
         *
         * @param testLine the test line
         */
        public void setTestLine(SourceDataLine testLine)
        {
            this.testLine = testLine;
        }

        public boolean isIgnoreErrors()
        {
            return ignoreErrors;
        }

        /**
         * Sets the ignoreErrors flag. If this flag is set, the methods for
         * raising errors or fatal errors will be overloaded to only record
         * their invocation and do nothing more.
         *
         * @param ignoreErrors the value of the flag
         */
        public void setIgnoreErrors(boolean ignoreErrors)
        {
            this.ignoreErrors = ignoreErrors;
        }

        @Override
        protected CommandDispatchThread createCommandThread()
        {
            // Create the thread and exit it immediately
            CommandDispatchThread thread = super.createCommandThread();
            thread.exit();
            try
            {
                thread.join();
            }
            catch (InterruptedException iex)
            {
                fail("Interrupted: " + iex);
            }
            return thread;
        }

        /**
         * Executes the next command in the command queue.
         */
        public void nextCommand()
        {
            getCommandDispatchThread().nextCommand();
        }

        /**
         * Executes all pending commands in the command queue.
         */
        public void executeAllCommands()
        {
            while (getCommandDispatchThread().isBusy())
            {
                nextCommand();
            }
        }

        @Override
        protected AudioPlayerEvent createEvent(Type type, Throwable ex)
        {
            return (getTestEvent() == null) ? super.createEvent(type, ex)
                    : getTestEvent();
        }

        @Override
        protected SourceDataLine setUpLine(AudioFormat format)
                throws LineUnavailableException
        {
            return (getTestLine() == null) ? super.setUpLine(format)
                    : getTestLine();
        }

        @Override
        protected void error(Throwable exception)
        {
            if (!isIgnoreErrors())
            {
                super.error(exception);
            }
            errorCount++;
        }

        @Override
        protected void fatalError(Throwable exception)
        {
            if (!isIgnoreErrors())
            {
                super.fatalError(exception);
            }
            fatalErrorCount++;
        }
    }

    /**
     * A helper thread class that allows to test the player's wait methods.
     */
    abstract class WaitThread extends Thread
    {
        /** Stores the waiting flag. */
        public boolean waiting;

        @Override
        /**
         * Starts the thread and waits for a while to ensure that the wait
         * condition is reached.
         */
        public synchronized void start()
        {
            super.start();
            try
            {
                Thread.sleep(WAIT_TIME);
            }
            catch (InterruptedException iex)
            {
                fail("Start operation was interrupted: " + iex);
            }
        }

        /**
         * Executes the thread. Calls the wait method.
         */
        @Override
        public void run()
        {
            waiting = true;
            try
            {
                callWaitMethod();
                waiting = false;
            }
            catch (InterruptedException iex)
            {
                fail("Operation was interrupted: " + iex);
            }
        }

        /**
         * Calls the concrete wait method.
         *
         * @throws InterruptedException if the operation is interrupted
         */
        protected abstract void callWaitMethod() throws InterruptedException;
    }

    /**
     * A specific wait thread that waits for the end of playback.
     */
    class WaitPlaybackEndThread extends WaitThread
    {
        @Override
        protected void callWaitMethod() throws InterruptedException
        {
            player.waitForPlaybackEnd();
        }
    }

    /**
     * A specific wait thread that simulates waiting after a fatal error.
     */
    class WaitFatalErrorThread extends WaitThread
    {
        @Override
        protected void callWaitMethod() throws InterruptedException
        {
            player.fatalError(new Exception("Test exception"));
        }
    }
}
