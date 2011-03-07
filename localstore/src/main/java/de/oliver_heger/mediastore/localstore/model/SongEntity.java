package de.oliver_heger.mediastore.localstore.model;

import java.io.Serializable;
import java.util.Locale;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.builder.ToStringBuilder;

/**
 * <p>
 * An entity class representing a song.
 * </p>
 * <p>
 * This class is used for storing songs in the local database. A song entity
 * stores a set of meta data about the song. It can be associated with an album
 * and an artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
@Table(name = "SONG")
public class SongEntity implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 201110305L;

    /** The ID of the song. */
    private Long id;

    /** The song name. */
    private String name;

    /** The duration of the song (in seconds). */
    private Integer duration;

    /** The inception year. */
    private Integer inceptionYear;

    /** The track number. */
    private Integer trackNo;

    /** The play count. */
    private int playCount;

    /** The reference to the artist this song belongs to. */
    private ArtistEntity artist;

    /**
     * Returns the ID of this song.
     *
     * @return the song ID
     */
    @Id
    @GeneratedValue
    @Column(name = "SONG_ID")
    public Long getId()
    {
        return id;
    }

    /**
     * Sets the ID of this entity. This method is intended to be called by the
     * persistence provider.
     *
     * @param id the ID of this entity
     */
    void setId(Long id)
    {
        this.id = id;
    }

    /**
     * Returns the name of this song.
     *
     * @return the song name
     */
    @Column(name = "SONG_NAME", nullable = false, length = 255)
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of this song.
     *
     * @param name the song name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the duration of this song in seconds. This may be <b>null</b> if
     * unknown.
     *
     * @return the song duration in seconds
     */
    public Integer getDuration()
    {
        return duration;
    }

    /**
     * Sets the duration of this song in seconds.
     *
     * @param duration the song duration in seconds
     */
    public void setDuration(Integer duration)
    {
        this.duration = duration;
    }

    /**
     * Returns the inception year of this song. This may be <b>null</b> if
     * unknown.
     *
     * @return the inception year of this song
     */
    @Column(name = "INTERCEPTION_YEAR")
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
     * Returns the track number of this song in the album it belongs to. This
     * may be <b>null</b> if there is no album or the track number if unknown.
     *
     * @return the track number
     */
    @Column(name = "TRACK_NO")
    public Integer getTrackNo()
    {
        return trackNo;
    }

    /**
     * Sets the track number of this song.
     *
     * @param trackNo the track number
     */
    public void setTrackNo(Integer trackNo)
    {
        this.trackNo = trackNo;
    }

    /**
     * Returns the number of times this song has been played.
     *
     * @return the number of times this song has been played
     */
    @Column(name = "PLAY_COUNT")
    public int getPlayCount()
    {
        return playCount;
    }

    /**
     * Sets the number of times this song has been played.
     *
     * @param playCount the counter for the plays
     */
    public void setPlayCount(int playCount)
    {
        this.playCount = playCount;
    }

    /**
     * Returns the artist this song belongs to.
     *
     * @return the artist of this song
     */
    @ManyToOne
    @JoinColumn(name = "ARTIST_ID")
    public ArtistEntity getArtist()
    {
        return artist;
    }

    /**
     * Sets the artist this song belongs to.
     *
     * @param artist the artist of this song
     */
    public void setArtist(ArtistEntity artist)
    {
        this.artist = artist;
    }

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code
     */
    @Override
    public int hashCode()
    {
        final int seed = 17;
        final int factor = 37;
        int result = seed;
        if (getName() != null)
        {
            result =
                    factor * result
                            + getName().toLowerCase(Locale.ENGLISH).hashCode();
        }
        result = factor * result + ObjectUtils.hashCode(getDuration());
        // TODO add artist
        return result;
    }

    /**
     * Compares this object with another one. Two instances of
     * {@code SongEntity} are considered equal if the following properties
     * match: name (ignoring case), duration, artist.
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
        if (!(obj instanceof SongEntity))
        {
            return false;
        }

        SongEntity c = (SongEntity) obj;
        // TODO add artist
        return ((getName() == null) ? c.getName() == null : getName()
                .equalsIgnoreCase(c.getName()))
                && ObjectUtils.equals(getDuration(), c.getDuration());
    }

    /**
     * Returns a string representation of this object. This string contains the
     * values of some of the important properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        return new ToStringBuilder(this).append("name", getName())
                .append("duration", getDuration()).toString();
    }
}
