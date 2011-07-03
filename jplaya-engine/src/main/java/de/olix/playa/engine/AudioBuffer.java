package de.olix.playa.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.swing.event.EventListenerList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * <p>
 * A class for buffering audio data.
 * </p>
 * <p>
 * This class is used for buffering audio data that can be obtained from a CD
 * ROM or over the network on the user's hard disk. It reserves a buffer of a
 * configurable size at a given directory. This buffer is divided in a number of
 * chunks (which can also be configured). Each chunk corresponds to a physical
 * file in the data directory. The buffer can be filled with data through the
 * methods of the {@link DataBuffer} interface. When data is added temporary
 * files for the chunks are created if necessary. If the the buffer is full,
 * write operations will block.
 * </p>
 * <p>
 * Concurrently data can be read via streams obtained through the methods of the
 * {@link AudioStreamSource} interface. These streams point to the data files
 * created for the single chunks. (In fact they are instances of the
 * {@link ChainedInputStream} class, that allows to treat the content of
 * multiple chunk files as a single stream.) If the buffer is closed, an empty
 * stream will be returned by {@code nextAudioStream()}. This can be used by a
 * client to determine when there is no more data.
 * </p>
 * <p>
 * When ever a chunk has been completely read the corresponding file is deleted
 * from the data directory. Its space is then available for a new chunk file. So
 * if there are blocking write operations, the corresponding threads can then
 * continue with their work. This way the size of the buffer remains constantly
 * under the specified limit while data is concurrently read and written.
 * </p>
 * <p>
 * An exception to this rule can occur when the mark operation is used on the
 * audio stream. In this case data that has been marked cannot simply be
 * removed. So if further data is requested, it will be loaded into the buffer,
 * even if its size limit is reached. If this is necessary and how many data
 * must be re-read during a mark operation depends on the audio format.
 * </p>
 * <p>
 * This class supports a single reader and a single writer thread. In that way
 * it is thread-safe, i.e. data can be concurrently read and written.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class AudioBuffer implements DataBuffer, AudioStreamSource,
        ChainedInputStreamCallBack
{
    /** Constant for the prefix of chunk temporary files. */
    private static final String FILE_PREFIX = "CHNK";

    /**
     * Constant for a threshold of <em>DATA_ADDED</em> events. This value
     * defines a period in milliseconds. In this period only a single add event
     * will be fired.
     */
    private static final long ADD_EVENT_THRESHOLD = 1000;

    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    /** Stores the directory with the file cache. */
    private final File cacheDirectory;

    /** A queue which stores the available audio streams. */
    private final BlockingQueue<AudioStreamData> availableStreams;

    /**
     * A set with the currently existing data files. This object is also used as
     * a lock when writing of chunk files is involved.
     */
    private final Map<File, InputStream> dataFiles;

    /** A collection with the currently existing parts. */
    private final Collection<ChunkDataPart> parts;

    /** Stores the current chunk part. */
    private ChunkDataPart currentPart;

    /** A set with streams, for which read call backs have been received. */
    private final Set<InputStream> readCallBacks;

    /** The output stream for the current chunk. */
    private OutputStream out;

    /** The current chunk file. */
    private File currentChunkFile;

    /** The lock for manipulating chunk counts. */
    private final Lock chunkLock;

    /** The condition for signaling a waiting writer thread. */
    private final Condition chunksNotFull;

    /** Stores the registered event listeners. */
    private final EventListenerList listeners;

    /** A queue for sending events. */
    private final BlockingQueue<AudioBufferEvent.Type> events;

    /** A reference to the thread for sending events. */
    private final EventThread eventThread;

    /** The size of a chunk in bytes. */
    private final long chunkSize;

    /** Stores the number of bytes written into the current chunk. */
    private long currentChunkSize;

    /** Stores the time of the last <em>DATA_ADDED</em> event. */
    private long lastDataAddedEvent;

    /** Stores the initial number of chunks. */
    private final int chunkCount;

    /** Stores the number of allowed chunks. */
    private int allowedChunkCount;

    /** Stores the number of currently existing chunks. */
    private int currentChunkCount;

    /** A flag whether the cache directory is to be cleared. */
    private final boolean clearCacheDirectory;

    /** A flag whether this buffer has been closed. */
    private volatile boolean closed;

    /** A flag whether the buffer is full. */
    private boolean full;

    /** A flag whether this instance has already been initialized. */
    private boolean initialized;

    /**
     * Creates a new instance of {@code AudioBuffer} and initializes it. This is
     * equivalent to {@code AudioBuffer(dir, chunkSize, chunks, false);}.
     *
     * @param dir the path to the cache directory
     * @param chunkSize the size of a chunk in bytes
     * @param chunks the number of chunks
     */
    public AudioBuffer(String dir, long chunkSize, int chunks)
    {
        this(dir, chunkSize, chunks, false);
    }

    /**
     * Creates a new instance of {@code AudioBuffer}, initializes it, and
     * optionally clears the cache directory. With this constructor remaining
     * artifacts of a previous run can be removed from the cache directory.
     * (Actually the cache directory is cleared when the instance is
     * initialized.)
     *
     * @param dir the path to the cache directory
     * @param chunkSize the size of a chunk in bytes
     * @param chunks the number of chunks
     * @param clearCache a flag whether the cache directory should be cleared
     */
    public AudioBuffer(String dir, long chunkSize, int chunks,
            boolean clearCache)
    {
        if (dir == null)
        {
            throw new IllegalArgumentException(
                    "Cache directory must not be null!");
        }
        if (chunkSize < 1)
        {
            throw new IllegalArgumentException(
                    "Chunk size must not be less than 1!");
        }
        if (chunks < 1)
        {
            throw new IllegalArgumentException(
                    "Number of chunks must be greater than 0!");
        }

        cacheDirectory = new File(dir);
        clearCacheDirectory = clearCache;
        this.chunkSize = chunkSize;
        chunkCount = chunks;
        allowedChunkCount = chunks;

        dataFiles = new LinkedHashMap<File, InputStream>();
        parts = new LinkedList<ChunkDataPart>();
        readCallBacks = new HashSet<InputStream>();
        availableStreams = new LinkedBlockingQueue<AudioStreamData>();
        chunkLock = new ReentrantLock();
        chunksNotFull = chunkLock.newCondition();

        listeners = new EventListenerList();
        events = new LinkedBlockingQueue<AudioBufferEvent.Type>();
        eventThread = new EventThread();
    }

    /**
     * Initializes the buffer. This method has to be called before the buffer
     * can actually be used. It is normally not necessary to invoke this method
     * manually because it is automatically called before a write operation.
     *
     * @throws IOException if an IO exception occurs
     */
    public void initialize() throws IOException
    {
        checkCacheDir(cacheDirectory);
        if (clearCacheDirectory)
        {
            clearCacheDirectory();
        }
        initFirstChunkFile();
        eventThread.start();
        initialized = true;
    }

    /**
     * Returns the size of this buffer. This is the maximum size set in the
     * constructor. The current size may differ from this.
     *
     * @return the size of this buffer
     */
    public long getChunkSize()
    {
        return chunkSize;
    }

    /**
     * Returns the cache directory used by this buffer.
     *
     * @return the cache directory
     */
    public File getCacheDirectory()
    {
        return cacheDirectory;
    }

    /**
     * Returns the number of chunks.
     *
     * @return the number of chunks
     */
    public int getChunkCount()
    {
        return chunkCount;
    }

    /**
     * Returns the number of currently used chunks. Note that this value may
     * even be greater than the number of chunks specified in the constructor.
     * If mark operations are involved, it may be necessary to fetch more data.
     *
     * @return the current number of used chunks
     */
    public int getCurrentChunkCount()
    {
        return currentChunkCount;
    }

    /**
     * Tests whether the buffer is currently full. This means that a write
     * operation will block until enough data has been read from the buffer.
     *
     * @return a flag whether the buffer is full
     */
    public boolean isFull()
    {
        chunkLock.lock();
        try
        {
            return full;
        }
        finally
        {
            chunkLock.unlock();
        }
    }

    /**
     * Modifies the number of currently used chunks. This will also trigger an
     * event.
     *
     * @param c the new number of chunks
     */
    private void setCurrentChunkCount(int c)
    {
        currentChunkCount = c;
        fireBufferEvent(AudioBufferEvent.Type.CHUNK_COUNT_CHANGED);
    }

    /**
     * Returns the next audio stream for reading. This call may block if no
     * audio data is available.
     *
     * @return a data object for the next audio stream
     * @throws InterruptedException if the operation is interrupted
     */
    public AudioStreamData nextAudioStream() throws InterruptedException
    {
        return availableStreams.take();
    }

    /**
     * Writes data to the current data stream. This operation may block if the
     * buffer is full.
     *
     * @param data the data buffer
     * @param ofs the offset into the data buffer
     * @param len the length of the data to write
     * @throws InterruptedException if the operation is interrupted
     * @throws IOException if an IO error occurs
     */
    public void addChunk(byte[] data, int ofs, int len) throws IOException,
            InterruptedException
    {
        ensureInitialized();
        int bytesWritten = 0;
        int curOfs = ofs;

        while (!isClosed() && bytesWritten < len)
        {
            boolean chunkFull = false;
            synchronized (getWriteLock())
            {
                int cnt =
                        (int) Math.min(len - bytesWritten,
                                getRemainingChunkSize());
                if (cnt > 0)
                {
                    out.write(data, curOfs, cnt);
                    bytesWritten += cnt;
                    curOfs += cnt;
                    currentChunkSize += cnt;
                    currentPart.addBytes(cnt);
                }
                chunkFull = currentChunkSize >= getChunkSize();
            }

            if (chunkFull && bytesWritten < len)
            {
                openChunk();
            }
        }

        fireBufferEvent(AudioBufferEvent.Type.DATA_ADDED);
    }

    /**
     * Adds a new stream to this buffer.
     *
     * @param data a data object describing the new stream
     */
    public void addNewStream(AudioStreamData data)
    {
        chunkLock.lock();
        try
        {
            if (!isClosed())
            {
                currentPart =
                        new ChunkDataPart(data.getName(), data.getID(),
                                data.size());
            }
        }
        finally
        {
            chunkLock.unlock();
        }
    }

    /**
     * Tells the buffer that the current stream has been completely written.
     */
    public void streamFinished()
    {
        chunkLock.lock();
        try
        {
            if (!isClosed())
            {
                parts.add(currentPart);
                currentPart = null;
            }
        }
        finally
        {
            chunkLock.unlock();
        }
    }

    /**
     * Notifies this object that a stream was completely read. In this case the
     * stream can be closed and the corresponding chunk file (which is passed as
     * the call back parameter) can be deleted. If the buffer has not been
     * extended because of an involved mark operation, an eventually waiting
     * writer thread must be signalled. Otherwise one of the additionally added
     * chunks can be removed again.
     *
     * @param stream the affected stream
     * @param position the current read position
     * @param param the call back parameter
     */
    public void streamCompleted(InputStream stream, long position, Object param)
    {
        if (log.isDebugEnabled())
        {
            log.debug("Stream was completely read; position is " + position);
        }

        File chunkFile = (File) param;
        InputStream streamToClose = null;
        chunkLock.lock();
        try
        {
            if (isClosed())
            {
                return;
            }
            readCallBacks.remove(stream);
            streamToClose = dataFiles.remove(chunkFile);

            setCurrentChunkCount(getCurrentChunkCount() - 1);
            if (getAllowedChunkCount() > getChunkCount())
            {
                allowedChunkCount--;
            }
            else
            {
                chunksNotFull.signal();
            }
        }
        finally
        {
            chunkLock.unlock();
        }

        try
        {
            streamToClose.close();
            log.info("Closed input stream for file " + chunkFile);
        }
        catch (IOException ioex)
        {
            log.warn("Could not close input stream", ioex);
        }
        if (!chunkFile.delete())
        {
            log.warn("Could not delete chunk file " + chunkFile);
        }
    }

    /**
     * Notifies this object that a stream was read that has a mark set. If this
     * was the last completely filled stream and the buffer is full, we will
     * allow an additional chunk and continue writing.
     *
     * @param stream the affected stream
     * @param position the current read position
     * @param param the param (this is the file representing the chunk)
     */
    public void streamRead(InputStream stream, long position, Object param)
    {
        if (log.isDebugEnabled())
        {
            log.debug("Stream was read in mark mode; position is " + position);
        }

        chunkLock.lock();
        try
        {
            if (isClosed())
            {
                return;
            }

            if (!readCallBacks.contains(stream))
            {
                readCallBacks.add(stream);
                if (getCurrentChunkCount() == getAllowedChunkCount())
                {
                    // is it the last chunk?
                    if (getChunkIndex((File) param) >= getAllowedChunkCount() - 2)
                    {
                        allowedChunkCount++;
                        chunksNotFull.signal();
                    }
                }
            }
        }
        finally
        {
            chunkLock.unlock();
        }
    }

    /**
     * Determines the index (the chunk number) of the given chunk file.
     *
     * @param chunkFile the chunk file
     * @return the index of this file
     */
    private int getChunkIndex(File chunkFile)
    {
        int index = 0;
        for (Iterator<File> it = dataFiles.keySet().iterator(); it.hasNext(); index++)
        {
            if (chunkFile == it.next())
            {
                return index;
            }
        }
        return -1;
    }

    /**
     * Returns the number of audio streams that can be fetched using
     * <code>nextAudioStream()</code> without blocking.
     *
     * @return the number of available audio streams
     */
    public int availableStreams()
    {
        return availableStreams.size();
    }

    /**
     * Checks whether this object has already been closed.
     *
     * @return a flag if this buffer has been closed
     */
    public boolean isClosed()
    {
        return closed;
    }

    /**
     * Tells this buffer that no more data will be added to it. This method must
     * be called when the end of the audio data is reached. It ensures that the
     * last chunk will be processed and made available for reading even if it is
     * only partially filled. After that write operations will be ignored.
     *
     * @throws IOException if an error occurs
     */
    public void close() throws IOException
    {
        if (!isClosed())
        {
            closed = true;
            chunkLock.lock();
            try
            {
                synchronized (getWriteLock())
                {
                    closeChunk();
                    closeCurrentOutStream();
                }

                // Add an empty stream to the queue as mark that this
                // buffer was closed
                availableStreams.put(new EndAudioStreamData());

                // Wake up threads waiting at the chunkFull condition
                chunksNotFull.signal();
            }
            catch (InterruptedException iex)
            {
                // This should not happen here
                log.warn("Interrupted exception occurred", iex);
            }
            finally
            {
                chunkLock.unlock();
            }

            fireBufferEvent(AudioBufferEvent.Type.BUFFER_CLOSED);
            try
            {
                eventThread.join();
            }
            catch (InterruptedException iex)
            {
                log.warn("Interrupted when waiting for event thread!", iex);
            }
        }
    }

    /**
     * Performs a shutdown on this buffer. This implementation calls the methods
     * {@link #close()} and {@link #clear()}. Exceptions that might be thrown by
     * {@code close()} are ignored.
     */
    @Override
    public void shutdown()
    {
        try
        {
            close();
        }
        catch (IOException ioex)
        {
            log.warn("Exception when closing buffer!", ioex);
        }
        clear();
    }

    /**
     * Clears the buffer. All temporary files will be removed. Before this the
     * {@code close()} method must have been invoked.
     */
    public void clear()
    {
        chunkLock.lock();
        try
        {
            if (!isClosed())
            {
                throw new IllegalStateException(
                        "clear() can only be called after close()!");
            }

            for (File f : dataFiles.keySet())
            {
                InputStream is = dataFiles.get(f);
                if (is != null)
                {
                    try
                    {
                        is.close();
                    }
                    catch (IOException ioex)
                    {
                        log.warn("Could not close input stream for file " + f,
                                ioex);
                    }
                }
                if (!f.delete())
                {
                    log.warn("Cannot delete chunk file " + f);
                }
            }
            dataFiles.clear();
        }
        finally
        {
            chunkLock.unlock();
        }
    }

    /**
     * Removes all files from the cache directory. This method can be used to
     * clean up the cache directory, which may be necessary for instance after
     * an irregular shutdown of the audio engine. It will remove all files found
     * in the cache directory (sub directories are not affected). Note: This
     * method must not be called while the buffer is active; call
     * <code>close()</code> and <code>clear()</code> before. A constructor is
     * provided that allows to clear the cache directory at startup.
     *
     * @return a flag whether all files could be removed from the cache
     *         directory
     */
    public boolean clearCacheDirectory()
    {
        boolean ok = true;
        File[] files = getCacheDirectory().listFiles();
        if (files != null)
        {
            for (File f : files)
            {
                if (f.isFile() && !f.delete())
                {
                    ok = false;
                    log.warn("Cannot delete file from cache: " + f.getName());
                }
            }
        }

        return ok;
    }

    /**
     * Returns a list with data files that have been created for storing the
     * buffer's content. Note that the returned list is only a snapshot. The
     * buffer's content may change over time.
     *
     * @return a list with the currently existing temporary files
     */
    public List<File> getDataFiles()
    {
        return new ArrayList<File>(dataFiles.keySet());
    }

    /**
     * Returns the current size of this buffer. This is the number of bytes that
     * are currently used in the cache directory.
     *
     * @return the current size of this buffer in bytes
     */
    public long getCurrentSize()
    {
        if (!isInitialized())
        {
            return 0;
        }

        chunkLock.lock();
        try
        {
            return (getCurrentChunkCount() - 1) * getChunkSize()
                    + currentChunkSize;
        }
        finally
        {
            chunkLock.unlock();
        }
    }

    /**
     * Returns the number of currently allowed chunks. At the beginning this is
     * the same number as was specified in the constructor for the number of
     * chunks. If mark operations are involved (i.e. the
     * <code>streamRead()</code> call back is triggered), the number returned by
     * this method may increase.
     *
     * @return the number of currently allowed chunks
     */
    public int getAllowedChunkCount()
    {
        return allowedChunkCount;
    }

    /**
     * Adds a new audio buffer listener to this object. This listener will
     * receive notifications about changes in the state of this buffer.
     *
     * @param l the listener to register (must not be <b>null</b>)
     * @throws IllegalArgumentException if the listener is <b>null</b>
     */
    public void addBufferListener(AudioBufferListener l)
    {
        if (l == null)
        {
            throw new IllegalArgumentException(
                    "Buffer listener must not be null!");
        }
        listeners.add(AudioBufferListener.class, l);
    }

    /**
     * Removes the specified listener from this buffer.
     *
     * @param l the listener to remove
     */
    public void removeBufferListener(AudioBufferListener l)
    {
        listeners.remove(AudioBufferListener.class, l);
    }

    /**
     * Notifies all registered event listeners about a change of the given type.
     * This implementation does not directly send an event to the registered
     * listeners; it rather schedules the event for being send. The sending
     * operation will be performed by a background thread. If the passed in
     * event type is <em>DATA_ADDED</em>, the method will only pass it to the
     * listeners if a certain amount of time has passed since the last event of
     * this type. This is due to the fact that, while the buffer is filled, a
     * large amount of <em>DATA_ADDED</em> events will occur, which can cause a
     * high CPU load. There is no point in propagating each of these events to
     * the registered listeners.
     *
     * @param type the event type
     */
    protected void fireBufferEvent(AudioBufferEvent.Type type)
    {
        if (type == AudioBufferEvent.Type.DATA_ADDED)
        {
            synchronized (getWriteLock())
            {
                long now = System.currentTimeMillis();
                if (now - lastDataAddedEvent <= ADD_EVENT_THRESHOLD)
                {
                    // last event not long enough in the past
                    return;
                }
                lastDataAddedEvent = now;
            }
        }

        events.offer(type);
    }

    /**
     * This method is invoked for every child stream that is added to a chained
     * input stream. It is mainly used for testing whether the correct child
     * streams and call backs are registered.
     *
     * @param stream the chained input stream
     * @param child the child stream
     * @param len the length of the child stream
     * @param callBack the call back to register
     * @param param the file that will be passed as parameter to the call back
     */
    void appendStream(ChainedInputStream stream, InputStream child, long len,
            ChainedInputStreamCallBack callBack, File param)
    {
        stream.addStream(child, len, callBack, param);
    }

    /**
     * Creates a new chunk file. The file will be created in the cache
     * directory. It will be added to the list of chunk files. An output stream
     * to that file will be opened.
     *
     * @throws IOException if an IO error occurs
     */
    private void createChunkFile() throws IOException
    {
        currentChunkFile =
                File.createTempFile(FILE_PREFIX, null, getCacheDirectory());
        dataFiles.put(currentChunkFile, null);
        out = new BufferedOutputStream(new FileOutputStream(currentChunkFile));
        log.info("Creating out stream for chunk file " + currentChunkFile);
    }

    /**
     * Closes the current chunk. All data that have been written into this chunk
     * will now be made available in the queue with the streams.
     *
     * @throws IOException if an IO error occurs
     * @throws InterruptedException if the operation is interrupted
     */
    private void closeChunk() throws IOException, InterruptedException
    {
        closeCurrentOutStream();
        if (currentChunkFile == null)
        {
            return;
        }

        InputStream in = dataFiles.get(currentChunkFile);
        if (in == null)
        {
            log.info("Creating input stream for " + currentChunkFile);
            in = new BufferedInputStream(new FileInputStream(currentChunkFile));
            dataFiles.put(currentChunkFile, in);
        }

        int callBackPartIndex = parts.size() - 1;
        if (currentPart != null && !currentPart.isEmpty())
        {
            callBackPartIndex++;
        }
        int index = 0;
        for (ChunkDataPart part : parts)
        {
            appendAudioStreamData(part, in, index == callBackPartIndex);
            part.complete();
            index++;
        }

        if (callBackPartIndex == parts.size())
        {
            appendAudioStreamData(currentPart, in, true);
        }
        parts.clear();
    }

    /**
     * Closes the current output stream. Exceptions are ignored.
     */
    private void closeCurrentOutStream()
    {
        if (out != null)
        {
            try
            {
                log.info("Closing out stream. Current file is "
                        + currentChunkFile);
                out.close();
            }
            catch (IOException ioex)
            {
                log.warn("Could not close current output stream!", ioex);
            }
            out = null;
        }
    }

    /**
     * Opens a new chunk. This method is called when the current chunk is full.
     * It will close this chunk and then try to create a new chunk file. This
     * operation may block if the buffer is full.
     *
     * @throws IOException if an IO error occurs
     * @throws InterruptedException if the operation is interrupted
     */
    private void openChunk() throws IOException, InterruptedException
    {
        chunkLock.lock();
        try
        {
            if (isClosed())
            {
                return;
            }

            // Close current chunk and wait until the buffer is not full
            closeChunk();
            if (getCurrentChunkCount() == getAllowedChunkCount())
            {
                full = true;
                fireBufferEvent(AudioBufferEvent.Type.BUFFER_FULL);
                chunksNotFull.await();
            }

            if (!isClosed())
            {
                full = false;
                fireBufferEvent(AudioBufferEvent.Type.BUFFER_FREE);
                createChunkFile();
                setCurrentChunkCount(getCurrentChunkCount() + 1);
                currentChunkSize = 0;
            }
        }
        finally
        {
            chunkLock.unlock();
        }
    }

    /**
     * Creates an <code>AudioStreamData</code> object from the given part object
     * if necessary and adds it to the queue with available streams.
     *
     * @param part the part object
     * @param in the input stream to use
     * @param setCallBack a flag whether a call back is to be registered
     * @throws InterruptedException if the operation is interrupted
     */
    private void appendAudioStreamData(ChunkDataPart part, InputStream in,
            boolean setCallBack) throws InterruptedException
    {
        ChainedInputStreamCallBack callBack;
        File callBackParam;

        if (setCallBack)
        {
            callBack = this;
            callBackParam = currentChunkFile;
        }
        else
        {
            callBack = null;
            callBackParam = null;
        }

        ChainedInputStream stream =
                part.getAppendStream(in, callBack, callBackParam);
        if (stream != null)
        {
            availableStreams.put(new AudioStreamDataImpl(part.getName(), part
                    .getID(), stream));
        }
    }

    /**
     * Returns the object used as lock for write operations.
     *
     * @return the write lock
     */
    private Object getWriteLock()
    {
        return dataFiles;
    }

    /**
     * Tests the passed in cache directory. If it does not exist, it will be
     * created now.
     *
     * @param dir the cache directory
     */
    private void checkCacheDir(File dir)
    {
        if (!dir.exists())
        {
            dir.mkdirs();
        }
    }

    /**
     * Returns the remaining free space in the current chunk.
     *
     * @return the remaining size of the current chunk
     */
    private long getRemainingChunkSize()
    {
        return getChunkSize() - currentChunkSize;
    }

    /**
     * Checks whether this object has already been initialized.
     *
     * @return a flag whether the object has been initialized
     */
    private boolean isInitialized()
    {
        synchronized (getWriteLock())
        {
            return initialized;
        }
    }

    /**
     * Ensures that this instance has been initialized. This method is called
     * before data is written into the buffer. It takes care that a chunk file
     * has been created. It also deals with clearing the cache directory if
     * necessary.
     *
     * @throws IOException if an IO error occurs
     */
    private void ensureInitialized() throws IOException
    {
        synchronized (getWriteLock())
        {
            if (!isInitialized())
            {
                initialize();
            }
        }
    }

    /**
     * Creates the first chunk file used by this buffer. This method is called
     * on first write access to the buffer.
     *
     * @throws IOException if an IO exception occurs
     */
    private void initFirstChunkFile() throws IOException
    {
        createChunkFile();
        currentChunkCount = 1;
    }

    /**
     * A default implementation of the <code>AudioStreamDataImpl</code>
     * interface.
     */
    static class AudioStreamDataImpl implements AudioStreamData
    {
        /** Stores the name of this stream. */
        private String name;

        /** Stores the ID of this stream. */
        private Object streamID;

        /** Stores the stream itself. */
        private ChainedInputStream stream;

        /**
         * Creates a new instance of <code>AudioStreamDataImpl</code>.
         *
         * @param n the name
         * @param id the stream's ID
         * @param str the stream
         */
        public AudioStreamDataImpl(String n, Object id, ChainedInputStream str)
        {
            name = n;
            streamID = id;
            stream = str;
        }

        /**
         * Returns the name of this stream.
         *
         * @return the name
         */
        public String getName()
        {
            return name;
        }

        /**
         * Returns the data stream.
         *
         * @return the stream
         */
        public InputStream getStream()
        {
            return stream;
        }

        /**
         * Returns the size of the stream.
         *
         * @return the size of the stream
         */
        public long size()
        {
            return stream.size();
        }

        /**
         * Returns the current position in the underlying stream.
         *
         * @return the read position
         */
        public long getPosition()
        {
            return stream.getReadPosition();
        }

        /**
         * Returns the ID of this audio stream.
         *
         * @return the stream's ID
         */
        public Object getID()
        {
            return streamID;
        }
    }

    /**
     * A simple helper class used for storing data of streams that were added to
     * the buffer's chunks.
     */
    class ChunkDataPart
    {
        /** Stores the chained stream for that data part. */
        private ChainedInputStream stream;

        /** Stores the name of the stream. */
        private String name;

        /** Stores the stream's ID. */
        private Object id;

        /** Stores the number of bytes to read. */
        private long bytesToRead;

        /** Stores the total size of the stream. */
        private long size;

        /**
         * Creates a new instance of <code>ChunkDataPart</code>
         *
         * @param n the name of the stream
         * @param strId the ID of the stream
         */
        public ChunkDataPart(String n, Object strId, long streamSize)
        {
            name = n;
            id = strId;
            size = streamSize;
        }

        /**
         * Returns the name of this stream.
         *
         * @return the name
         */
        public String getName()
        {
            return name;
        }

        /**
         * Returns the ID of this stream.
         *
         * @return the stream's ID
         */
        public Object getID()
        {
            return id;
        }

        /**
         * Adds the given number of bytes to read.
         *
         * @param cnt the additional number of bytes to read
         */
        public void addBytes(long cnt)
        {
            bytesToRead += cnt;
        }

        /**
         * Checks if this part is empty. This is the case if no data has been
         * written yet.
         *
         * @return a flag if this part is empty
         */
        public boolean isEmpty()
        {
            return bytesToRead < 1;
        }

        /**
         * Adds the given child stream to the managed chained stream. If this is
         * the first child stream to be added, the chained stream will be
         * created and returned (it must then be added to the queue with the
         * available streams). Otherwise the return value is <b>null</b>.
         *
         * @param child the child stream to be added
         * @param callBack the call back
         * @param param the parameter for the call back
         * @return the chained stream if it was newly created
         */
        public ChainedInputStream getAppendStream(InputStream child,
                ChainedInputStreamCallBack callBack, File param)
        {
            ChainedInputStream result = null;

            if (stream == null)
            {
                stream = new ChainedInputStream(size);
                result = stream;
            }
            appendStream(stream, child, bytesToRead, callBack, param);
            bytesToRead = 0;
            return result;
        }

        /**
         * Marks the managed chained stream as complete.
         */
        public void complete()
        {
            assert stream != null : "Stream is null!";
            stream.complete();
        }
    }

    /**
     * An internally used thread for sending events to registered listeners.
     * This is done in a separate thread to avoid potential deadlocks when event
     * listeners query properties of the buffer that need synchronization.
     */
    class EventThread extends Thread
    {
        /**
         * Monitors the event queue and sends events to registered listeners.
         */
        @Override
        public void run()
        {
            boolean exit = false;
            while (!exit)
            {
                try
                {
                    AudioBufferEvent.Type type = events.take();
                    exit = type == AudioBufferEvent.Type.BUFFER_CLOSED;
                    AudioBufferEvent event = null;
                    Object[] lstnrs = listeners.getListenerList();
                    for (int i = lstnrs.length - 2; i >= 0; i -= 2)
                    {
                        if (lstnrs[i] == AudioBufferListener.class)
                        {
                            if (event == null)
                                event =
                                        new AudioBufferEvent(AudioBuffer.this,
                                                type);
                            ((AudioBufferListener) lstnrs[i + 1])
                                    .bufferChanged(event);
                        }
                    }
                }
                catch (InterruptedException iex)
                {
                    log.debug("Event sending thread was interrupted.");
                }
            }
        }
    }
}
