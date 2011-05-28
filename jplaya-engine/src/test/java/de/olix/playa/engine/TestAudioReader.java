package de.olix.playa.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;

import org.easymock.EasyMock;

import junit.framework.TestCase;

/**
 * Test class for AudioReader.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestAudioReader extends TestCase
{
    /** Constant for the prefix of stream names. */
    private static final String STREAM_NAME = "TestStream";

    /** An array with the lengths of a sequence of test streams. */
    private static final int[] STREAM_LENS =
    { 10000, 20000, 512, 16384, 10 };

    /** The reader to be tested. */
    private AudioReader reader;

    protected void setUp() throws Exception
    {
        super.setUp();
        reader = new AudioReader();
    }

    /**
     * Initializes the passed in mock for a data buffer to expect a copy
     * operation of a test stream with the given length.
     *
     * @param mockBuffer the buffer mock
     * @param mockData the audio stream data mock
     * @param streamLen the length of the test stream
     * @throws IOException if an IO error occurs
     */
    protected void setUpBufferMockForStream(DataBuffer mockBuffer,
            AudioStreamData mockData, int streamLen) throws IOException
    {
        EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
        mockBuffer.addNewStream(mockData);
        int offset = 0;
        int len = streamLen;

        try
        {
            while (len >= reader.getChunkSize())
            {
                EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
                byte[] data = StreamHelper.createTestBytes(offset, offset
                        + reader.getChunkSize());
                mockBuffer.addChunk(EasyMock.aryEq(data), EasyMock.eq(0),
                        EasyMock.eq(reader.getChunkSize()));
                offset += reader.getChunkSize();
                len -= reader.getChunkSize();
            }
            if (len > 0)
            {
                EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
                // The final block is partly undefined, so we cannot compare it
                mockBuffer.addChunk((byte[]) EasyMock.anyObject(), EasyMock
                        .eq(0), EasyMock.eq(len));
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
     * Creates a mock for an audio stream data object.
     *
     * @param name the stream's name
     * @param length the stream's length
     * @return the initialized mock object
     */
    private AudioStreamData setUpStreamDataMock(String name, int length)
    {
        AudioStreamData mockData = EasyMock.createMock(AudioStreamData.class);
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
        EasyMock.replay(mockData);
        return mockData;
    }

    /**
     * Tests the state of a newly created audio reader.
     */
    public void testInit()
    {
        assertNull("Audio buffer is set", reader.getAudioBuffer());
        assertNull("Source is set", reader.getStreamSource());
        assertFalse("Close flag is set", reader.isCloseBufferAtEnd());
        assertEquals("Default chunk size not set",
                AudioReader.DEFAULT_CHUNK_SIZE, reader.getChunkSize());
    }

    /**
     * Tests the constructor that takes a data buffer.
     */
    public void testInitWithBuffer()
    {
        DataBuffer buffer = EasyMock.createMock(DataBuffer.class);
        EasyMock.replay(buffer);
        reader = new AudioReader(buffer);
        assertEquals("Wrong buffer set", buffer, reader.getAudioBuffer());
        assertNull("Source is set", reader.getStreamSource());
        assertEquals("Default chunk size not set",
                AudioReader.DEFAULT_CHUNK_SIZE, reader.getChunkSize());
        EasyMock.verify(buffer);
    }

    /**
     * Tries to invoke the read() method when no target buffer was set. This
     * should cause an exception.
     */
    public void testReadUndefinedBuffer() throws InterruptedException
    {
        reader.initStreamSource(new ArrayList<AudioStreamData>().iterator());
        try
        {
            reader.read();
            fail("Could call read() without a buffer!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tries to invoke the read() method when no source object was set. This
     * should cause an exception.
     */
    public void testReadUndefinedSource() throws InterruptedException
    {
        DataBuffer mockBuffer = EasyMock.createMock(DataBuffer.class);
        EasyMock.replay(mockBuffer);
        reader.setAudioBuffer(mockBuffer);
        try
        {
            reader.read();
            fail("Could call read() without a source!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tests reading a sequence of streams with the default chunk size.
     */
    public void testReadDefaultChunkSize() throws IOException,
            InterruptedException
    {
        checkReadWithBufferSize(reader.getChunkSize(), false);
    }

    /**
     * Tests reading a sequence of streams with a large chunk size.
     */
    public void testReadLargeChunkSize() throws IOException,
            InterruptedException
    {
        checkReadWithBufferSize(32768, false);
    }

    /**
     * Tests reading a sequence of streams with a small chunk size.
     */
    public void testReadSmallChunkSize() throws IOException,
            InterruptedException
    {
        checkReadWithBufferSize(256, false);
    }

    /**
     * Tests reading the test streams and then closing the buffer.
     */
    public void testReadWithClose() throws IOException, InterruptedException
    {
        checkReadWithBufferSize(reader.getChunkSize(), true);
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
     * Tests the read operation when the buffer is closed in the middle.
     */
    public void testReadWithBufferClosed() throws InterruptedException,
            IOException
    {
        DataBuffer mockBuffer = EasyMock.createMock(DataBuffer.class);
        Collection<AudioStreamData> col = new ArrayList<AudioStreamData>(3);
        AudioStreamData mockData = setUpStreamDataMock(STREAM_NAME, 1000);
        col.add(mockData);
        setUpBufferMockForStream(mockBuffer, mockData, 1000);
        mockData = setUpStreamDataMock(STREAM_NAME + "1", 10000);
        col.add(mockData);
        EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
        mockBuffer.addNewStream(mockData);
        EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.TRUE).times(2);
        mockBuffer.streamFinished();
        mockData = EasyMock.createMock(AudioStreamData.class);
        EasyMock.replay(mockData);
        col.add(mockData);
        EasyMock.replay(mockBuffer);

        ReaderTestHelper helper = new ReaderTestHelper(mockBuffer, col);
        helper.readAndVerify();
    }

    /**
     * Tests the read operation when an IO exception occurs. This should be
     * caught, and the current stream should be skipped.
     */
    public void testReadWithException() throws InterruptedException,
            IOException
    {
        DataBuffer mockBuffer = EasyMock.createMock(DataBuffer.class);
        Collection<AudioStreamData> col = new ArrayList<AudioStreamData>(3);
        AudioStreamData mockData = setUpStreamDataMock(STREAM_NAME, 1000);
        col.add(mockData);
        setUpBufferMockForStream(mockBuffer, mockData, 1000);

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
        mockData = EasyMock.createMock(AudioStreamData.class);
        EasyMock.expect(mockData.getName()).andReturn(STREAM_NAME + "1");
        EasyMock.expect(mockData.getStream()).andReturn(exStream);
        EasyMock.expect(mockData.size()).andReturn(1000L);
        EasyMock.expect(mockData.getID()).andStubReturn(STREAM_NAME + "1");
        EasyMock.replay(mockData);
        col.add(mockData);
        setUpBufferMockForStream(mockBuffer, mockData, reader.getChunkSize());
        mockData = setUpStreamDataMock(STREAM_NAME + "2", 12000);
        col.add(mockData);
        setUpBufferMockForStream(mockBuffer, mockData, 12000);
        EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
        EasyMock.replay(mockBuffer);

        ReaderTestHelper helper = new ReaderTestHelper(mockBuffer, col);
        helper.readAndVerify();
    }

    /**
     * Tests the run() method. This should simply delegate to read().
     */
    public void testRun() throws InterruptedException
    {
        ReaderTestHelper helper = new ReaderTestHelper();
        helper.initMocks(AudioReader.DEFAULT_CHUNK_SIZE, true);
        helper.initReader();
        Thread t = new Thread(reader);
        t.start();
        t.join();
        helper.verify();
    }

    /**
     * Tests initializing a reader instance with a stream source object.
     */
    public void testInitWithStreamSource()
    {
        AudioStreamSource source = EasyMock.createMock(AudioStreamSource.class);
        EasyMock.replay(source);
        reader = new AudioReader(null, source);
        assertEquals("Wrong source used", source, reader.getStreamSource());
        EasyMock.verify(source);
    }

    /**
     * A helper class for performing tests of the read() method. This class is
     * able to initialize the needed mock objects. It can call read() and verify
     * the mock objects.
     */
    class ReaderTestHelper
    {
        /** Stores the buffer mock. */
        private DataBuffer mockBuffer;

        /** A collection for the stream data. */
        private Collection<AudioStreamData> streamCollection;

        /**
         * Creates a new, uninitialized instance of
         * <code>ReaderTestHelper</code>.
         */
        public ReaderTestHelper()
        {
            this(null, null);
        }

        /**
         * Creates a new instance of <code>ReaderTestHelper</code> and
         * initializes it with the mock objects.
         *
         * @param buffer the buffer mock
         * @param col a collection with the source stream data
         */
        public ReaderTestHelper(DataBuffer buffer,
                Collection<AudioStreamData> col)
        {
            setMockBuffer(buffer);
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

        public DataBuffer getMockBuffer()
        {
            return mockBuffer;
        }

        public void setMockBuffer(DataBuffer mockBuffer)
        {
            this.mockBuffer = mockBuffer;
        }

        /**
         * Initializes mock objects for the data buffer and the test audio
         * streams.
         *
         * @param chunkSize the chunk size to be used
         * @param close a flag whether the buffer should be closed at the end
         */
        public void initMocks(int chunkSize, boolean close)
        {
            reader.setChunkSize(chunkSize);
            reader.setCloseBufferAtEnd(close);
            try
            {
                mockBuffer = EasyMock.createMock(DataBuffer.class);
                streamCollection = new ArrayList<AudioStreamData>(
                        STREAM_LENS.length);
                for (int i = 0; i < STREAM_LENS.length; i++)
                {
                    AudioStreamData mockData = setUpStreamDataMock(STREAM_NAME
                            + i, STREAM_LENS[i]);
                    streamCollection.add(mockData);
                    setUpBufferMockForStream(mockBuffer, mockData,
                            STREAM_LENS[i]);
                }
                EasyMock.expect(mockBuffer.isClosed()).andReturn(Boolean.FALSE);
                if (close)
                {
                    mockBuffer.close();
                }
                EasyMock.replay(mockBuffer);
            }
            catch (IOException ioex)
            {
                // should not happen here
                fail(ioex.toString());
            }
        }

        /**
         * Verifies the mock objects.
         */
        public void verify()
        {
            EasyMock.verify(getMockBuffer());
            EasyMock.verify(getStreamCollection().toArray());
        }

        /**
         * Prepares the read() operation. Passes the mock objects to the reader.
         */
        public void initReader()
        {
            reader.setAudioBuffer(getMockBuffer());
            reader.initStreamSource(getStreamCollection().iterator());
        }

        /**
         * Calls the read() method and verifies the mock objects.
         */
        public void readAndVerify()
        {
            initReader();
            try
            {
                reader.read();
            }
            catch (InterruptedException iex)
            {
                fail("Interrupted exception: " + iex);
            }
            verify();
        }
    }
}
