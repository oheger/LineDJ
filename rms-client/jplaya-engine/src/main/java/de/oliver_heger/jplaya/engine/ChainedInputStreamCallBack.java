package de.oliver_heger.jplaya.engine;

import java.io.InputStream;

/**
 * <p>
 * Definition of an interface for call backs invoked by a
 * <code>{@link ChainedInputStream}</code>.
 * </p>
 * <p>
 * Whenever a child stream is passed to a <code>ChainedInputStream</code> an
 * implementation of this listener interface can be provided. When then the
 * stream is processed the defined methods are invoked at the correct points of
 * time.
 * </p>
 * <p>
 * The purpose of this interface is to make buffering easier: A component that
 * feeds this stream with child streams can use this mechanism to find out when
 * which child streams have been read, so that further data can be obtained.
 * </p>
 * 
 * @author Oliver Heger
 * @version $Id$
 * @see ChainedInputStream
 */
public interface ChainedInputStreamCallBack
{
    /**
     * Notification method that the end of a stream was reached. This method is
     * invoked when the stream was marked. So it is possible that a reset()
     * occurs and the stream will be read again.
     * 
     * @param stream the stream
     * @param position the current read position (i.e. the number of bytes read
     * so far)
     * @param param a parameter that was passed when the call back listener was
     * registered
     */
    void streamRead(InputStream stream, long position, Object param);

    /**
     * Notification method that a stream was completely read. This method will
     * be invoked when the end of a stream was reached and no mark mode is
     * active. So the stream can now be considered finished.
     * 
     * @param stream the stream
     * @param position the current read position (i.e. the number of bytes read
     * so far)
     * @param param a parameter that was passed when the call back listener was
     * registered
     */
    void streamCompleted(InputStream stream, long position, Object param);
}
