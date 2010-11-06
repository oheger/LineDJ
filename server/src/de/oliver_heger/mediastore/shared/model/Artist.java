package de.oliver_heger.mediastore.shared.model;

import java.io.Serializable;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An entity object representing an artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
public class Artist implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20100911L;

    /** The artist's ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The name of the artist. */
    private String name;

    /** The ID of the user for which this artist is stored. */
    private String userID;

    /**
     * Returns the ID of this artist.
     *
     * @return the ID of this artist
     */
    public Long getId()
    {
        return id;
    }

    /**
     * Returns the name of this artist.
     *
     * @return the name of this artist
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of this artist.
     *
     * @param name the name of this artist
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the ID of the user associated with this artist.
     *
     * @return the user
     */
    public String getUserID()
    {
        return userID;
    }

    /**
     * Sets the ID of the user. The artist belongs to the private data of this
     * user.
     *
     * @param user the user
     */
    public void setUserID(String user)
    {
        this.userID = user;
    }

    /**
     * Compares this object with another one. Two artist are considered equal if
     * their names are equal (ignoring case) and they belong to the same user.
     *
     * @param obj the object to compare to
     * @return a flag whether the objects are equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof Artist))
        {
            return false;
        }
        Artist c = (Artist) obj;
        return ObjectUtils.equalsIgnoreCase(getName(), c.getName())
                && ObjectUtils.equals(getUserID(), c.getUserID());
    }

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code
     */
    @Override
    public int hashCode()
    {
        int result = ObjectUtils.HASH_SEED;
        result = ObjectUtils.hashIgnoreCase(getName(), result);
        result = ObjectUtils.hash(getUserID(), result);
        return result;
    }

    /**
     * Returns a string representation of this object. This string contains the
     * properties defined for this instance.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "name", getName(), true);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
