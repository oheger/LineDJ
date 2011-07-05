package de.olix.playa.engine;

import java.io.IOException;

/**
 * <p>
 * Definition of an interface for storing data in a buffer.
 * </p>
 * <p>
 * This interface allows to put chunks of data into a buffer. The buffer may
 * block if it is full. It is used by the MP3 player to cache the audio data -
 * that is probably read from a CD ROM - on the hard disk.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface DataBuffer
{
    /**
     * Tells the buffer that now data for a new element (e.g. a song file)
     * starts. The given data object contains all required information about the
     * new element.
     *
     * @param data a data object describing the data source
     */
    void addNewStream(AudioStreamData data);

    /**
     * Adds a new chunk of data for the current stream to this buffer. This
     * method can be called after {@code addNewStream()} for an arbitrary number
     * of times. It specifies the data of the stream. If the buffer is full,
     * this operation may block.
     *
     * @param data the data buffer
     * @param ofs the offset into this buffer
     * @param len the number of bytes
     * @throws IOException if an IO error occurs
     * @throws InterruptedException if the thread is interrupted
     */
    void addChunk(byte[] data, int ofs, int len) throws IOException,
            InterruptedException;

    /**
     * Tells the buffer that the data of the current stream has been completely
     * written. With this call a stream is closed. So this method must be
     * invoked for each stream that was added using {@code addNewStream()}.
     */
    void streamFinished();

    /**
     * Returns a flag whether this buffer has been closed. In this case,
     * incoming audio data will simply be ignored.
     *
     * @return a flag whether this buffer has been closed
     */
    boolean isClosed();

    /**
     * Tests whether the buffer is currently full.
     *
     * @return a flag whether the buffer is full
     */
    boolean isFull();

    /**
     * Closes this buffer. This means that no more data will be added.
     *
     * @throws IOException if an IO error occurs
     */
    void close() throws IOException;

    /**
     * Performs a shutdown of this buffer. This involves a {@code close()}
     * operation and frees all resources used by this instance. After this the
     * buffer cannot be used any longer.
     */
    void shutdown();

    /**
     * Removes the specified listener from this buffer.
     *
     * @param l the listener to remove
     */
    void removeBufferListener(DataBufferListener l);

    /**
     * Adds a new data buffer listener to this object. This listener will
     * receive notifications about changes in the state of this buffer.
     *
     * @param l the listener to register
     */
    void addBufferListener(DataBufferListener l);
}
