package de.oliver_heger.mediastore.shared.model;

import java.io.Serializable;
import java.util.Date;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A data transfer object representing an album.
 * </p>
 * <p>
 * Objects of this class are transferred to the client as results of the search
 * service for albums. Note that some of the properties defined here do not
 * correspond to fields of the associated entity class. These fields are
 * calculated based on the songs that belong to this album.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumInfo implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110123L;

    /** The ID of the represented album. */
    private Long albumID;

    /** The name of the album. */
    private String name;

    /** The date when the album was added to the database. */
    private Date creationDate;

    /** The duration of this album. */
    private Long duration;

    /** The inception year of this album. */
    private Integer inceptionYear;

    /** The number of songs stored for this album. */
    private int numberOfSongs;

    /**
     * Returns the ID of the represented album.
     *
     * @return the album ID
     */
    public Long getAlbumID()
    {
        return albumID;
    }

    /**
     * Sets the ID of the represented album.
     *
     * @param albumID the album ID
     */
    public void setAlbumID(Long albumID)
    {
        this.albumID = albumID;
    }

    /**
     * Returns the name of the album
     *
     * @return the name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of the album.
     *
     * @param name the name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the date when this album entity was created.
     *
     * @return the creation date
     */
    public Date getCreationDate()
    {
        return creationDate;
    }

    /**
     * Sets the date when this album instance was created. This is the date when
     * the album was added to the database.
     *
     * @param creationDate the creation date
     */
    public void setCreationDate(Date creationDate)
    {
        this.creationDate = creationDate;
    }

    /**
     * Returns the duration of this album. The duration is calculated as the sum
     * of the durations of the songs associated with this album. It may be
     * <b>null</b> if this information is not available.
     *
     * @return the album duration
     */
    public Long getDuration()
    {
        return duration;
    }

    /**
     * Sets the duration of this album.
     *
     * @param duration the duration
     */
    public void setDuration(Long duration)
    {
        this.duration = duration;
    }

    /**
     * Returns the inception year of this album. This may be <b>null</b> if the
     * inception year is unknown.
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
     * Returns the number of songs of this album. Note: This is the number of
     * songs which are associated with the album. If not all songs of the album
     * are stored in the database, this number is different from the actual
     * number of songs which belong to the album.
     *
     * @return the number of songs
     */
    public int getNumberOfSongs()
    {
        return numberOfSongs;
    }

    /**
     * Sets the number of songs of this album.
     *
     * @param numberOfSongs the number of songs
     */
    public void setNumberOfSongs(int numberOfSongs)
    {
        this.numberOfSongs = numberOfSongs;
    }

    /**
     * Returns the duration of this album as a formatted string.
     *
     * @return the formatted album duration
     */
    public String getFormattedDuration()
    {
        return DurationFormatter.formatHours(getDuration());
    }

    /**
     * Returns a string representation of this object. This string contains the
     * values of some important properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "albumID", getAlbumID(), true);
        ObjectUtils.appendToStringField(buf, "name", getName(), false);
        ObjectUtils.appendToStringField(buf, "creationDate", getCreationDate(),
                false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
