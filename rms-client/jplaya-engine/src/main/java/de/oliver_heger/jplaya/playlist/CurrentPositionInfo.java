package de.oliver_heger.jplaya.playlist;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * <p>
 * A simple data class that holds information about the current position in the
 * play list.
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
public final class CurrentPositionInfo
{
    /**
     * Constant for an info object for an undefined position. This position
     * object has all properties set to 0. It can be used for referring to a non
     * existing position.
     */
    public static final CurrentPositionInfo UNDEFINED_POSITION =
            new CurrentPositionInfo(0, 0);

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

    /**
     * Calculates a hash code for this object.
     *
     * @return a hash code for this object
     */
    @Override
    public int hashCode()
    {
        return new HashCodeBuilder().append(getPosition()).append(getTime())
                .toHashCode();
    }

    /**
     * Compares this object with another one. Two instances of this class are
     * considered equals if they have the same position and time properties.
     *
     * @param obj the object to compare to
     * @return a flag whether these objects are equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof CurrentPositionInfo))
        {
            return false;
        }

        CurrentPositionInfo c = (CurrentPositionInfo) obj;
        return getPosition() == c.getPosition() && getTime() == c.getTime();
    }

    /**
     * Returns a string representation for this object. This string contains the
     * values of all properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        return new ToStringBuilder(this).append("position", getPosition())
                .append("time", getTime()).toString();
    }
}
