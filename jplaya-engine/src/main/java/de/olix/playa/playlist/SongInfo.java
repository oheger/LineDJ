package de.olix.playa.playlist;

import java.util.Map;

/**
 * <p>
 * Definition of an interface for obtaining further information about a song to
 * be played.
 * </p>
 * <p>
 * Through this interface an audio player main application can query information
 * about a song to be played and display this data to the user. The interface is
 * quite simple. There are some methods for directly querying the most important
 * properties like title and interprete. The remaining properties are returned
 * via a map.
 * </p>
 * <p>
 * The number of available properties seems not to be standardized. So it is not
 * clear, which information concrete service providers deliver. If a certain
 * information is not available, the corresponding method will just return
 * <b>null</b>.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public interface SongInfo
{
    /**
     * Returns the title of the song.
     *
     * @return the songs's title
     */
    String getTitle();

    /**
     * Returns the interpret of the song.
     *
     * @return the interpret
     */
    String getInterpret();

    /**
     * Returns the playback duration of this song in milli seconds.
     *
     * @return the playback duration in ms
     */
    Long getDuration();

    /**
     * Returns a map with further properties. This map should contain only data
     * that cannot be obtained using the other methods of this interface.
     *
     * @return a map with further properties of the represented song
     */
    Map<String, Object> properties();
}
