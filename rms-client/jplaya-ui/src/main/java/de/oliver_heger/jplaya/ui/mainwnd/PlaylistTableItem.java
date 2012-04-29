package de.oliver_heger.jplaya.ui.mainwnd;

import org.apache.commons.lang3.StringUtils;

import de.oliver_heger.splaya.AudioSourceData;
import de.oliver_heger.splaya.PlaylistData;

/**
 * <p>
 * A class representing the data to be displayed by an item in the table with
 * the playlist.
 * </p>
 * <p>
 * This class mainly acts as an adapter for a specific item of a
 * {@code PlaylistData} instance. It provides bean properties which can be
 * accessed by the <em>JGUIraffe</em> table implementation.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlaylistTableItem
{
    /** Constant for the number of milliseconds per second. */
    private static final long MILLIS = 1000;

    /** Constant for the number of seconds per hour. */
    private static final int SECS_PER_HOUR = 60 * 60;

    /** Constant for the seconds per minute. */
    private static final int SECS_PER_MINUTE = 60;

    /** Constant for the duration buffer size. */
    private static final int DURATION_BUF_SIZE = 16;

    /** Stores the playlist data object. */
    private final PlaylistData playlistData;

    /** Stores the index of the represented playlist item. */
    private final int index;

    /**
     * Creates a new instance of {@code PlaylistTableItem} and initializes it
     * with the required data.
     *
     * @param data the current {@code PlaylistData} instance
     * @param itemIndex the index of the represented item
     */
    public PlaylistTableItem(PlaylistData data, int itemIndex)
    {
        playlistData = data;
        index = itemIndex;
    }

    /**
     * Returns the {@code PlaylistData} object this instance operates on.
     *
     * @return the wrapped {@code PlaylistData} instance
     */
    public PlaylistData getPlaylistData()
    {
        return playlistData;
    }

    /**
     * Returns the index of the represented playlist item.
     *
     * @return the index of the playlist item
     */
    public int getIndex()
    {
        return index;
    }

    /**
     * Returns the name of the song represented by this playlist item.
     *
     * @return the song name
     */
    public String getSongName()
    {
        return fetchSourceData().title();
    }

    /**
     * Returns the 1-based index of this playlist item.
     *
     * @return the index of this playlist item to be displayed to the end user
     */
    public int getListIndex()
    {
        return getIndex() + 1;
    }

    /**
     * Returns the duration of this audio source as a formatted string.
     *
     * @return the formatted duration
     */
    public String getDuration()
    {
        return formatDuration(fetchSourceData().duration());
    }

    /**
     * Returns the name of the album which contains the represented song.
     *
     * @return the album name
     */
    public String getAlbum()
    {
        return fetchProperty(fetchSourceData().albumName());
    }

    /**
     * Returns the name of the artist who performed the represented song.
     *
     * @return the artist name
     */
    public String getArtist()
    {
        return fetchProperty(fetchSourceData().artistName());
    }

    /**
     * Returns the track number of the represented song or an empty string if it
     * is undefined.
     *
     * @return the track number
     */
    public String getTrackNo()
    {
        int trackNo = fetchSourceData().trackNo();
        return (trackNo > 0) ? String.valueOf(trackNo) : StringUtils.EMPTY;
    }

    /**
     * Obtains the {@code AudioSourceData} object for this playlist item.
     *
     * @return the underlying {@code AudioSourceData} object
     */
    private AudioSourceData fetchSourceData()
    {
        return getPlaylistData().getAudioSourceData(getIndex());
    }

    /**
     * Returns a string representation for the specified duration.
     *
     * @param duration the duration (in milliseconds)
     * @return a string for this duration
     */
    private static String formatDuration(long duration)
    {
        StringBuilder buf = new StringBuilder(DURATION_BUF_SIZE);
        long secs = Math.round(duration / (double) MILLIS);
        long hours = secs / SECS_PER_HOUR;
        if (hours > 0)
        {
            buf.append(hours).append(':');
        }
        long mins = (secs % SECS_PER_HOUR) / SECS_PER_MINUTE;
        if (mins < 10 && hours > 0)
        {
            buf.append('0');
        }
        buf.append(mins).append(':');
        secs = secs % SECS_PER_MINUTE;
        if (secs < 10)
        {
            buf.append('0');
        }
        buf.append(secs);
        return buf.toString();
    }

    /**
     * Obtains the value of a property. If it has a value, it is converted to a
     * string. Otherwise the undefined value is returned.
     *
     * @param value the value
     * @return the value as string
     */
    private static String fetchProperty(Object value)
    {
        return (value != null) ? value.toString() : StringUtils.EMPTY;
    }
}
