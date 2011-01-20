package de.oliver_heger.mediastore.shared.model;

import java.io.Serializable;
import java.util.Date;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A data object representing a song.
 * </p>
 * <p>
 * Objects of this class are sent to the client as results for queries for
 * songs.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongInfo implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110101L;

    /** Constant for the string buffer size. */
    private static final int BUF_SIZE = 5;

    /** Constant for the separator for the formatted duration. */
    private static final char DURATION_SEPARATOR = ':';

    /** The ID of the song. */
    private String songID;

    /** The creation date. */
    private Date creationDate;

    /** The name of the song. */
    private String name;

    /** The duration in milliseconds. */
    private Long duration;

    /** The inception year. */
    private Integer inceptionYear;

    /** Stores the ID of the performing artist. */
    private Long artistID;

    /** The name of the performing artist. */
    private String artistName;

    /** Stores the ID of the owning album. */
    private Long albumID;

    /** Stores the name of the album the songs belong to. */
    private String albumName;

    /** Stores the track number (if this song belongs to an album). */
    private Integer trackNo;

    /** Stores the number how often this song has been played so far. */
    private int playCount;

    /**
     * Returns the ID of the song as a string.
     *
     * @return the song ID
     */
    public String getSongID()
    {
        return songID;
    }

    /**
     * Sets the ID of the song as a string.
     *
     * @param songID the song ID
     */
    public void setSongID(String songID)
    {
        this.songID = songID;
    }

    /**
     * Returns the creation date of the song.
     *
     * @return the creation date
     */
    public Date getCreationDate()
    {
        return creationDate;
    }

    /**
     * Sets the creation date of the song.
     *
     * @param creationDate the creation date
     */
    public void setCreationDate(Date creationDate)
    {
        this.creationDate = creationDate;
    }

    /**
     * Returns the name of the song.
     *
     * @return the song name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of the song.
     *
     * @param name the song name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the duration of the song.
     *
     * @return the duration (in milliseconds)
     */
    public Long getDuration()
    {
        return duration;
    }

    /**
     * Sets the duration of the song.
     *
     * @param duration the duration (in milliseconds)
     */
    public void setDuration(Long duration)
    {
        this.duration = duration;
    }

    /**
     * Returns the inception year of the song.
     *
     * @return the inception year
     */
    public Integer getInceptionYear()
    {
        return inceptionYear;
    }

    /**
     * Sets the inception year of the song.
     *
     * @param inceptionYear the inception year
     */
    public void setInceptionYear(Integer inceptionYear)
    {
        this.inceptionYear = inceptionYear;
    }

    /**
     * Returns the ID of the artist this song belongs to. This may be
     * <b>null</b> if the artist is unknown.
     *
     * @return the ID of the artist
     */
    public Long getArtistID()
    {
        return artistID;
    }

    /**
     * Sets the ID of the artist this song belongs to.
     *
     * @param artistID the artist ID
     */
    public void setArtistID(Long artistID)
    {
        this.artistID = artistID;
    }

    /**
     * Returns the name of the artist this song belongs to. This may be
     * <b>null</b> if the artist is unknown.
     *
     * @return the artist name
     */
    public String getArtistName()
    {
        return artistName;
    }

    /**
     * Sets the name of the artist this song belongs to.
     *
     * @param artistName the artist name
     */
    public void setArtistName(String artistName)
    {
        this.artistName = artistName;
    }

    /**
     * Returns the ID of the album this song belongs to. This may be <b>null</b>
     * if the album is unknown.
     *
     * @return the album ID
     */
    public Long getAlbumID()
    {
        return albumID;
    }

    /**
     * Sets the ID of the album this song belongs to.
     *
     * @param albumID the album ID
     */
    public void setAlbumID(Long albumID)
    {
        this.albumID = albumID;
    }

    /**
     * Returns the name of the album this song belongs to. This may be
     * <b>null</b> if the album is unknown.
     *
     * @return the album name
     */
    public String getAlbumName()
    {
        return albumName;
    }

    /**
     * Sets the name of the album this song belongs to.
     *
     * @param albumName the album name
     */
    public void setAlbumName(String albumName)
    {
        this.albumName = albumName;
    }

    /**
     * Returns the track number of the song (if it belongs to a known album).
     *
     * @return the track number
     */
    public Integer getTrackNo()
    {
        return trackNo;
    }

    /**
     * Sets the track number of the song.
     *
     * @param trackNo the track number
     */
    public void setTrackNo(Integer trackNo)
    {
        this.trackNo = trackNo;
    }

    /**
     * Returns the play count of this song.
     *
     * @return the play count
     */
    public int getPlayCount()
    {
        return playCount;
    }

    /**
     * Sets the play count of this song.
     *
     * @param playCount the play count
     */
    public void setPlayCount(int playCount)
    {
        this.playCount = playCount;
    }

    /**
     * Returns the duration as a formatted string.
     *
     * @return the formatted duration
     */
    public String getFormattedDuration()
    {
        if (getDuration() == null)
        {
            return null;
        }

        long durSecs = getDuration() / 1000;
        StringBuilder buf = new StringBuilder(BUF_SIZE);
        appendDurationPart(buf, durSecs / 60);
        buf.append(DURATION_SEPARATOR);
        appendDurationPart(buf, durSecs % 60);
        return buf.toString();
    }

    /**
     * Returns a string representation for this object. This string contains the
     * values of some important properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "name", getName(), true);
        ObjectUtils.appendToStringField(buf, "artist", getArtistName(), false);
        ObjectUtils.appendToStringField(buf, "album", getAlbumName(), false);
        ObjectUtils.appendToStringField(buf, "inceptionYear",
                getInceptionYear(), false);
        ObjectUtils
                .appendToStringField(buf, "playCount", getPlayCount(), false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }

    /**
     * Appends a duration part to a buffer.
     *
     * @param buf the buffer
     * @param part the part to be added
     */
    private static void appendDurationPart(StringBuilder buf, long part)
    {
        if (part < 10)
        {
            buf.append('0');
        }
        buf.append(part);
    }
}
