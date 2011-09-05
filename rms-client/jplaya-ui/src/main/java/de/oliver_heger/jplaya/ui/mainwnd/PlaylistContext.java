package de.oliver_heger.jplaya.ui.mainwnd;

import de.oliver_heger.jplaya.playlist.PlaylistInfo;

/**
 * <p>
 * A simple data class storing global information about a playlist.
 * </p>
 * <p>
 * An object of this class is used by the playlist UI model to hold current
 * status information about the playlist. This includes meta data about the list
 * (e.g. its name), but also information about the currently played song.
 * </p>
 * <p>
 * This class is a simple Java bean without any additional logic. It must always
 * be accessed in the EDT.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlaylistContext
{
    /** An object with meta information about the current playlist. */
    private PlaylistInfo playlistInfo;

    /** The elapsed time of the current song. */
    private long playbackTime;

    /**
     * The ratio which has been played from the current song. This value is
     * between 0 and 100.
     */
    private int playbackRatio;

    /** The index of the current song. */
    private int currentSongIndex;

    /**
     * Returns an object with information about the current playlist.
     *
     * @return an object with meta data about the playlist
     */
    public PlaylistInfo getPlaylistInfo()
    {
        return playlistInfo;
    }

    /**
     * Sets an object with information about the current playlist.
     *
     * @param playlistInfo a data object about the playlist
     */
    public void setPlaylistInfo(PlaylistInfo playlistInfo)
    {
        this.playlistInfo = playlistInfo;
    }

    /**
     * Returns the time of the current song that has already been played.
     *
     * @return the elapsed time in the current song (in milliseconds)
     */
    public long getPlaybackTime()
    {
        return playbackTime;
    }

    /**
     * Sets the time of the current song that has already been played.
     *
     * @param playbackTime the played time
     */
    public void setPlaybackTime(long playbackTime)
    {
        this.playbackTime = playbackTime;
    }

    /**
     * Returns the ratio of the current song that has already been played. This
     * is a value between 0 and 100. It can be used for instance to update a
     * progress bar.
     *
     * @return the ratio
     */
    public int getPlaybackRatio()
    {
        return playbackRatio;
    }

    /**
     * Sets the ratio of the current song.
     *
     * @param playbackRatio the ratio
     */
    public void setPlaybackRatio(int playbackRatio)
    {
        this.playbackRatio = playbackRatio;
    }

    /**
     * Returns the index of the current song in the playlist. A value less than
     * 0 means that there is no current song.
     *
     * @return the index of the current song
     */
    public int getCurrentSongIndex()
    {
        return currentSongIndex;
    }

    /**
     * Sets the index of the current song in the playlist.
     *
     * @param currentSongIndex the index of the current song
     */
    public void setCurrentSongIndex(int currentSongIndex)
    {
        this.currentSongIndex = currentSongIndex;
    }
}
