package de.oliver_heger.mediastore.shared.model;

import java.io.Serializable;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An entity object representing an album.
 * </p>
 * <p>
 * This class defines some meta data of the album. The songs of an album can be
 * stored, too; however, this information is stored in the {@link Song} class.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
public class Album implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101002L;

    /** The album's ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stores the ID of the user this album belongs to. */
    private String userID;

    /** The name of the album. */
    private String name;

    /** The inception year of this album. */
    private Integer inceptionYear;

    /**
     * Returns the ID of this album.
     *
     * @return the album ID
     */
    public Long getId()
    {
        return id;
    }

    /**
     * Returns the ID of the user to which this entity belongs.
     *
     * @return the user ID
     */
    public String getUserID()
    {
        return userID;
    }

    /**
     * Sets the ID of the user who is the owner of this entity.
     *
     * @param userID the user ID
     */
    public void setUserID(String userID)
    {
        this.userID = userID;
    }

    /**
     * Returns the name of this album.
     *
     * @return the name of this album
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of this album.
     *
     * @param name the name of this album
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the inception year of this album. This may be <b>null</b> if
     * unknown.
     *
     * @return the inception year
     */
    public Integer getInceptionYear()
    {
        return inceptionYear;
    }

    /**
     * Sets the inception year of this album.
     *
     * @param inceptionYear the inception year
     */
    public void setInceptionYear(Integer inceptionYear)
    {
        this.inceptionYear = inceptionYear;
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
        result = ObjectUtils.hash(getInceptionYear(), result);
        result = ObjectUtils.hash(getUserID(), result);
        return result;
    }

    /**
     * Compares this object with another one. Two instances of {@code Album} are
     * equal if the following properties match: name, inceptionYear, userID.
     *
     * @param obj the object to compare to
     * @return a flag whether both objects are equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof Album))
        {
            return false;
        }

        Album c = (Album) obj;
        return ObjectUtils.equalsIgnoreCase(getName(), c.getName())
                && ObjectUtils.equals(getInceptionYear(), c.getInceptionYear())
                && ObjectUtils.equals(getUserID(), c.getUserID());
    }

    /**
     * Returns a string representation for this object. This string contains the
     * most important properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "name", getName(), true);
        ObjectUtils.appendToStringField(buf, "inceptionYear",
                getInceptionYear(), false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
