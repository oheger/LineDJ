package de.oliver_heger.jplaya.playlist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * <p>
 * A simple data class for defining songs that always have to be played in a
 * predefined order.
 * </p>
 * <p>
 * Sometimes it is the case that a longer song is split into multiple tracks.
 * Then the single tracks of this song should always be played in sequence even
 * if the order of the playlist is {@link PlaylistOrder#RANDOM}.
 * </p>
 * <p>
 * A {@code KeepGroup} can be used to achieve this effect. An instance is passed
 * a list with the URIs of the songs that belong to the group. When constructing
 * a random order these groups are taken into account.
 * </p>
 * <p>
 * Implementation note: Instances of this class are immutable and can be shared
 * between multiple threads.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public final class KeepGroup
{
    /** The list with the URIs of the songs that belong to this group. */
    private final List<String> songURIs;

    /**
     * Creates a new instance of {@code KeepGroup} and initializes it with the
     * URIs of the songs which belong to the group. The passed in collection
     * must not be <b>null</b> and must contain at least 2 elements.
     *
     * @param uris the collection with song URIs (must not be <b>null</b>)
     * @throws IllegalArgumentException if the collection is invalid
     */
    public KeepGroup(Collection<String> uris)
    {
        if (uris == null)
        {
            throw new IllegalArgumentException(
                    "List with song URIs must not be null!");
        }
        if (uris.size() < 2)
        {
            throw new IllegalArgumentException(
                    "Group must contain at least 2 elements!");
        }

        songURIs = new ArrayList<String>(uris);
    }

    /**
     * Returns the number of songs that belong to this group.
     *
     * @return the number of songs
     */
    public int size()
    {
        return songURIs.size();
    }

    /**
     * Returns the URI of the song at the specified index. The index is 0-based
     * and can be in the range from 0 to {@code size() - 1}.
     *
     * @param idx the index of the song in question
     * @return the URI of the song with this index
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    public String getSongURI(int idx)
    {
        return songURIs.get(idx);
    }

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code
     */
    @Override
    public int hashCode()
    {
        return songURIs.hashCode();
    }

    /**
     * Compares this object with another one. Two instances of this class are
     * considered equal if they contain the same songs in the same order.
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
        if (!(obj instanceof KeepGroup))
        {
            return false;
        }

        KeepGroup c = (KeepGroup) obj;
        return songURIs.equals(c.songURIs);
    }

    /**
     * Returns a string representation of this object.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        return new ToStringBuilder(this).append("songs", songURIs).toString();
    }
}
