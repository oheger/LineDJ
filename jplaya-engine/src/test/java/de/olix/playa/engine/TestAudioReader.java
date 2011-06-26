package de.olix.playa.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code AudioReader}.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestAudioReader extends EasyMockSupport
{
    /** Constant for the prefix of stream names. */
    private static final String STREAM_NAME = "TestStream";

    /** An array with the lengths of a sequence of test streams. */
    private static final int[] STREAM_LENS = {
            10000, 20000, 512, 16384, 10
    };

    /** A mock for the data buffer. */
    private DataBuffer buffer;

    /** A mock for the audio stream source. */
    private AudioStreamSource source;

    @Before
    public void setUp() throws Exception
    {
        buffer = createMock(DataBuffer.class);
        source = createMock(AudioStreamSource.class);
    }

    /**
     * Initializes the passed in mock for a data buffer to expect a copy
     * operation of a test stream with the given length.
     *
     * @param mockBuffer the buffer mock
     * @param mockData the audio stream data mock
     * @param chunkSize the chunk size
     * @param streamLen the length of the test stream
     * @throws IOException if an IO error occurs
     */
    private void setUpBufferMockForStream(DataBuffer mockBuffer,
            AudioStreamData mockData, int chunkSize, int streamLen)
            throws IOException
    {
        EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
        mockBuffer.addNewStream(mockData);
        int offset = 0;
        int len = streamLen;

        try
        {
            while (len >= chunkSize)
            {
                EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
                byte[] data =
                        StreamHelper
                                .createTestBytes(offset, offset + chunkSize);
                mockBuffer.addChunk(EasyMock.aryEq(data), EasyMock.eq(0),
                        EasyMock.eq(chunkSize));
                offset += chunkSize;
                len -= chunkSize;
            }
            if (len > 0)
            {
                EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
                // The final block is partly undefined, so we cannot compare it
                mockBuffer.addChunk((byte[]) EasyMock.anyObject(),
                        EasyMock.eq(0), EasyMock.eq(len));
            }
            mockBuffer.streamFinished();
        }
        catch (InterruptedException iex)
        {
            // cannot happen
            fail(iex.toString());
        }
    }

    /**
     * Initializes a mock for an audio stream data object.
     *
     * @param name the stream's name
     * @param length the stream's length
     * @return the initialized mock object
     */
    private AudioStreamData setUpStreamDataMock(String name, int length)
    {
        AudioStreamData mockData = createMock(AudioStreamData.class);
        EasyMock.expect(mockData.getName()).andReturn(name);
        EasyMock.expect(mockData.getID()).andStubReturn(name);
        EasyMock.expect(mockData.size()).andStubReturn(Long.valueOf(length));
        try
        {
            EasyMock.expect(mockData.getStream()).andReturn(
                    StreamHelper.createTestStream(length));
        }
        catch (IOException ioex)
        {
            // cannot happen here
            fail("Strange exception occurred: " + ioex);
        }
        EasyMock.expect(mockData.size()).andReturn(Long.valueOf(length));
        return mockData;
    }

    /**
     * Tests whether default values for constructor arguments are set.
     */
    @Test
    public void testInitDefaults()
    {
        replayAll();
        AudioReader reader = new AudioReader(buffer, source);
        assertSame("Wrong buffer", buffer, reader.getAudioBuffer());
        assertSame("Wrong source", source, reader.getStreamSource());
        assertTrue("Wrong close flag", reader.isCloseBufferAtEnd());
        assertEquals("Default chunk size not set",
                AudioReader.DEFAULT_CHUNK_SIZE, reader.getChunkSize());
    }

    /**
     * Tries to create an instance without a buffer.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoBuffer() throws InterruptedException
    {
        new AudioReader(null, source);
    }

    /**
     * Tries to create an instance without a source.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoSource() throws InterruptedException
    {
        new AudioReader(buffer, (AudioStreamSource) null);
    }

    /**
     * Tests whether an invalid chunk size is detected.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitInvalidChunkSize()
    {
        new AudioReader(buffer, source, 0, false);
    }

    /**
     * Tests the constructor which expects an iterator.
     */
    @Test
    public void testInitIterator()
    {
        @SuppressWarnings("unchecked")
        Iterator<AudioStreamData> it = createMock(Iterator.class);
        replayAll();
        AudioReader reader = new AudioReader(buffer, it);
        IteratorAudioStreamSource src =
                (IteratorAudioStreamSource) reader.getStreamSource();
        assertSame("Wrong iterator", it, src.getIterator());
    }

    /**
     * Tests reading a sequence of streams with the default chunk size.
     */
    @Test
    public void testReadDefaultChunkSize() throws IOException,
            InterruptedException
    {
        checkReadWithBufferSize(AudioReader.DEFAULT_CHUNK_SIZE, false);
    }

    /**
     * Tests reading a sequence of streams with a large chunk size.
     */
    @Test
    public void testReadLargeChunkSize() throws IOException,
            InterruptedException
    {
        checkReadWithBufferSize(32768, false);
    }

    /**
     * Tests reading a sequence of streams with a small chunk size.
     */
    @Test
    public void testReadSmallChunkSize() throws IOException,
            InterruptedException
    {
        checkReadWithBufferSize(256, false);
    }

    /**
     * Tests reading the test streams and then closing the buffer.
     */
    @Test
    public void testReadWithClose() throws IOException, InterruptedException
    {
        checkReadWithBufferSize(AudioReader.DEFAULT_CHUNK_SIZE, true);
    }

    /**
     * Tests reading a sequence of test streams.
     *
     * @param bufSize the buffer size to use
     * @param close a flag whether the buffer should be closed at the end
     */
    private void checkReadWithBufferSize(int bufSize, boolean close)
    {
        ReaderTestHelper helper = new ReaderTestHelper();
        helper.initMocks(bufSize, close);
        helper.readAndVerify();
    }

    /**
     * Tests the read operation if the buffer is closed in the middle.
     */
    @Test
    public void testReadWithBufferClosed() throws InterruptedException,
            IOException
    {
        Collection<AudioStreamData> col = new ArrayList<AudioStreamData>(3);
        AudioStreamData mockData = setUpStreamDataMock(STREAM_NAME, 1000);
        col.add(mockData);
        setUpBufferMockForStream(buffer, mockData,
                AudioReader.DEFAULT_CHUNK_SIZE, 1000);
        mockData = setUpStreamDataMock(STREAM_NAME + "1", 10000);
        col.add(mockData);
        EasyMock.expect(buffer.isClosed()).andReturn(Boolean.FALSE);
        buffer.addNewStream(mockData);
        EasyMock.expect(buffer.isClosed()).andReturn(Boolean.TRUE).times(2);
        buffer.streamFinished();
        mockData = EasyMock.createMock(AudioStreamData.class);
        col.add(mockData);
        ReaderTestHelper helper = new ReaderTestHelper(col);
        helper.initMocks(AudioReader.DEFAULT_CHUNK_SIZE, false);
        helper.readAndVerify();
    }

    /**
     * Tests the read operation when an IO exception occurs. This should be
     * caught, and the current stream should be skipped.
     */
    @Test
    public void testReadWithException() throws InterruptedException,
            IOException
    {
        Collection<AudioStreamData> col = new ArrayList<AudioStreamData>(3);
        AudioStreamData mockData = setUpStreamDataMock(STREAM_NAME, 1000);
        col.add(mockData);
        setUpBufferMockForStream(buffer, mockData,
                AudioReader.DEFAULT_CHUNK_SIZE, 1000);

        // A stream that will throw an exception on the 2nd read invocation
        InputStream exStream = new InputStream()
        {
            int count;

            @Override
            public int read(byte[] b) throws IOException
            {
                if (++count < 2)
                {
                    System.arraycopy(StreamHelper.createTestBytes(0, b.length),
                            0, b, 0, b.length);
                    return b.length;
                }
                else
                {
                    throw new IOException("Test exception!");
                }
            }

            @Override
            public int read() throws IOException
            {
                // Won't be called
                throw new UnsupportedOperationException("Not yet implemented!");
            }
        };
        mockData = createMock(AudioStreamData.class);
        EasyMock.expect(mockData.getName()).andReturn(STREAM_NAME + "1");
        EasyMock.expect(mockData.getStream()).andReturn(exStream);
        EasyMock.expect(mockData.size()).andReturn(1000L);
        EasyMock.expect(mockData.getID()).andStubReturn(STREAM_NAME + "1");
        col.add(mockData);
        setUpBufferMockForStream(buffer, mockData,
                AudioReader.DEFAULT_CHUNK_SIZE, AudioReader.DEFAULT_CHUNK_SIZE);
        mockData = setUpStreamDataMock(STREAM_NAME + "2", 12000);
        col.add(mockData);
        setUpBufferMockForStream(buffer, mockData,
                AudioReader.DEFAULT_CHUNK_SIZE, 12000);
        EasyMock.expect(buffer.isClosed()).andReturn(Boolean.FALSE);

        ReaderTestHelper helper = new ReaderTestHelper(col);
        helper.initMocks(AudioReader.DEFAULT_CHUNK_SIZE, false);
        helper.readAndVerify();
    }

    /**
     * Tests whether the reader can be started in a separate thread.
     */
    @Test
    public void testStart() throws InterruptedException
    {
        final StringBuilder bufMethods = new StringBuilder();
        AudioReader reader = new AudioReader(buffer, source)
        {
            @Override
            public void read() throws InterruptedException
            {
                bufMethods.append("read()");
            }
        };
        replayAll();
        Thread t = reader.start();
        assertTrue("Not a deamon thread", t.isDaemon());
        t.join();
        assertEquals("Method not called", "read()", bufMethods.toString());
        verifyAll();
    }

    /**
     * Tests whether an interrupted exception is handled by run().
     */
    @Test
    public void testRunInterruptedException()
    {
        replayAll();
        AudioReader reader = new AudioReader(buffer, source)
        {
            @Override
            public void read() throws InterruptedException
            {
                throw new InterruptedException("Test exception!");
            };
        };
        reader.run();
        assertTrue("Not interrupted", Thread.interrupted());
        verifyAll();
    }

    /**
     * A helper class for performing tests of the read() method. This class is
     * able to initialize the needed mock objects. It can call read() and verify
     * the mock objects.
     */
    class ReaderTestHelper
    {
        /** A collection for the stream data. */
        private Collection<AudioStreamData> streamCollection;

        /** The reader to be tested. */
        private AudioReader reader;

        /**
         * Creates a new, uninitialized instance of {@code ReaderTestHelper}.
         */
        public ReaderTestHelper()
        {
            this(null);
        }

        /**
         * Creates a new instance of ReaderTestHelper and initializes it with
         * the mock objects.
         *
         * @param col a collection with the source stream data
         */
        public ReaderTestHelper(Collection<AudioStreamData> col)
        {
            setStreamCollection(col);
        }

        public Collection<AudioStreamData> getStreamCollection()
        {
            return streamCollection;
        }

        public void setStreamCollection(Collection<AudioStreamData> col)
        {
            this.streamCollection = col;
        }

        /**
         * Initializes the test helper class. If a collection with mock stream
         * data objects has not been set, it is created now with default data.
         * The test reader instance is created.
         *
         * @param chunkSize the chunk size to be used
         * @param close a flag whether the buffer should be closed at the end
         */
        public void initMocks(int chunkSize, boolean close)
        {
            if (streamCollection == null)
            {
                try
                {
                    streamCollection =
                            new ArrayList<AudioStreamData>(STREAM_LENS.length);
                    for (int i = 0; i < STREAM_LENS.length; i++)
                    {
                        AudioStreamData mockData =
                                setUpStreamDataMock(STREAM_NAME + i,
                                        STREAM_LENS[i]);
                        streamCollection.add(mockData);
                        setUpBufferMockForStream(buffer, mockData, chunkSize,
                                STREAM_LENS[i]);
                    }
                    EasyMock.expect(buffer.isClosed()).andReturn(Boolean.FALSE);
                    if (close)
                    {
                        buffer.close();
                    }
                }
                catch (IOException ioex)
                {
                    // should not happen here
                    fail(ioex.toString());
                }
            }

            reader = new AudioReader(buffer, createSource(), chunkSize, close);
        }

        /**
         * Calls the read() method and verifies the mock objects.
         */
        public void readAndVerify()
        {
            if (reader == null)
            {
                reader = new AudioReader(buffer, createSource());
            }
            replayAll();
            try
            {
                reader.read();
            }
            catch (InterruptedException iex)
            {
                fail("Interrupted exception: " + iex);
            }
            verifyAll();
        }

        /**
         * Creates an iterator stream source for the collection with streams.
         *
         * @return the source
         */
        private IteratorAudioStreamSource createSource()
        {
            return new IteratorAudioStreamSource(streamCollection.iterator());
        }
    }
}
