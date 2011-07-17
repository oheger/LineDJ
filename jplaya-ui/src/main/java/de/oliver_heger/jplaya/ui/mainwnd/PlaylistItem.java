package de.oliver_heger.jplaya.ui.mainwnd;

import org.apache.commons.lang3.StringUtils;

import de.oliver_heger.mediastore.service.SongData;
import de.olix.playa.playlist.PlaylistInfo;

/**
 * <p>
 * A data class representing a song in the playlist.
 * </p>
 * <p>
 * This class is used by the UI model for the playlist. An instance contains all
 * information which has to be displayed in the UI for a single song. The major
 * part of this information is obtained from a {@link SongData} object
 * associated with the instance. The idea is that the class defines properties
 * which can directly be bound to UI elements. These properties are already
 * formatted strings.
 * </p>
 * <p>
 * Implementation note: This class is not thread-safe. It is expected that
 * instances are exclusively accessed in the EDT. The class is only used
 * internally; therefore no sophisticated parameter checks are implemented.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlaylistItem
{
    /** Constant for the string to be returned for an undefined property. */
    private static final String UNDEF = StringUtils.EMPTY;

    /** Constant for the format pattern for the playback time. */
    private static final String FMT_PLAYBACK = "%s / %s";

    /** Constant for the format pattern for the index in the playlist. */
    private static final String FMT_PLINDEX = "%d / %d";

    /** Constant for the number of milliseconds per second. */
    private static final long MILLIS = 1000;

    /** Constant for the number of seconds per hour. */
    private static final int SECS_PER_HOUR = 60 * 60;

    /** Constant for the seconds per minute. */
    private static final int SECS_PER_MINUTE = 60;

    /** Constant for the duration buffer size. */
    private static final int DURATION_BUF_SIZE = 16;

    /** Constant of the path separator for URIs. */
    private static final char PATH_SEPARATOR = '/';

    /** Constant for the file name extension separator. */
    private static final char EXT_SEPARATOR = '.';

    /** The underlying {@code SongData} object. */
    private SongData songData;

    /** The current playlist context. */
    private PlaylistContext playlistContext;

    /** The URI of this song. */
    private String uri;

    /** The name extracted from the URI. */
    private String name = UNDEF;

    /** The index of this song in the playlist. */
    private int index = -1;

    /**
     * Returns the underlying {@code SongData} object.
     *
     * @return the object with media information about the associated song
     */
    public SongData getSongData()
    {
        return songData;
    }

    /**
     * Sets the underlying {@code SongData} object. Many properties provided to
     * the UI are derived from the data stored in this object. There should
     * always be an associated {@code SongData} object, otherwise
     * {@code NullPointerException} exceptions will occur.
     *
     * @param songData the associated {@code SongData} object
     */
    public void setSongData(SongData songData)
    {
        this.songData = songData;
    }

    /**
     * Returns the object with current context information about the playlist.
     *
     * @return the playlist context object
     */
    public PlaylistContext getPlaylistContext()
    {
        return playlistContext;
    }

    /**
     * Sets the object with current context information about the playlist.
     *
     * @param playlistContext the context object
     */
    public void setPlaylistContext(PlaylistContext playlistContext)
    {
        this.playlistContext = playlistContext;
    }

    /**
     * Returns the URI of the represented song.
     *
     * @return the URI
     */
    public String getUri()
    {
        return uri;
    }

    /**
     * Sets the URI of the represented song. If no media information is
     * available, the URI is used to produce at least a song name.
     *
     * @param uri the URI of this song
     */
    public void setUri(String uri)
    {
        this.uri = uri;

        if (uri == null)
        {
            name = UNDEF;
        }
        else
        {
            int pos = uri.lastIndexOf(PATH_SEPARATOR);
            if (pos < 0 || pos >= uri.length() - 1)
            {
                name = uri;
            }
            else
            {
                name = uri.substring(pos + 1);
                pos = name.lastIndexOf(EXT_SEPARATOR);
                if (pos > 0)
                {
                    name = name.substring(0, pos);
                }
            }
        }
    }

    /**
     * Returns the index of this song in the playlist.
     *
     * @return the index
     */
    public int getIndex()
    {
        return index;
    }

    /**
     * Sets the index of this song in the playlist.
     *
     * @param index the index
     */
    public void setIndex(int index)
    {
        this.index = index;
    }

    /**
     * Returns the name of the represented song.
     *
     * @return the name of this song
     */
    public String getSongName()
    {
        String songName = getSongData().getName();
        return (songName != null) ? songName : name;
    }

    /**
     * Returns the name of the artist of the represented song.
     *
     * @return the name of the artist
     */
    public String getArtist()
    {
        return fetchProperty(getSongData().getArtistName());
    }

    /**
     * Returns the name of the album the represented song belongs to.
     *
     * @return the name of the album
     */
    public String getAlbum()
    {
        return fetchProperty(getSongData().getAlbumName());
    }

    /**
     * Returns the elapsed time of the song in relation to the total duration.
     * This looks something like {@code 2:58 / 4:30}.
     *
     * @return the playback time
     */
    public String getPlaybackTime()
    {
        String playback =
                formatDuration(getPlaylistContext().getPlaybackTime());
        String duration = getDuration();
        return (duration != UNDEF) ? String.format(FMT_PLAYBACK, playback,
                duration) : playback;
    }

    /**
     * Returns the formatted duration of the represented song.
     *
     * @return the duration
     */
    public String getDuration()
    {
        Number duration = getSongData().getDuration();
        if (duration == null)
        {
            return UNDEF;
        }
        return formatDuration(duration.longValue() * MILLIS);
    }

    /**
     * Returns the track number of this song in the album it belongs to.
     *
     * @return the track number
     */
    public String getTrackNo()
    {
        return fetchProperty(getSongData().getTrackNo());
    }

    /**
     * Returns the playback ratio in percent. The value lies between 0 and 100.
     *
     * @return the playback ratio
     */
    public int getPlaybackRatio()
    {
        return getPlaylistContext().getPlaybackRatio();
    }

    /**
     * Returns the index of this song in relation to the size of the playlist.
     * This is something like {@code 10 / 100}. Note: While the index is
     * 0-based, the display starts with 1.
     *
     * @return the playlist index
     */
    public String getPlaybackIndex()
    {
        int idx = getIndex() + 1;
        return (idx > 0) ? String.format(FMT_PLINDEX, idx, getPlaylistContext()
                .getPlaylistInfo().getNumberOfSongs()) : UNDEF;
    }

    /**
     * Returns the name of the playlist.
     *
     * @return the name of the playlist
     */
    public String getPlaylistName()
    {
        PlaylistInfo info = getPlaylistContext().getPlaylistInfo();
        return (info != null) ? info.getName() : UNDEF;
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
        return (value != null) ? value.toString() : UNDEF;
    }
}
