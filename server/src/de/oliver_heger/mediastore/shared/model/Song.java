package de.oliver_heger.mediastore.shared.model;

import java.io.Serializable;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An entity object representing a song.
 * </p>
 * <p>
 * In addition to storing some meta data, a {@code Song} object links the
 * entities {@link Artist} and {@link Album} together. Both are referenced.
 * Specialized find methods are defined for retrieving all songs of an artist
 * and all songs that belong to an album. Because of restrictions of the
 * persistence provided by Google AppEngine no JPA relations are defined, but
 * the primary keys are stored directly.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
public class Song implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20100926L;

    /** The song's ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stores the ID of the user this song belongs to. */
    private String userID;

    /** The name of the song. */
    private String name;

    /** The duration in seconds. */
    private Long duration;

    /** The inception year. */
    private Integer inceptionYear;

    /** Stores the ID of the performing artist. */
    private Long artistID;

    /** Stores a reference to the album this song belongs to. */
    private Long albumID;

    /** Stores the track number (if this song belongs to an album). */
    private Integer trackNo;

    /** Stores the number how often this song has been played so far. */
    private int playCount;

    /**
     * Returns the ID of this song.
     *
     * @return the ID
     */
    public Long getId()
    {
        return id;
    }

    /**
     * Returns the duration of this song in milliseconds.
     *
     * @return the duration
     */
    public Long getDuration()
    {
        return duration;
    }

    /**
     * Sets the duration of this song.
     *
     * @param duration the duration (in milliseconds)
     */
    public void setDuration(Long duration)
    {
        this.duration = duration;
    }

    /**
     * Returns the name of this song.
     *
     * @return the name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name for this song.
     *
     * @param name the new name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the ID of the artist who performed this song. This may be
     * <b>null</b> if the artist is unknown.
     *
     * @return the artist
     */
    public Long getArtistID()
    {
        return artistID;
    }

    /**
     * Sets the ID of the artist of this song.
     *
     * @param artist the artistID
     */
    public void setArtistID(Long artist)
    {
        this.artistID = artist;
    }

    /**
     * Returns the ID of the album this song belongs to. This may be <b>null</b>
     * if the song does not belong to an album.
     *
     * @return the ID of the album
     */
    public Long getAlbumID()
    {
        return albumID;
    }

    /**
     * Sets the ID of the album this song belongs to.
     *
     * @param albumID the ID of the album
     */
    public void setAlbumID(Long albumID)
    {
        this.albumID = albumID;
    }

    /**
     * Returns the inception year of this song.
     *
     * @return the inception year
     */
    public Integer getInceptionYear()
    {
        return inceptionYear;
    }

    /**
     * Sets the inception year of this song.
     *
     * @param inceptionYear the inception year
     */
    public void setInceptionYear(Integer inceptionYear)
    {
        this.inceptionYear = inceptionYear;
    }

    /**
     * Returns the number of times this song has been played.
     *
     * @return the number of times this song has been played
     */
    public int getPlayCount()
    {
        return playCount;
    }

    /**
     * Sets the number of times this song has been played.
     *
     * @param playCount the new counter value
     */
    public void setPlayCount(int playCount)
    {
        this.playCount = playCount;
    }

    /**
     * Returns the track number of this song.
     *
     * @return the track number
     */
    public Integer getTrackNo()
    {
        return trackNo;
    }

    /**
     * Sets the track number. This number is valid only if this song belongs to
     * an album.
     *
     * @param trackNo the track number
     */
    public void setTrackNo(Integer trackNo)
    {
        this.trackNo = trackNo;
    }

    /**
     * Returns the ID of the user this song belongs to.
     *
     * @return the user ID
     */
    public String getUserID()
    {
        return userID;
    }

    /**
     * Sets the ID of the user this song belongs to.
     *
     * @param userID the user ID
     */
    public void setUserID(String userID)
    {
        this.userID = userID;
    }

    /**
     * Compares this object with another one. Two songs are considered equal if
     * and only if they have the same name (ignoring case), the same duration,
     * belong to the same artist, and are associated with the same user.
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
        if (!(obj instanceof Song))
        {
            return false;
        }
        Song c = (Song) obj;
        return ObjectUtils.equalsIgnoreCase(getName(), c.getName())
                && ObjectUtils.equals(getDuration(), c.getDuration())
                && ObjectUtils.equals(getArtistID(), c.getArtistID())
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
        result = ObjectUtils.hash(getDuration(), result);
        result = ObjectUtils.hash(getArtistID(), result);
        result = ObjectUtils.hash(getUserID(), result);
        return result;
    }

    /**
     * Returns a string representation of this object. The string will contain
     * the attributes used for identifying a song.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "name", getName(), true);
        if (getDuration() != null)
        {
            ObjectUtils.appendToStringField(buf, "duration", getDuration()
                    + " ms", false);
        }
        ObjectUtils.appendToStringField(buf, "inceptionYear",
                getInceptionYear(), false);
        ObjectUtils.appendToStringField(buf, "track", getTrackNo(), false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
