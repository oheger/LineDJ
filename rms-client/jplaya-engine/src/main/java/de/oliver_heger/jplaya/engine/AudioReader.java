package de.oliver_heger.jplaya.engine;

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
 * An instance of this class is passed a list with {@link AudioStreamData}
 * objects in form of a {@link AudioStreamSource} object. It will then process
 * the list element by element, read the provided audio streams and store their
 * content in a {@link DataBuffer} object. From there the streams can be
 * buffered, further processed, and finally played.
 * </p>
 * <p>
 * The class is quite simple. The list with the input data is simply iterated in
 * a main loop and the protocol of a {@code DataBuffer} is used to pass the
 * source data to the buffer. If the buffer has been closed during this
 * operation, the loop will terminate. The class can also be instructed to close
 * the buffer when the list with the source data has been completely processed.
 * If an IO exception occurs during processing of a source stream, this stream
 * will be skipped, and processing will continue with the next stream.
 * </p>
 * <p>
 * The fact that the list with the source data contains {@code AudioStreamData}
 * objects make this class independent from a specific data source. Different
 * implementations of the {@code AudioStreamData} interface are possible, which
 * obtain their data from many different sources.
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
    private final Log log = LogFactory.getLog(getClass());

    /** Stores the reference to the target audio buffer. */
    private final DataBuffer audioBuffer;

    /** Stores a reference to the audio data source object. */
    private final AudioStreamSource source;

    /** Stores the size of chunks used for copying data. */
    private final int chunkSize;

    /**
     * A flag whether the buffer should be closed after the source data has been
     * processed.
     */
    private final boolean closeBufferAtEnd;

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
        this(buffer, new IteratorAudioStreamSource(source));
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
        this(buffer, src, DEFAULT_CHUNK_SIZE, true);
    }

    /**
     * Creates a new instance of {@code AudioReader} and initializes all
     * properties.
     *
     * @param buffer the target buffer (must not be <b>null</b>)
     * @param src the source object, from which the streams are fetched (must
     *        not be <b>null</b>)
     * @param myChunkSize the chunk size to be used when writing data into the
     *        buffer (must be &lt; 0)
     * @param autoCloseBuffer a flag whether the buffer should be closed when
     *        the end of the playlist is reached
     * @throws IllegalArgumentException if a parameter is invalid
     */
    public AudioReader(DataBuffer buffer, AudioStreamSource src,
            int myChunkSize, boolean autoCloseBuffer)
    {
        if (buffer == null)
        {
            throw new IllegalArgumentException("Audio buffer must not be null!");
        }
        if (src == null)
        {
            throw new IllegalArgumentException(
                    "AudioStreamSource must not be null!");
        }
        if (myChunkSize <= 0)
        {
            throw new IllegalArgumentException("Invalid chunks size: "
                    + myChunkSize);
        }

        audioBuffer = buffer;
        source = src;
        chunkSize = myChunkSize;
        closeBufferAtEnd = autoCloseBuffer;
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
     * Returns the current close buffer at end flag.
     *
     * @return a flag whether the buffer is to be closed when all source data
     *         has been processed
     */
    public boolean isCloseBufferAtEnd()
    {
        return closeBufferAtEnd;
    }

    /**
     * Returns the <code>{@link AudioStreamSource}</code> object, from which the
     * stream data is obtained.
     *
     * @return the object with the source data
     */
    public AudioStreamSource getStreamSource()
    {
        return source;
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
     * Processes all source data and passes it to the target buffer. This is the
     * main method of this class, which does all the processing. It should be
     * called from a separate thread so that the audio reader can work in
     * background.
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
                // TODO correct exception handling
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

    /**
     * Executes the reader. This method is called when the reader is started in
     * a separate thread.
     */
    public void run()
    {
        try
        {
            read();
        }
        catch (InterruptedException iex)
        {
            log.warn("Operation was interrupted!", iex);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Starts this reader in a separate thread. This is a convenience method
     * which calls the reader's main {@code read()} method in a separate thread.
     * The reader starts immediately processing streams provided by the
     * {@code AudioStreamSource}. It ends when the source has no more data or
     * the buffer was closed.
     *
     * @return the thread which runs the reader
     */
    public Thread start()
    {
        Thread t = new Thread(this);
        t.setDaemon(true);
        t.start();
        return t;
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
