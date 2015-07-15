package de.oliver_heger.splaya;

/**
 * <p>
 * An enumeration class defining the different types of playlist events
 * supported by the splaya engine.
 * </p>
 * <p>
 * Playlist events are fired when a new playlist is created or more information
 * about single audio sources becomes available.
 * </p>
 */
public enum PlaylistEventType
{
    /** A new playlist has been created. */
    PLAYLIST_CREATED,

    /**
     * The playlist has been updated. This typically means that information
     * about a playlist item has been fetched.
     */
    PLAYLIST_UPDATED
}
