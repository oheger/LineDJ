package de.olix.playa.playlist;

/**
 * <p>
 * A simple data class that holds information about the current position in the
 * playlist.
 * </p>
 * <p>
 * If playback is interrupted in the middle of a song, the playlist manager has
 * to store information about the current position in this song, so that
 * playback can continue later at the exact same position. This data class holds
 * all information necessary for this purpose.
 * </p>
 * <p>
 * The class is immutable and thus can be shared between different threads.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class CurrentPositionInfo
{
    /**
     * Constant for an info object for an undefined position. This position
     * object has all properties set to 0. It can be used for referring to a non
     * existing position.
     */
    public static final CurrentPositionInfo UNDEFINED_POSITION = new CurrentPositionInfo(
            0, 0);

    /** Stores the position in bytes. */
    private final long position;

    /** Stores the playback time (in milliseconds). */
    private final long time;

    /**
     * Creates a new instance of <code>CurrentPositionInfo</code> and
     * initializes it.
     *
     * @param pos the position in bytes
     * @param millis the time in milliseconds
     */
    public CurrentPositionInfo(long pos, long millis)
    {
        position = pos;
        time = millis;
    }

    /**
     * Returns the position in bytes.
     *
     * @return the playback position in bytes
     */
    public long getPosition()
    {
        return position;
    }

    /**
     * Returns the playback time.
     *
     * @return the playback time (in milliseconds)
     */
    public long getTime()
    {
        return time;
    }
}
