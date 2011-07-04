package de.oliver_heger.jplaya.ui.mainwnd;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * An internally used helper class for synchronizing events updating song data
 * information.
 * </p>
 * <p>
 * There are multiple types of events requiring access to song data objects:
 * <ul>
 * <li>The {@code SongDataManager} sends events when media information about a
 * song has been extracted.</li>
 * <li>When a song has been played the corresponding data object has to be
 * retrieved.</li>
 * </ul>
 * These events can arrive in any order, e.g. a song may have already been
 * played (even multiple times!) before its media information has been
 * retrieved. So it is a bit tricky to manage song data objects and update the
 * local database at the right time.
 * </p>
 * <p>
 * This class addresses this problem. It keeps track about the songs whose media
 * information has already been fetched and about the played songs as well.
 * Internal data structures are synchronized correspondingly. Usage scenario is
 * that every time an event is received the corresponding method is called. It
 * updates the internal fields managed by this instance. If sufficient
 * information for updating the database is available, this is indicated by the
 * return value. The caller can then query the corresponding {@code SongData}
 * object from the manager and pass it to the database.
 * </p>
 * <p>
 * Implementation note: This class is thread-safe.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class SongDataSynchronizer
{
    /** Constant for the number one, the initial play count. */
    private static final Integer ONE = Integer.valueOf(1);

    /**
     * A set with the URIs of the songs for which song data objects are
     * available.
     */
    private final Set<String> availableSongData;

    /** A map with information about songs that have been played. */
    private final Map<String, Integer> playedSongs;

    /**
     * Creates a new instance of {@code SongDataSynchronizer}.
     */
    public SongDataSynchronizer()
    {
        availableSongData = new HashSet<String>();
        playedSongs = new HashMap<String, Integer>();
    }

    /**
     * An event indicating that a song has been played was received. Internal
     * data structures are updated correspondingly. If the return value is not
     * 0, a corresponding {@code SongData} object is available; the return value
     * is the play count.
     *
     * @param uri the URI of the song in question
     * @return the number of times the song has been played
     */
    public synchronized int songPlayedEventReceived(String uri)
    {
        if (availableSongData.contains(uri))
        {
            return 1;
        }

        Integer count = playedSongs.get(uri);
        Integer newCount =
                (count != null) ? Integer.valueOf(count.intValue() + 1) : ONE;
        playedSongs.put(uri, newCount);
        return 0;
    }

    /**
     * An event indicating that media information has been retrieved was
     * received. Internal data structures are updated correspondingly. If the
     * return value is not 0, a corresponding {@code SongData} object is
     * available; the return value is the play count.
     *
     * @param uri the URI of the song in question
     * @return the number of times the song has been played
     */
    public synchronized int songDataEventReceived(String uri)
    {
        availableSongData.add(uri);

        Integer count = playedSongs.remove(uri);
        return (count != null) ? count.intValue() : 0;
    }
}
