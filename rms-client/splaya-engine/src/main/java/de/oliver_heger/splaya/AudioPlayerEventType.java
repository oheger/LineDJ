package de.oliver_heger.splaya;

/**
 * <p>
 * An enumeration class defining the different types of audio player events
 * supported by the splaya engine.
 * </p>
 */
public enum AudioPlayerEventType
{
    /** Playback of an audio source has started. */
    START_SOURCE,

    /** Playback of an audio source has finished. */
    END_SONG,

    /** The playback position in the current audio source has changed. */
    POSITION_CHANGED,

    /** The playlist has been played completely. */
    PLAYLIST_END,

    /** A recoverable error occurred during playback. */
    EXCEPTION,

    /** A fatal error occurred during playback. */
    FATAL_EXCEPTION
}
