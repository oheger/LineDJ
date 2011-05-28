package de.olix.playa.engine;

import java.io.IOException;
import java.io.InputStream;

/**
 * <p>
 * This is a simple implementation of the <code>AudioStreamData</code>
 * interface that can be used to indicate the end of a playlist.
 * </p>
 * <p>
 * As pointed out in the documentation of the
 * <code>{@link AudioStreamSource}</code> interface, the end of a playlist is
 * indicated by a special <code>{@link AudioStreamData}</code> object that
 * returns a stream size less than zero. Exactly this functionality is provided
 * by this class. The other methods defined by the interface are implemented as
 * dummies.
 * </p>
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class EndAudioStreamData implements AudioStreamData
{
    /** Constant for the name of this (dummy) audio stream. */
    public static final String END_STREAM_NAME = "End of playlist";
    
    /** Constant for the ID of this (dummy) audio stream.*/
    public static final Integer END_STREAM_ID = -1;
    
    /**
     * Constant for an instance of this class. Because this instance is immutable
     * it can be shared.
     */
    public static final EndAudioStreamData INSTANCE = new EndAudioStreamData();

    /**
     * Returns the name of the underlying stream. This implementation will
     * return the <code>END_STREAM_NAME</code> constant.
     * 
     * @return the name of the stream
     */
    public String getName()
    {
        return END_STREAM_NAME;
    }

    /**
     * Returns the current stream position. This implementation returns always
     * 0.
     * 
     * @return the stream position
     */
    public long getPosition()
    {
        return 0;
    }

    /**
     * Returns the underlying input stream. This implementation returns always
     * <b>null</b>
     * 
     * @return the stream
     * @throws IOException if an IO error occurs
     */
    public InputStream getStream() throws IOException
    {
        return null;
    }

    /**
     * Returns the size of this stream. To make this instance to an end of
     * playlist marker, a value less than 0 will be returned.
     * 
     * @return the size of the managed stream
     */
    public long size()
    {
        return -1;
    }

    /**
     * Returns the ID of this stream. This implementation will
     * return the <code>END_STREAM_ID</code> constant.
     * @return the ID of the managed stream
     */
    public Object getID()
    {
        return END_STREAM_ID;
    }
}
