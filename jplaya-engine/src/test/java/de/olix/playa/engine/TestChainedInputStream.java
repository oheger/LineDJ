package de.olix.playa.engine;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import junit.framework.TestCase;

import org.easymock.EasyMock;

/**
 * Test class for ChainedInputStream.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestChainedInputStream extends TestCase
{
    /** Constant for the test string to be read from a stream. */
    private static final String TEST_DATA = "This is a text that will be read "
            + "read from a ChainedInputStream. Hopefully all goes well. Greetings to "
            + "everybody!";

    /** Constant for a test parameter expected by the call back. */
    private static final String TEST_PARA = "A param";

    /** Constant for the sleep time used in tests with threads. */
    private static final long SLEEP_TIME = 250;

    /** Stores the length of the test text. */
    private static final int TEST_LEN = TEST_DATA.length();

    /** Stores the object to be tested. */
    private ChainedInputStream stream;

    /** An output stream for storing the read data. */
    private ByteArrayOutputStream out;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        stream = new ChainedInputStream();
    }

    /**
     * Creates an input stream that contains the test data.
     *
     * @return the initialized input stream
     */
    private static InputStream createTestStream()
    {
        return new ByteArrayInputStream(TEST_DATA.getBytes());
    }

    /**
     * Returns the output stream.
     *
     * @return the output stream
     */
    private ByteArrayOutputStream getOut()
    {
        if (out == null)
        {
            out = new ByteArrayOutputStream();
        }
        return out;
    }

    /**
     * Tests the content of the output stream. This method assumes that a
     * sequence of test strings was read. The result is compared against a sub
     * string of this sequence from the given start and end index. After that
     * the output stream is reset.
     *
     * @param from the start index
     * @param to the end index
     */
    private void checkOutput(int from, int to)
    {
        assertEquals("Wrong output text", generateData(from, to), getOut()
                .toString());
        out.reset();
    }

    /**
     * Helper method for generating a string with text data of the specified
     * length.
     *
     * @param from the start position in the test data
     * @param to the end position in the test data (exclusive)
     * @return the string with the test data
     */
    private String generateData(int from, int to)
    {
        StringBuilder buf = new StringBuilder(TEST_DATA);
        while (buf.length() < to)
        {
            buf.append(TEST_DATA);
        }
        return buf.substring(from, to);
    }

    /**
     * Reads the test stream up to its end.
     *
     * @throws IOException if an error occurs
     */
    private void read() throws IOException
    {
        byte[] buffer = new byte[16];
        int bytesRead;
        while ((bytesRead = stream.read(buffer)) > 0)
        {
            getOut().write(buffer, 0, bytesRead);
        }
    }

    /**
     * Reads the specified number of byte from the test stream and writes it
     * into the output stream.
     *
     * @param bytes the number of bytes to read
     * @return the number of bytes actually read
     * @throws IOException if an error occurs
     */
    private int read(int bytes) throws IOException
    {
        byte[] buf = new byte[bytes];
        int read = stream.read(buf);
        if (read > 0)
        {
            getOut().write(buf, 0, read);
        }
        return read;
    }

    /**
     * Adds the specified number of test child streams to the chained stream.
     *
     * @param count the number of child streams to add
     * @param complete a flag if complete() should be called
     */
    private void addChildStreams(int count, boolean complete)
    {
        for (int i = 0; i < count; i++)
        {
            stream.addStream(createTestStream(), TEST_LEN);
        }
        if (complete)
        {
            stream.complete();
        }
    }

    /**
     * Tests reading a single stream.
     */
    public void testReadSingleStream() throws IOException
    {
        addChildStreams(1, true);
        read();
        checkOutput(0, TEST_LEN);
    }

    /**
     * Tests reading multiple streams.
     */
    public void testReadMultipleStreams() throws IOException
    {
        final int count = 3;
        addChildStreams(count, true);
        read();
        checkOutput(0, count * TEST_LEN);
    }

    /**
     * Tests reading a part of a stream.
     */
    public void testReadStreamPartial() throws IOException
    {
        final int len = 10;
        stream.addStream(createTestStream(), len);
        stream.complete();
        read();
        checkOutput(0, len);
    }

    /**
     * Tests reading multiple stream, the last one will be read partial.
     */
    public void testReadMultiStreamsPartial() throws IOException
    {
        final int len = 10;
        stream.addStream(createTestStream(), TEST_LEN);
        stream.addStream(createTestStream(), len);
        stream.complete();
        read();
        checkOutput(0, TEST_LEN + len);
    }

    /**
     * Tests reading byte per byte. (This is not very efficient.)
     */
    public void testReadByteWise() throws IOException
    {
        final int count = 2;
        addChildStreams(count, true);
        int c;
        while ((c = stream.read()) >= 0)
        {
            getOut().write(c);
        }
        checkOutput(0, count * TEST_LEN);
    }

    /**
     * Tests reading a chunk that spans multiple child streams.
     */
    public void testReadLargeChunk() throws IOException
    {
        final int count = 5;
        final int len = 3 * TEST_LEN + 12;
        addChildStreams(count, true);
        read(len);
        checkOutput(0, len);
    }

    /**
     * Tests if the call back is invoked.
     */
    public void testCallBack() throws IOException
    {
        ChainedInputStreamCallBack callBack = EasyMock
                .createMock(ChainedInputStreamCallBack.class);
        InputStream testStream = createTestStream();
        callBack.streamCompleted(testStream, TEST_LEN, TEST_PARA);
        stream.addStream(testStream, TEST_LEN, callBack, TEST_PARA);
        stream.addStream(createTestStream(), TEST_LEN);
        testStream = createTestStream();
        callBack.streamCompleted(testStream, 3 * TEST_LEN, TEST_PARA);
        stream.addStream(testStream, TEST_LEN, callBack, TEST_PARA);
        stream.complete();
        EasyMock.replay(callBack);
        read();
        checkOutput(0, 3 * TEST_LEN);
        EasyMock.verify(callBack);
    }

    /**
     * Tests the mark operation on a single stream.
     */
    public void testMarkSingleStream() throws IOException
    {
        final int len = 10;
        stream.addStream(createTestStream(), TEST_LEN);
        stream.complete();
        read(len);
        stream.mark(2 * len);
        read(len);
        checkOutput(0, 2 * len);
        stream.reset();
        assertEquals("Wrong current position", len, stream.getReadPosition());
        read();
        checkOutput(len, TEST_LEN);
    }

    /**
     * Tests whether mark() and reset() work over multiple child streams.
     */
    public void testMarkMultipleStreams() throws IOException
    {
        addChildStreams(10, true);
        final int start = 15;
        final int readAhead = 2 * TEST_LEN + 10;
        final int len = readAhead + TEST_LEN + 17;

        read(start);
        checkOutput(0, start);
        stream.mark(readAhead + 2);
        assertEquals("Wrong number of bytes read", readAhead, read(readAhead));
        checkOutput(start, readAhead + start);
        stream.reset();
        assertEquals("Wrong current position", start, stream.getReadPosition());
        assertEquals("Wrong number of bytes read (2)", len, read(len));
        checkOutput(start, len + start);
    }

    /**
     * Tests the mark() method at the very beginning of the stream.
     */
    public void testMarkAtStart() throws IOException
    {
        final int len = 16;
        addChildStreams(2, true);
        stream.mark(TEST_LEN);
        read(len);
        checkOutput(0, len);
        stream.reset();
        read();
        checkOutput(0, 2 * TEST_LEN);
    }

    /**
     * Tests a mark() operation at the very beginning of the stream if a
     * buffered input stream is involved.
     */
    public void testMarkAtStartBufferedStream() throws IOException
    {
        final int len = 128000;
        InputStream in =
                new BufferedInputStream(new ByteArrayInputStream(generateData(
                        0, len).getBytes()));
        stream.addStream(in, len);
        stream.mark(TEST_LEN);
        stream.setIgnoreMarkLimit(true);
        read(len - 1);
        stream.reset();
        out = new ByteArrayOutputStream();
        read(2 * TEST_LEN);
        checkOutput(0, 2 * TEST_LEN);
    }

    /**
     * Tests the call backs in mark mode.
     */
    public void testCallBackWithMark() throws IOException
    {
        ChainedInputStreamCallBack callBack = EasyMock
                .createMock(ChainedInputStreamCallBack.class);
        InputStream testStream = createTestStream();
        stream.addStream(testStream, TEST_LEN, callBack, null);
        addChildStreams(1, true);
        callBack.streamRead(testStream, TEST_LEN, null);
        EasyMock.replay(callBack);
        final int pos = 10;
        read(pos);
        stream.mark(TEST_LEN + pos);
        read(TEST_LEN);
        EasyMock.verify(callBack);
        checkOutput(0, TEST_LEN + pos);

        EasyMock.reset(callBack);
        callBack.streamCompleted(testStream, TEST_LEN, null);
        EasyMock.replay(callBack);
        stream.reset();
        read();
        EasyMock.verify(callBack);
        checkOutput(pos, 2 * TEST_LEN);
    }

    /**
     * Tests the behavior if mark() is called, but then more bytes are read
     * than were specified in the readAhead argument. Then the mark should be
     * freed.
     */
    public void testMarkWithReadAhead() throws IOException
    {
        ChainedInputStreamCallBack callBack = EasyMock
                .createStrictMock(ChainedInputStreamCallBack.class);
        InputStream testStream = createTestStream();
        stream.addStream(testStream, TEST_LEN, callBack, TEST_PARA);
        addChildStreams(1, true);
        callBack.streamRead(testStream, TEST_LEN, TEST_PARA);
        callBack.streamCompleted(testStream, TEST_LEN, TEST_PARA);
        EasyMock.replay(callBack);
        final int pos = 10;
        read(pos);
        checkOutput(0, pos);
        stream.mark(TEST_LEN);
        read();
        EasyMock.verify(callBack);
        checkOutput(pos, 2 * TEST_LEN);
    }

    /**
     * Tests whether the read limit passed to mark() can be ignored.
     */
    public void testMarkIgnoreLimit() throws IOException
    {
        addChildStreams(2, true);
        stream.setIgnoreMarkLimit(true);
        stream.mark(TEST_LEN / 2);
        read(TEST_LEN);
        assertTrue("Mark was reset", stream.isMarked());
    }

    /**
     * Tests calling mark() multiple times. The reset() will use the recent
     * mark() call to find the correct position.
     */
    public void testMultipleMarks() throws IOException
    {
        ChainedInputStreamCallBack callBack = EasyMock
                .createStrictMock(ChainedInputStreamCallBack.class);
        InputStream testStream = createTestStream();
        stream.addStream(testStream, TEST_LEN, callBack, null);
        addChildStreams(1, true);
        callBack.streamRead(testStream, TEST_LEN, null);
        callBack.streamCompleted(testStream, TEST_LEN, null);
        EasyMock.replay(callBack);
        final int pos = 8;
        read(pos);
        checkOutput(0, pos);
        stream.mark(TEST_LEN + pos);
        read(TEST_LEN);
        checkOutput(pos, TEST_LEN + pos);
        stream.mark(TEST_LEN);
        EasyMock.verify(callBack);
        read(pos);
        checkOutput(TEST_LEN + pos, TEST_LEN + 2 * pos);
        stream.reset();
        read();
        checkOutput(TEST_LEN + pos, 2 * TEST_LEN);
    }

    /**
     * Tests a call to reset() when no mark() was called. This should cause an
     * exception.
     */
    public void testResetWithoutMark() throws IOException
    {
        addChildStreams(2, true);
        read(10);
        try
        {
            stream.reset();
            fail("Could reset() stream without mark()!");
        }
        catch (IOException ioex)
        {
            // ok
        }
    }

    /**
     * Tests reading over the end of stream.
     */
    public void testReadTooMuch() throws IOException
    {
        addChildStreams(1, true);
        assertEquals("Too many bytes read", TEST_LEN, read(10 * TEST_LEN));
        checkOutput(0, TEST_LEN);
        assertEquals("EOS not returned", -1, read(1));
    }

    /**
     * Tests whether the stream tells that is supports the mark() operation.
     */
    public void testMarkSupported()
    {
        addChildStreams(1, true);
        assertTrue("Mark is not supported", stream.markSupported());
    }

    /**
     * Tests the isMarked() method after the stream has been created.
     */
    public void testInitIsMarked()
    {
        assertFalse("New stream is marked", stream.isMarked());
    }

    /**
     * Tests the isMarked() method for a marked stream.
     */
    public void testIsMarked() throws IOException
    {
        addChildStreams(2, true);
        read(10);
        assertFalse("Stream is marked", stream.isMarked());
        stream.mark(TEST_LEN);
        assertTrue("Stream is not marked", stream.isMarked());
        read(10);
        assertTrue("Stream is not marked again", stream.isMarked());
        stream.reset();
        assertFalse("Stream is marked after reset", stream.isMarked());
    }

    /**
     * Tests adding a null stream. This should cause an exception.
     */
    public void testAddStreamNull()
    {
        try
        {
            stream.addStream(null, 10L);
            fail("Could add null stream!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests adding a child stream with an invalid number of bytes to read. This
     * should cause an exception.
     */
    public void testAddStreamInvalidBytesToRead()
    {
        try
        {
            stream.addStream(createTestStream(), -1);
            fail("Could add stream with invalid bytes to read!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests setting an offset for the read position.
     */
    public void testSetReadOffset()
    {
        addChildStreams(2, true);
        assertEquals("Wrong current read position", 0, stream.getReadPosition());
        stream.setReadOffset(42);
        assertEquals("Wrong read position with offset", 42, stream
                .getReadPosition());
    }

    /**
     * Tests a blocking read operation that is blocked. In this case nothing
     * should be read.
     */
    public void testReadInterrupted() throws InterruptedException
    {
        ReadRunnable r = new ReadRunnable();
        Thread t = new Thread(r);
        t.start();
        Thread.sleep(SLEEP_TIME);
        assertFalse("Runnable has terminated", r.done);
        assertEquals("Bytes have been written", 0, getOut().size());
        t.interrupt();
        t.join();
        assertEquals("Bytes have been written after interrupt", 0, getOut()
                .size());
    }

    /**
     * Tests a blocking read operation. The read can only be finished after
     * complete() has been called on the stream.
     */
    public void testReadBlocking() throws InterruptedException
    {
        final ReadRunnable r = new ReadRunnable();
        stream.addStream(createTestStream(), TEST_LEN,
                new ChainedInputStreamCallBack()
                {

                    public void streamCompleted(InputStream s, long position,
                            Object param)
                    {
                        assertFalse("Runnable has terminated", r.done);
                        stream.complete();
                    }

                    public void streamRead(InputStream stream, long position,
                            Object param)
                    {
                        fail("Unexpected method call!");
                    }

                }, null);
        Thread t = new Thread(r);
        t.start();
        t.join();
        assertTrue("Runnable has not terminated", r.done);
        checkOutput(0, TEST_LEN);
    }

    /**
     * Tests reading when one of the child streams is empty.
     */
    public void testReadEmptyStream() throws IOException
    {
        addChildStreams(2, false);
        InputStream in = new ByteArrayInputStream(new byte[0]);
        ChainedInputStreamCallBack callBack = EasyMock.createMock(ChainedInputStreamCallBack.class);
        callBack.streamCompleted(in, 2 * TEST_LEN, null);
        EasyMock.replay(callBack);
        stream.addStream(in, 0, callBack, null);
        addChildStreams(1, true);
        read();
        EasyMock.verify(callBack);
        checkOutput(0, 3 * TEST_LEN);
    }

    /**
     * Tests the isEmpty() method for an empty stream.
     */
    public void testIsEmpty() throws IOException
    {
        stream.complete();
        assertTrue("Stream not empty", stream.isEmpty());
        read();
        checkOutput(0, 0);
    }

    /**
     * Tests the isEmpty() method for an empty thread, for which complete() has
     * not been called yet. In this case the return value should be false.
     */
    public void testIsEmptyUncompleted()
    {
        assertFalse("Uncompleted stream is empty", stream.isEmpty());
    }

    /**
     * Tests the isEmpty() method when the stream contains data.
     */
    public void testIsEmptyWithChildren() throws IOException
    {
        stream.addStream(createTestStream(), TEST_LEN);
        assertFalse("Stream is empty", stream.isEmpty());
        read(TEST_LEN);
        assertFalse("Stream empty at end", stream.isEmpty());
        stream.complete();
        assertFalse("Completed stream empty", stream.isEmpty());
        read();
        assertFalse("Completely read stream empty", stream.isEmpty());
    }

    /**
     * Tests querying the size of a chained stream.
     */
    public void testSize()
    {
        assertEquals("Size of empty stream not 0", 0, stream.size());
        addChildStreams(1, false);
        assertEquals("Wrong size", TEST_LEN, stream.size());
        addChildStreams(9, false);
        assertEquals("Wrong size for multiple streams", 10 * TEST_LEN, stream
                .size());
        stream.complete();
        assertEquals("Wrong size after complete()", 10 * TEST_LEN, stream
                .size());
    }

    /**
     * Tests the size() method when the size of the stream was provided at
     * construction time.
     */
    public void testSizeGiven()
    {
        final long streamSize = 10000;
        stream = new ChainedInputStream(streamSize);
        assertEquals("Wrong size after construction", streamSize, stream.size());
        addChildStreams(5, false);
        assertEquals("Wrong size after adding streams", streamSize, stream
                .size());
        stream.complete();
        assertEquals("Wrong size after complete", streamSize, stream.size());
    }

    /**
     * A helper class for testing blocking read operations. This runnable will
     * simple read the test stream. It will be invoked from a different thread.
     */
    class ReadRunnable implements Runnable
    {
        /** A flag if the run() method has been terminated. */
        public volatile boolean done;

        /**
         * Reads the test stream.
         */
        public void run()
        {
            try
            {
                read();
                done = true;
            }
            catch (IOException ioex)
            {
                fail(ioex.toString());
            }
        }
    }
}
