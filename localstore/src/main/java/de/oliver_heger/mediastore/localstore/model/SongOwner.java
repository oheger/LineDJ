package de.oliver_heger.mediastore.localstore.model;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang.builder.ToStringBuilder;

/**
 * <p>
 * An abstract base class for objects which can own songs.
 * </p>
 * <p>
 * In the object model of this application there are artists and albums which
 * can be both associated with an arbitrary number of songs. The properties of
 * these entity classes are very similar (because the whole object model is
 * quite simple). Therefore it makes sense to have a common base class for these
 * concrete entity classes which already implements a major part of the
 * functionality for managing songs or other common properties.
 * </p>
 * <p>
 * Concrete subclasses have to define the abstract methods for setting the
 * references on {@link SongEntity} objects. These are called by the base class
 * when new songs are added or removed.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public abstract class SongOwner implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110306L;

    /** Constant for the hash factor. */
    protected static final int HASH_FACTOR = 31;

    /** Constant for the hash seed. */
    private static final int HASH_SEED = 17;

    /** The ID of this entity. */
    private Long id;

    /** The name of this entity. */
    private String name;

    /** A set with the songs associated with this entity. */
    private Set<SongEntity> songs = new HashSet<SongEntity>();

    /**
     * Sets the ID of this artist. This method is intended to be called by the
     * persistence provider.
     *
     * @param id the artist ID
     */
    void setId(Long id)
    {
        this.id = id;
    }

    /**
     * Sets the name of this artist.
     *
     * @param name the artist's name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Sets the set with songs associated with this artist.
     *
     * @param songs the songs of this artist
     */
    public void setSongs(Set<SongEntity> songs)
    {
        this.songs = songs;
    }

    /**
     * Adds a new song to this artist.
     *
     * @param song the song to be added (must not be <b>null</b>)
     * @return a flag whether the song could be added (<b>false</b> means that
     *         the song already existed)
     * @throws NullPointerException if the song is <b>null</b>
     */
    public boolean addSong(SongEntity song)
    {
        if (song == null)
        {
            throw new NullPointerException("Song must not be null!");
        }

        attachSong(song);
        boolean ok = songs.add(song);
        if (!ok)
        {
            detachSong(song);
        }
        return ok;
    }

    /**
     * Removes the specified song from this artist.
     *
     * @param song the song to be removed
     * @return a flag whether the song could be removed successfully
     */
    public boolean removeSong(SongEntity song)
    {
        if (songs.remove(song))
        {
            detachSong(song);
            return true;
        }
        return false;
    }

    /**
     * Returns a string representation of this object. This string contains the
     * name of this object.
     */
    @Override
    public String toString()
    {
        ToStringBuilder tsb = new ToStringBuilder(this);
        tsb.append("name", getInternalName());
        initializeToStringBuilder(tsb);
        return tsb.toString();
    }

    /**
     * Returns the ID of the entity object. Derived classes can use this method
     * to implement the public get method.
     *
     * @return the ID of this object
     */
    protected final Long getInternalId()
    {
        return id;
    }

    /**
     * Returns the name of this object. Derived classes can use this method to
     * implement the public get method.
     *
     * @return the name of this object
     */
    protected final String getInternalName()
    {
        return name;
    }

    /**
     * Returns a set with the songs associated with this object. Derived classes
     * can use this method to implement the public get method.
     *
     * @return a set with the songs of this object
     */
    protected final Set<SongEntity> getInternalSongs()
    {
        return songs;
    }

    /**
     * Compares the names of this {@code SongOwner} with the specified one
     * ignoring case.
     *
     * @param c the object to compare to
     * @return a flag whether the names are equal (ignoring case)
     */
    protected boolean equalsName(SongOwner c)
    {
        return (getInternalName() == null) ? c.getInternalName() == null
                : getInternalName().equalsIgnoreCase(c.getInternalName());
    }

    /**
     * Returns a hash code for the name property. This value can be used as base
     * for the calculation of the total hash code for this object.
     *
     * @return the hash code for the name of this object
     */
    protected int hashName()
    {
        int result = HASH_SEED;
        if (getInternalName() != null)
        {
            result =
                    HASH_FACTOR
                            * result
                            + getInternalName().toLowerCase(Locale.ENGLISH)
                                    .hashCode();
        }
        return result;
    }

    /**
     * Initializes the given {@code ToStringBuilder}. This method is called by
     * the {@link #toString()} method. Derived classes can add additional fields
     * to the builder. This base implementation is empty.
     *
     * @param tsb the {@code ToStringBuilder} for generating the string
     *        representation
     */
    protected void initializeToStringBuilder(ToStringBuilder tsb)
    {
    }

    /**
     * Attaches the given {@link SongEntity} to this object. This method is
     * called when a song is added to this object. A concrete implementation has
     * to set the corresponding reference of the {@link SongEntity} object.
     *
     * @param song the {@link SongEntity} object affected
     */
    protected abstract void attachSong(SongEntity song);

    /**
     * Clears the association between this object and the given
     * {@link SongEntity}. This method is called when the song is to be removed
     * from this object. A concrete implementation has to clear the
     * corresponding reference.
     *
     * @param song
     */
    protected abstract void detachSong(SongEntity song);
}
