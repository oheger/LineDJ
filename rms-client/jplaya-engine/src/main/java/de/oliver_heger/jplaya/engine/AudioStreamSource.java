package de.oliver_heger.jplaya.engine;

import java.io.IOException;

/**
 * <p>
 * Definition of an interface for obtaining audio streams.
 * </p>
 * <p>
 * This interface is used by the audio player to obtain the streams for the
 * songs it should play. It allows to query for streams one by one. After one
 * stream has been played, the next one can be fetched. If no audio stream is
 * available at the moment, an implementation will block until new data arrives.
 * </p>
 * <p>
 * If all audio streams of a specific playlist have been processed, a special
 * {@link AudioStreamData} object is returned as end mark: this object will
 * return a stream size that is less than 0. (An alternative would have been to
 * return a <b>null</b> reference, but for some implementations, especially
 * those that are based on blocking queues, this may not be easy possible.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface AudioStreamSource
{
    /**
     * Returns the next available audio stream. If none is available currently,
     * the method will block until data was set.
     *
     * @return an <code>AudioStreamData</code> object for the next audio stream
     * @throws InterruptedException if the operation was interrupted
     * @throws IOException if an IO error occurs
     */
    AudioStreamData nextAudioStream() throws InterruptedException, IOException;
}
