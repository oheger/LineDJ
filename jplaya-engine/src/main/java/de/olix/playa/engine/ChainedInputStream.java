package de.olix.playa.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * <p>
 * A specialized input stream implementation that allows access to an arbitrary
 * number of child streams, whose date is sequentially read.
 * </p>
 * <p>
 * With this stream it is possible to combine multiple input streams to a single
 * one, which is useful when a large file is split into multiple parts. Child
 * streams can be added using the <code>addStream()</code> method. For each
 * stream the number of bytes to be read can be specified. So it is possible
 * that streams are only partly read.
 * </p>
 * <p>
 * The implementation is thread-safe, i.e. it is possible to add new child
 * streams while data is read (however read operations on the stream are only
 * allowed for a single thread). When a child stream is completely processed,
 * optionally a call back method can be invoked, notifying an interested
 * listener about the progress. The current number of read bytes is also
 * calculated.
 * </p>
 * <p>
 * The stream also supports the <code>mark()</code> operation if the contained
 * streams also support this feature.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class ChainedInputStream extends InputStream
{
    /** Constant for the mark position NO MARK. */
    private static final long NO_MARK = -1;

    /** Stores the child streams to read. */
    private BlockingQueue<ChildStreamData> childStreams;

    /** A list with streams that were read when a mark is set. */
    private List<ChildStreamData> markStreams;

    /** Stores data for the current child stream. */
    private ChildStreamData currentStreamData;

    /** Stores the current read position. */
    private long readPosition;

    /** Stores the mark position. */
    private long markPosition;

    /**
     * Stores an offset for the read position. This value is added to the
     * current read position.
     */
    private long readOffset;

    /** Stores the total size of this stream. */
    private final long totalSize;

    /** Stores the size of this stream. */
    private long size;

    /** Stores the read limit for mark operations. */
    private int markReadLimit;

    /** A flag whether the read limit passed to mark() should be ignored. */
    private boolean ignoreMarkLimit;

    /**
     * Creates a new instance of <code>ChainedInputStream</code>.
     */
    public ChainedInputStream()
    {
        this(0);
    }

    /**
     * Creates a new instance of <code>ChainedInputStream</code> and sets the
     * size of this stream. This constructor can be used if the total size of
     * the stream is known ahead.
     *
     * @param streamSize the total size of this stream
     */
    public ChainedInputStream(long streamSize)
    {
        childStreams = new LinkedBlockingQueue<ChildStreamData>();
        markStreams = new LinkedList<ChildStreamData>();
        markPosition = NO_MARK;
        totalSize = streamSize;
    }

    /**
     * Returns a flag whether the read limit passed to the {@link #mark(int)}
     * method should be ignored.
     *
     * @return a flag whether the read limit for {@code mark()} should be
     *         ignored
     */
    public boolean isIgnoreMarkLimit()
    {
        return ignoreMarkLimit;
    }

    /**
     * Sets a flag whether the read limit passed to the {@link #mark(int)}
     * method should be ignored. Per default, this stream monitors the bytes
     * read. If the number of bytes read exceeds the limit passed to
     * {@code mark()}, all data stored for the mark operation is discarded. It
     * has shown that this can be problematic for some audio files. Therefore
     * this flag was introduced to disable this mechanism. If set to
     * <b>true</b>, a {@link #reset()} is always possible after a
     * {@link #mark(int)} call.
     *
     * @param ignoreMarkLimit a flag whether the mark limit should be ignored
     * @see #mark(int)
     */
    public void setIgnoreMarkLimit(boolean ignoreMarkLimit)
    {
        this.ignoreMarkLimit = ignoreMarkLimit;
    }

    /**
     * Adds a new child stream to this chained stream. The new stream will be
     * added at the end of the list of child streams, i.e. it will be read after
     * all existing child streams have been processed. The given number of bytes
     * will be read from this stream (it is assumed that the stream contains at
     * least as much data). If the call back interface is defined, it will be
     * invoked when the time comes.
     *
     * @param stream the stream to be added
     * @param bytesToRead the number of bytes to be read from this stream
     * @param callBack the optional call back interface
     * @param param an arbitrary object that will be passed again to the call
     * back method when it is invoked
     */
    public void addStream(InputStream stream, long bytesToRead,
            ChainedInputStreamCallBack callBack, Object param)
    {
        if (stream == null)
        {
            throw new IllegalArgumentException("Child stream must not be null!");
        }
        if (bytesToRead < 0)
        {
            throw new IllegalArgumentException("Invalid bytes to read: "
                    + bytesToRead);
        }

        try
        {
            childStreams.put(new ChildStreamData(stream, bytesToRead, callBack,
                    param));
            synchronized (this)
            {
                size += bytesToRead;
            }
        }
        catch (InterruptedException iex)
        {
            // should not happen
        }
    }

    /**
     * Adds a new child stream to this chained stream. Works like the method
     * with the same name, but does not register a call back.
     *
     * @param stream the stream to be added
     * @param bytesToRead the number of bytes to be read from this stream
     */
    public void addStream(InputStream stream, long bytesToRead)
    {
        addStream(stream, bytesToRead, null, null);
    }

    /**
     * Tells this chained stream that no more child streams will be added. When
     * the last added child stream is processed the stream will indicate the
     * caller that no more data is available. If this method has not been
     * invoked yet, the chained stream will expect that further data will be
     * added. In this case read operations on this stream will block until more
     * data is added.
     */
    public void complete()
    {
        childStreams.offer(new ChildStreamData());
    }

    /**
     * Returns the current read position.
     *
     * @return the current read position
     */
    public synchronized long getReadPosition()
    {
        return readPosition + getReadOffset();
    }

    /**
     * Returns the read offset.
     *
     * @return the read offset
     */
    long getReadOffset()
    {
        return readOffset;
    }

    /**
     * Allows to specify a read offset. This is a numeric value that is added to
     * the current read position. It is used internally for implementing the
     * mark functionality.
     */
    void setReadOffset(long ofs)
    {
        readOffset = ofs;
    }

    @Override
    /**
     * Marks the current position of this stream. A later call to
     * <code>reset()</code> will restore it, so that the same bytes will be
     * read again.
     *
     * @param readLimit the number of bytes that can be read in mark mode
     */
    public void mark(int readLimit)
    {
        if (isMarked())
        {
            unmark();
        }
        markPosition = getReadPosition();
        markReadLimit = readLimit;
        if (currentStreamData != null)
        {
            currentStreamData.mark(readLimit);
        }
    }

    @Override
    /**
     * Tests whether the mark operation is supported by this stream. This is the
     * case here.
     *
     * @return a flag if the mark operation is supported
     */
    public boolean markSupported()
    {
        return true;
    }

    @Override
    /**
     * Reads a number of bytes from this stream. If this operation is
     * interrupted, the number of so far read bytes will be returned.
     *
     * @param buf the target buffer
     * @param ofs the offset in the target buffer
     * @param len the number of bytes to read
     * @return the number of bytes actually read
     * @throws IOException if an error occurs
     */
    public int read(byte[] buf, int ofs, int len) throws IOException
    {
        int readCount = 0;
        int actOfs = ofs;

        try
        {
            if (endOfStream())
            {
                return -1;
            }

            while (!endOfStream() && readCount < len)
            {
                int actLen = (int) Math.min(len - readCount, currentStreamData
                        .getRemainingBytes());
                if (actLen > 0)
                {
                    actLen = currentStreamData.read(buf, actOfs, actLen);
                    if (actLen != -1)
                    {
                        actOfs += actLen;
                        readCount += actLen;
                        synchronized (this)
                        {
                            readPosition += actLen;
                        }
                    }
                }
                else
                {
                    actLen = -1;
                }

                if (actLen < 0)
                {
                    nextStream();
                }
                if (exceedReadLimit())
                {
                    unmark();
                }
            }
        }
        catch (InterruptedException iex)
        {
            // exit the read loop and return so far read bytes
        }

        return readCount;
    }

    @Override
    /**
     * Reads a number of bytes from this stream. It is tried to fill the
     * complete buffer.
     *
     * @param buf the target buffer
     * @return the number of bytes read
     * @throws IOException if an error occurs
     */
    public int read(byte[] buf) throws IOException
    {
        return read(buf, 0, buf.length);
    }

    @Override
    /**
     * Resets the stream's current position to the recent marked position.
     *
     * @throws IOException if a reset is not possible
     */
    public void reset() throws IOException
    {
        if (!isMarked())
        {
            throw new IOException("reset() call without mark()!");
        }

        if (markStreams.isEmpty())
        {
            currentStreamData.reset();
        }
        else
        {
            markStreams.add(currentStreamData);
            ChainedInputStream cis = new ChainedInputStream();
            cis.setReadOffset(markPosition);
            long bytesToRead = 0;
            for (ChildStreamData csd : markStreams)
            {
                csd.reset();
                bytesToRead += csd.getRemainingBytes();
                csd.addToChainedStream(cis);
            }
            currentStreamData = new ChildStreamData(cis, bytesToRead, null,
                    null);
        }

        readPosition = markPosition;
        markPosition = NO_MARK;
        markStreams.clear();
    }

    @Override
    /**
     * Reads a single byte from this stream. Note that this method is not very
     * efficient. It is by far more performant to use one of the other
     * <code>read()</code> methods.
     *
     * @return the read byte or -1 if the end of this stream is reached
     * @throws IOException in case of an error
     */
    public int read() throws IOException
    {
        byte[] buf = new byte[1];
        int read = read(buf);
        return (read != 1) ? -1 : buf[0];
    }

    /**
     * Returns a flag whether this stream's end is reached.
     *
     * @return the end of stream flag
     * @throws InterruptedException if the operation was interrupted
     */
    public boolean endOfStream() throws InterruptedException
    {
        if (currentStreamData == null)
        {
            initCurrentStreamDataFromQueue();
        }
        return currentStreamData.getStream() == null;
    }

    /**
     * Returns a flag whether this stream has been marked. This means that the
     * <code>mark()</code> method was invoked.
     *
     * @return a flag if this stream has been marked
     */
    public boolean isMarked()
    {
        return markPosition != NO_MARK;
    }

    /**
     * Tests if this chained stream is empty. Empty means that for this stream
     * only <code>complete()</code> was called without adding a child stream.
     *
     * @return a flag if this stream is empty
     */
    public boolean isEmpty()
    {
        if (getReadPosition() > 0)
        {
            return false;
        }
        else
        {
            ChildStreamData data = childStreams.peek();
            return data != null && data.getStream() == null;
        }
    }

    /**
     * Returns the size of this stream. If the stream size was specified when
     * this object was created, this size will be returned. Otherwise the size
     * is determined as the total number of bytes to be read from the child
     * streams that have been added so far. Note that in contrast to the
     * <code>{@link #isEmpty()}</code> method the return value of this method
     * does not depend on <code>{@link #complete()}</code> being called.
     *
     * @return the size of this stream
     */
    public long size()
    {
        if (totalSize > 0)
        {
            return totalSize;
        }
        synchronized (this)
        {
            return size;
        }
    }

    /**
     * Proceeds to the next child stream in the list. This may block if there is
     * none. If necessary, call back methods will be invoked for the finished
     * stream.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    private void nextStream() throws InterruptedException
    {
        if (isMarked())
        {
            markStreams.add(currentStreamData);
        }
        currentStreamData.invokeCallBackRead(getReadPosition(), !isMarked());
        initCurrentStreamDataFromQueue();
    }

    /**
     * Fetches the next child stream data object from the queue with the child
     * streams and initializes it.
     *
     * @throws InterruptedException if waiting at the queue is interrupted
     */
    private void initCurrentStreamDataFromQueue() throws InterruptedException
    {
        currentStreamData = childStreams.take();
        if (isMarked())
        {
            int readLimit =
                    isIgnoreMarkLimit() ? Integer.MAX_VALUE
                            : (int) (markReadLimit - (getReadPosition() - markPosition));
            currentStreamData.mark(readLimit);
        }
    }

    /**
     * This method is called when a mark() operation becomes invalid, either
     * because too many bytes have been read or because of another call to
     * <code>mark()</code>. In this case all streams in the mark list must be
     * freed.
     */
    private void unmark()
    {
        for (ChildStreamData csd : markStreams)
        {
            csd.invokeCompleteCallBack();
        }
        markStreams.clear();
        markPosition = NO_MARK;
    }

    /**
     * Checks the read limit for the current mark operation. If more bytes have
     * been read that were specified as argument in the <code>mark()</code>
     * call, the mark can be invalidated.
     *
     * @return a flag whether the read limit was exceeded
     */
    private boolean exceedReadLimit()
    {
        return isMarked() && !isIgnoreMarkLimit()
                && (getReadPosition() - markPosition > markReadLimit);
    }

    /**
     * A simple helper class for storing data about a child stream.
     */
    static class ChildStreamData
    {
        /** Stores the child stream. This is <b>null</b> for the end mark. */
        private InputStream stream;

        /** Stores a reference to the call back object if defined. */
        private ChainedInputStreamCallBack callBack;

        /** Stores the parameter for the call back. */
        private Object callBackParam;

        /** Stores the number of bytes to read from this stream. */
        private long bytesToRead;

        /** Stores the bytes that have already been read. */
        private long bytesRead;

        /** Stores the mark position. */
        private long markPosition;

        /** Stores the end position of this child stream. */
        private long endPosition;

        /**
         * Creates a new instance of <code>ChildStreamData</code> and
         * initializes it.
         *
         * @param in the child stream
         * @param count the number of bytes to read from this stream
         * @param cb the call back object (can be <b>null</b>)
         * @param param the parameter to be passed to the call back
         */
        public ChildStreamData(InputStream in, long count,
                ChainedInputStreamCallBack cb, Object param)
        {
            stream = in;
            bytesToRead = count;
            callBack = cb;
            callBackParam = param;
        }

        /**
         * Creates a new <code>ChildStreamData</code> instance that is used as
         * the chained stream's end mark.
         */
        public ChildStreamData()
        {
            this(null, 0, null, null);
        }

        /**
         * Returns the child stream.
         *
         * @return the stream
         */
        public InputStream getStream()
        {
            return stream;
        }

        /**
         * Reads data from the managed stream.
         *
         * @param buf the target buffer
         * @param pos the start index in the buffer
         * @param len the number of bytes to read
         * @return the number of bytes read
         * @throws IOException if an error occurs
         */
        public int read(byte[] buf, int pos, int len) throws IOException
        {
            int read = getStream().read(buf, pos, len);
            bytesRead += read;
            return read;
        }

        /**
         * Returns the number of remaining bytes that must be read from this
         * child stream.
         *
         * @return the number of remaining bytes to read
         */
        public long getRemainingBytes()
        {
            return bytesToRead - bytesRead;
        }

        /**
         * Marks the current position in this stream.
         *
         * @param readLimit the limit for the mark operation
         */
        public void mark(int readLimit)
        {
            if (getStream() != null)
            {
                markPosition = bytesRead;
                getStream()
                        .mark((int) Math.min(readLimit, getRemainingBytes()));
            }
        }

        /**
         * Resets the child stream to the last mark position.
         *
         * @throws IOException if an error occurs
         */
        public void reset() throws IOException
        {
            getStream().reset();
            bytesRead = markPosition;
        }

        /**
         * Invokes the call back object if it is defined.
         *
         * @param position the current position (in the owning chained stream)
         * @param complete the complete flag (determines, which call back method
         * is to be invoked)
         */
        public void invokeCallBackRead(long position, boolean complete)
        {
            if (callBack != null)
            {
                if (complete)
                {
                    callBack.streamCompleted(getStream(), position,
                            callBackParam);
                }
                else
                {
                    callBack.streamRead(getStream(), position, callBackParam);
                }
                endPosition = position;
            }
        }

        /**
         * Invokes the call back when the child stream has been completed. This
         * method is only called if a mark() operation is involved. In this case
         * <code>invokeCallBackRead()</code> has already been called. The
         * position specified for that call will be used here again.
         */
        public void invokeCompleteCallBack()
        {
            if (callBack != null)
            {
                callBack.streamCompleted(getStream(), endPosition,
                        callBackParam);
            }
        }

        /**
         * Adds the represented stream to a chained input stream.
         *
         * @param cis the chained input stream
         */
        public void addToChainedStream(ChainedInputStream cis)
        {
            cis.addStream(getStream(), getRemainingBytes(), callBack,
                    callBackParam);
        }
    }
}
