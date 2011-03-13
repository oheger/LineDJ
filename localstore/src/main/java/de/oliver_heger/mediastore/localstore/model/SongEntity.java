package de.oliver_heger.mediastore.localstore.model;

import java.io.Serializable;
import java.util.Locale;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
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
 * <p>
 * This entity class keeps counters for the number of times the song has been
 * played. There is one counter for the total count and one for the count since
 * the last synchronization with the server database. The latter is also used to
 * determine which songs have to be sent to the server. Normally both counters
 * are initialized when a new instance is created. However, for legacy data the
 * total count is undefined. Therefore this class contains some logic for
 * dealing with such cases.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
@Table(name = "SONG")
@NamedQueries({
        @NamedQuery(name = SongEntity.QUERY_FIND_SPECIFIC_WITH_ARTIST, query = SongEntity.QUERY_FIND_SPECIFIC_WITH_ARTIST_DEF),
        @NamedQuery(name = SongEntity.QUERY_FIND_SPECIFIC_NO_ARTIST, query = SongEntity.QUERY_FIND_SPECIFIC_NO_ARTIST_DEF)
})
public class SongEntity implements Serializable
{
    /** Constant for the song name parameter. */
    public static final String PARAM_NAME = "songName";

    /** Constant for the duration parameter. */
    public static final String PARAM_DURATION = "duration";

    /** Constant for the artist name parameter. */
    public static final String PARAM_ARTIST = "artist";

    /** Constant for the prefix used for all named song queries. */
    public static final String SONG_QUERY_PREFIX =
            "de.oliver_heger.mediastore.localstore.model.SongEntity.";

    /**
     * Constant for the name of the query for finding a song by its defining
     * properties if an artist parameter is provided.
     */
    public static final String QUERY_FIND_SPECIFIC_WITH_ARTIST =
            SONG_QUERY_PREFIX + "FIND_SPECIFIC_WITH_ARTIST";

    /**
     * Constant for the name of the query for finding a song by its defining
     * properties if no artist parameter is provided.
     */
    public static final String QUERY_FIND_SPECIFIC_NO_ARTIST =
            SONG_QUERY_PREFIX + "FIND_SPECIFIC_NO_ARTIST";

    /** A query prefix with the defining properties except for the artist. */
    static final String QUERY_FIND_SPECIFIC_PREFIX =
            "select s from SongEntity s where upper(s.name) = :" + PARAM_NAME
                    + " and ((s.duration = :" + PARAM_DURATION + ") or (:"
                    + PARAM_DURATION + " is null and s.duration is null))";

    /** The definition of the find specific with artist query. */
    static final String QUERY_FIND_SPECIFIC_WITH_ARTIST_DEF =
            QUERY_FIND_SPECIFIC_PREFIX + " and upper(s.artist.name) = :"
                    + PARAM_ARTIST;

    /** The definition of the find specific without artist query. */
    static final String QUERY_FIND_SPECIFIC_NO_ARTIST_DEF =
            QUERY_FIND_SPECIFIC_PREFIX + " and s.artist is null";

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

    /** The play count since the last synchronization. */
    private int currentPlayCount;

    /** The total play count. */
    private Integer totalPlayCount;

    /** The reference to the artist this song belongs to. */
    private ArtistEntity artist;

    /** The reference to the album this song belongs to. */
    private AlbumEntity album;

    /**
     * Creates a new instance of {@code SongEntity}.
     */
    public SongEntity()
    {
        totalPlayCount = Integer.valueOf(0);
    }

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
     * Returns the current number of times this song has been played since the
     * last synchronization. This number is reset every time a synchronization
     * with the remote media store happens.
     *
     * @return the current number of times this song has been played
     */
    @Column(name = "PLAY_COUNT")
    public int getCurrentPlayCount()
    {
        return currentPlayCount;
    }

    /**
     * Sets the current number of times this song has been played.
     *
     * @param playCount the counter for the plays
     */
    public void setCurrentPlayCount(int playCount)
    {
        this.currentPlayCount = playCount;
    }

    /**
     * Returns the total number of times this song has been played. This field
     * is only increased, it is not affected by a synchronization.
     *
     * @return the total play count
     */
    @Column(name = "TOTAL_PLAY_COUNT")
    public Integer getTotalPlayCount()
    {
        return (totalPlayCount != null) ? totalPlayCount : Integer
                .valueOf(getCurrentPlayCount());
    }

    /**
     * Sets the total number of times this song has been played. Note: Normally,
     * this field should always be set. However, for legacy data it may be
     * <b>null</b>. For such cases this class contains some logic to update the
     * field automatically.
     *
     * @param totalPlayCount the total play count
     */
    public void setTotalPlayCount(Integer totalPlayCount)
    {
        this.totalPlayCount = totalPlayCount;
    }

    /**
     * Returns the artist this song belongs to. This can be <b>null</b> if the
     * artist is not known.
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
     * Returns the album this song belongs to. This can be <b>null</b> if the
     * album is not known.
     *
     * @return the album of this song
     */
    @ManyToOne
    @JoinColumn(name = "ALBUM_ID")
    public AlbumEntity getAlbum()
    {
        return album;
    }

    /**
     * Sets the album this song belongs to.
     *
     * @param album the album of this song
     */
    public void setAlbum(AlbumEntity album)
    {
        this.album = album;
    }

    /**
     * Increments the counters managed by this entity. This method should be
     * called when the song has been played.
     */
    public void incrementPlayCount()
    {
        setCurrentPlayCount(getCurrentPlayCount() + 1);

        if (totalPlayCount != null)
        {
            setTotalPlayCount(Integer.valueOf(totalPlayCount + 1));
        }
    }

    /**
     * Resets the current play count. This method should be called after a
     * successful synchronization.
     */
    public void resetCurrentCount()
    {
        if (totalPlayCount == null)
        {
            setTotalPlayCount(Integer.valueOf(getCurrentPlayCount()));
        }

        setCurrentPlayCount(0);
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
        if (getArtist() != null)
        {
            result = factor * result + getArtist().hashCode();
        }
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
        return ((getName() == null) ? c.getName() == null : getName()
                .equalsIgnoreCase(c.getName()))
                && ObjectUtils.equals(getDuration(), c.getDuration())
                && ObjectUtils.equals(getArtist(), c.getArtist());
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
                .append("duration", getDuration())
                .append("artist", getArtist()).toString();
    }
}
