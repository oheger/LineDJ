package de.olix.playa.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * <p>
 * A class for reading source audio data.
 * </p>
 * <p>
 * An instance of this class is passed a list with
 * <code>{@link AudioStreamData}</code> objects in form of a
 * <code>{@link AudioStreamSource}</code> object. It will then process the
 * list element by element, read the provided audio streams and store their
 * content in a <code>{@link DataBuffer}</code> object. From there the streams
 * can be buffered, further processed, and finally played.
 * </p>
 * <p>
 * The class is quite simple. The list with the input data is simply iterated in
 * a main loop and the protocol of a <code>DataBuffer</code> is used to pass
 * the source data to the buffer. If the buffer has been closed during this
 * operation, the loop will terminate. The class can also be instructed to close
 * the buffer when the list with the source data has been completely processed.
 * If an IO exception occurs during processing of a source stream, this stream
 * will be skipped, and processing will continue with the next stream.
 * </p>
 * <p>
 * The fact that the list with the source data contains
 * <code>AudioStreamData</code> objects make this class independed from a
 * specific data source. Different implementations of the
 * <code>AudioStreamData</code> interface are possible, which obtain their
 * data from many different sources.
 * </p>
 * <p>
 * An instance of this class can be reused. After the source data has been
 * processed, new source data can be set. The reference to the target buffer can
 * also be modified if necessary. But these things should not be done when the
 * main loop is running.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class AudioReader implements Runnable
{
    /** Constant for the default chunk size. */
    static final int DEFAULT_CHUNK_SIZE = 8192;

    /** The logger. */
    private Log log = LogFactory.getLog(getClass());

    /** Stores the reference to the target audio buffer. */
    private DataBuffer audioBuffer;

    /** Stores a reference to the audio data source object. */
    private AudioStreamSource source;

    /** Stores the size of chunks used for copying data. */
    private int chunkSize;

    /**
     * A flag whether the buffer should be closed after the source data has been
     * processed.
     */
    private boolean closeBufferAtEnd;

    /**
     * Creates a new, uninitialized instance of <code>AudioReader</code>.
     */
    public AudioReader()
    {
        this(null, (AudioStreamSource) null);
    }

    /**
     * Creates a new instance of <code>AudioReader</code> and initializes it
     * with the reference to the target audio buffer.
     *
     * @param buffer the target buffer
     */
    public AudioReader(DataBuffer buffer)
    {
        this(buffer, (AudioStreamSource) null);
    }

    /**
     * Creates a new instance of <code>AudioReader</code> and initializes it
     * with the reference to the target audio buffer and an iterator for
     * obtaining the source data.
     *
     * @param buffer the target buffer
     * @param source the source data iterator
     */
    public AudioReader(DataBuffer buffer, Iterator<AudioStreamData> source)
    {
        initialize(buffer);
        initStreamSource(source);
    }

    /**
     * Creates a new instance of <code>AudioReader</code> and initializes it
     * with the reference to the target audio buffer and the source object.
     *
     * @param buffer the target buffer
     * @param src the source object, from which the streams are fetched
     */
    public AudioReader(DataBuffer buffer, AudioStreamSource src)
    {
        initialize(buffer);
        setStreamSource(src);
    }

    /**
     * Initializes this instance.
     *
     * @param buffer the target buffer
     */
    private void initialize(DataBuffer buffer)
    {
        setAudioBuffer(buffer);
        setChunkSize(DEFAULT_CHUNK_SIZE);
    }

    /**
     * Returns the target audio buffer.
     *
     * @return the target buffer
     */
    public DataBuffer getAudioBuffer()
    {
        return audioBuffer;
    }

    /**
     * Sets the target audio buffer. To this object the source data will be
     * passed.
     *
     * @param audioBuffer the target audio buffer
     */
    public void setAudioBuffer(DataBuffer audioBuffer)
    {
        this.audioBuffer = audioBuffer;
    }

    /**
     * Returns the current close buffer at end flag.
     *
     * @return a flag whether the buffer is to be closed when all source data
     * has been processed
     */
    public boolean isCloseBufferAtEnd()
    {
        return closeBufferAtEnd;
    }

    /**
     * Sets the close buffer at end flag. If this flag is set, the audio
     * buffer's <code>close()</code> method will be invoked when no more
     * source data is available.
     *
     * @param closeBufferAtEnd the value of the flag
     */
    public void setCloseBufferAtEnd(boolean closeBufferAtEnd)
    {
        this.closeBufferAtEnd = closeBufferAtEnd;
    }

    /**
     * Returns the <code>{@link AudioStreamSource}</code> object, from which
     * the stream data is obtained.
     *
     * @return the object with the source data
     */
    public AudioStreamSource getStreamSource()
    {
        return source;
    }

    /**
     * Sets the <code>{@link AudioStreamSource}</code> object, from which the
     * stream data is obtained.
     *
     * @param src the object with the source data
     */
    public void setStreamSource(AudioStreamSource src)
    {
        source = src;
    }

    /**
     * Initializes the <code>AudioStreamSource</code> to be used from the
     * given iterator. This implementation will create a special stream source
     * that is backed by the given iterator.
     *
     * @param iterator the iterator with the source data
     */
    public void initStreamSource(Iterator<AudioStreamData> iterator)
    {
        setStreamSource(new IteratorAudioStreamSource(iterator));
    }

    /**
     * Returns the currently used chunk size.
     *
     * @return the chunk size
     */
    public int getChunkSize()
    {
        return chunkSize;
    }

    /**
     * Sets the chunk size to use. When the source streams are read and copied
     * into the target buffer buffers of this size will be used. The concrete
     * value of this property will have some influence on performance and memory
     * usage.
     *
     * @param chunkSize the chunk size
     */
    public void setChunkSize(int chunkSize)
    {
        this.chunkSize = chunkSize;
    }

    /**
     * Processes all source data and passes it to the target buffer. Before this
     * method can be called the target buffer and the source data iterator must
     * have been set, otherwise a <code>IllegalStateException</code> exception
     * is thrown. This is the main method of this class, which does all the
     * processing.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    public void read() throws InterruptedException
    {
        if (getAudioBuffer() == null)
        {
            throw new IllegalStateException("Audio buffer is not set!");
        }
        if (getStreamSource() == null)
        {
            throw new IllegalStateException("Source iterator is not set!");
        }

        log.info("Starting source iteration.");
        byte[] buffer = new byte[getChunkSize()];
        while (!getAudioBuffer().isClosed())
        {
            try
            {
                AudioStreamData asd = getStreamSource().nextAudioStream();
                if (asd.size() < 0)
                {
                    // end marker?
                    break;
                }
                processSourceStream(asd, buffer);
            }
            catch (IOException ioex)
            {
                //TODO correct exception handling
                log.error("Error when reading stream!", ioex);
            }
        }
        log.info("Source iteration completed.");

        if (isCloseBufferAtEnd())
        {
            log.info("Closing audio buffer.");
            try
            {
                getAudioBuffer().close();
            }
            catch (IOException ioex)
            {
                log.warn("Error when closing audio buffer!", ioex);
            }
        }
    }

    public void run()
    {
        try
        {
            read();
        }
        catch (InterruptedException iex)
        {
            log.warn("Operation was interrupted!", iex);
        }
    }

    /**
     * Processes the given source stream. Copies all of its content into the
     * target buffer. If an exception occurs, it is caught and logged. Then the
     * copy process in terminated, so that the next stream can be processed.
     *
     * @param source the source stream to be handled
     * @param buffer the buffer to use for the copy operation
     * @throws InterruptedException if the operation is interrupted
     */
    protected void processSourceStream(AudioStreamData source, byte[] buffer)
            throws InterruptedException
    {
        String streamName = source.getName();
        if (log.isInfoEnabled())
        {
            log.info("Processing source stream " + streamName);
        }

        getAudioBuffer().addNewStream(source);
        try
        {
            copySourceStream(source.getStream(), buffer);
        }
        catch (IOException ioex)
        {
            log.warn("Error when copying source stream " + streamName, ioex);
        }
        getAudioBuffer().streamFinished();
    }

    /**
     * Copies the given source input stream into the target buffer.
     *
     * @param in the source input stream
     * @param buffer the buffer to use for the copy operation
     * @throws IOException if an error occurs
     * @throws InterruptedException if the operation is interrupted
     */
    protected void copySourceStream(InputStream in, byte[] buffer)
            throws IOException, InterruptedException
    {
        int read;

        while ((read = in.read(buffer)) != -1 && !getAudioBuffer().isClosed())
        {
            getAudioBuffer().addChunk(buffer, 0, read);
        }
        in.close();
    }
}
