package de.oliver_heger.jplaya.ui.mainwnd;

import org.apache.commons.lang3.StringUtils;

import de.oliver_heger.splaya.PlaylistData;

/**
 * <p>
 * A class representing a song in the playlist with all detail information to
 * populate the form for the current song.
 * </p>
 * <p>
 * This class is used as the UI model for the major part of the audio player
 * application. An instance contains all information which has to be displayed
 * in the UI for a single song. The idea is that the class defines properties
 * which can directly be bound to UI elements. These properties are already
 * formatted strings.
 * </p>
 * <p>
 * A part of the information provided by this class stems from the base class
 * which is backed by an {@code AudioSourceData} object. Other parts have to be
 * set explicitly through properties.
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
public class PlaylistItem extends PlaylistTableItem
{
    /** Constant for the string to be returned for an undefined property. */
    private static final String UNDEF = StringUtils.EMPTY;

    /** Constant for the format pattern for the playback time. */
    private static final String FMT_PLAYBACK = "%s / %s";

    /** Constant for the format pattern for the index in the playlist. */
    private static final String FMT_PLINDEX = "%d / %d";

    /**
     * Constant for the format pattern for the album and track number
     * combination.
     */
    private static final String FMT_ALBUM_TRACK = "%s (%s)";

    /** The current playback time. */
    private long currentPlaybackTime;

    /** The playback ratio. */
    private int playbackRatio;

    /**
     * Creates a new instance of {@code PlaylistItem} and initializes it.
     *
     * @param data the data object for the whole playlist
     * @param itemIndex the index of the represented playlist item
     */
    public PlaylistItem(PlaylistData data, int itemIndex)
    {
        super(data, itemIndex);
    }

    /**
     * Returns the current playback time in milliseconds.
     *
     * @return the current playback time
     */
    public long getCurrentPlaybackTime()
    {
        return currentPlaybackTime;
    }

    /**
     * Sets the current playback time in milliseconds.
     *
     * @param currentPlaybackTime the current playback time
     */
    public void setCurrentPlaybackTime(long currentPlaybackTime)
    {
        this.currentPlaybackTime = currentPlaybackTime;
    }

    /**
     * Returns the playback ratio in percent. The value lies between 0 and 100.
     *
     * @return the playback ratio
     */
    public int getPlaybackRatio()
    {
        return playbackRatio;
    }

    /**
     * Sets the current playback ratio in percent.
     *
     * @param playbackRatio the current playback ratio
     */
    public void setPlaybackRatio(int playbackRatio)
    {
        this.playbackRatio = playbackRatio;
    }

    /**
     * Returns the elapsed time of the song in relation to the total duration.
     * This looks something like {@code 2:58 / 4:30}.
     *
     * @return the playback time
     */
    public String getPlaybackTime()
    {
        String playback = formatDuration(getCurrentPlaybackTime());
        String duration = getDuration();
        return (duration.length() > 0) ? String.format(FMT_PLAYBACK, playback,
                duration) : playback;
    }

    /**
     * Returns the name of the album and the track number. This information is
     * displayed in combination. The combination is only constructed if both
     * values are defined.
     *
     * @return the combination of album and track number
     */
    public String getAlbumAndTrack()
    {
        String album = getAlbum();
        if (StringUtils.isEmpty(album))
        {
            return album;
        }
        String track = getTrackNo();
        return (StringUtils.isEmpty(track)) ? album : String.format(
                FMT_ALBUM_TRACK, album, track);
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
        int idx = getListIndex();
        return (idx > 0) ? String.format(FMT_PLINDEX, idx, getPlaylistData()
                .size()) : UNDEF;
    }

    /**
     * Returns the name of the playlist.
     *
     * @return the name of the playlist
     */
    public String getPlaylistName()
    {
        return fetchProperty(getPlaylistData().settings().name());
    }
}
