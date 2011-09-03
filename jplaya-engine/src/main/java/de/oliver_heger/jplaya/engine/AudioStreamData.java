package de.oliver_heger.jplaya.engine;

import java.io.IOException;
import java.io.InputStream;

/**
 * <p>
 * An interface that describes an audio stream.
 * </p>
 * <p>
 * An <em>audio stream</em> simply has a name and an associated data stream. In
 * addition to this basic properties a stream data object can provide some
 * further information about the current status of the underlying stream, e.g.
 * its length and its current read position.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface AudioStreamData
{
    /**
     * Returns the name of this audio stream.
     *
     * @return the name of this stream
     */
    String getName();

    /**
     * Returns an ID for the represented audio stream. During processing of a
     * stream this ID will remain constant. It should be unique. It will be used
     * to obtain further properties about this audio stream, e.g. from a
     * playlist manager.
     *
     * @return the ID of this audio stream
     */
    Object getID();

    /**
     * Returns the stream with the underlying data.
     *
     * @return the stream with the data
     * @throws IOException if an IO error occurs
     */
    InputStream getStream() throws IOException;

    /**
     * Returns the length of this stream.
     *
     * @return the length of this stream
     */
    long size();

    /**
     * Returns the current read position in the underlying stream if this
     * information is available. This data may be used to determine a percentual
     * position in the currently played audio stream. Implementations that
     * cannot obtain this information should return a negative value.
     *
     * @return the current position in the audio stream
     */
    long getPosition();

    /**
     * Returns the index of this stream in the current playlist.
     *
     * @return the index of this stream
     */
    int getIndex();
}
