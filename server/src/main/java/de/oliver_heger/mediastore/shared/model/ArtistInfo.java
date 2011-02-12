package de.oliver_heger.mediastore.shared.model;

import java.io.Serializable;
import java.util.Date;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A data object representing an artist.
 * </p>
 * <p>
 * Objects of this type are sent to the client as results of queries for artist
 * entities.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistInfo implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101127L;

    /** The ID of the artist. */
    private Long artistID;

    /** Stores the name of the artist. */
    private String name;

    /** Stores the creation date of the artist. */
    private Date creationDate;

    /**
     * Returns the ID of the artist represented by this object.
     *
     * @return the artist ID
     */
    public Long getArtistID()
    {
        return artistID;
    }

    /**
     * Sets the ID of the artist represented by this object.
     *
     * @param artistID the artist ID
     */
    public void setArtistID(Long artistID)
    {
        this.artistID = artistID;
    }

    /**
     * Returns the name of the artist.
     *
     * @return the artist name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of the artist.
     *
     * @param name the artist name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the creation date of the artist. This is the date this artist was
     * added to the database.
     *
     * @return the creation date
     */
    public Date getCreationDate()
    {
        return creationDate;
    }

    /**
     * Sets the creation date of the artist.
     *
     * @param creationDate the creation date
     */
    public void setCreationDate(Date creationDate)
    {
        this.creationDate = creationDate;
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
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "artistID", getArtistID(), true);
        ObjectUtils.appendToStringField(buf, "name", getName(), false);
        ObjectUtils.appendToStringField(buf, "creationDate", getCreationDate(),
                false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
