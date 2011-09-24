package de.oliver_heger.mediastore.server.model;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A specialized synonym class for the synonyms of a {@link SongEntity}.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
public class SongSynonym extends AbstractSynonym
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110923L;

    /** The owning song. */
    @ManyToOne
    private SongEntity song;

    /** The ID of the artist who performed this song. */
    private Long artistID;

    /** The duration of the song. */
    private Long duration;

    /**
     * Returns the song this synonym belongs to.
     *
     * @return the song
     */
    public SongEntity getSong()
    {
        return song;
    }

    /**
     * Sets the song this synonym belongs to.
     *
     * @param song the song
     */
    public void setSong(SongEntity song)
    {
        this.song = song;
    }

    /**
     * Returns the ID of the artist associated with this synonym.
     *
     * @return the artist ID
     */
    public Long getArtistID()
    {
        return artistID;
    }

    /**
     * Sets the ID of the artist associated with this synonym.
     *
     * @param artistID the artist ID
     */
    public void setArtistID(Long artistID)
    {
        this.artistID = artistID;
    }

    /**
     * Returns the duration of this song synonym.
     *
     * @return the duration
     */
    public Long getDuration()
    {
        return duration;
    }

    /**
     * Sets the duration of this song synonym.
     *
     * @param duration the duration
     */
    public void setDuration(Long duration)
    {
        this.duration = duration;
    }

    /**
     * Compares this object with another one. In addition to the checks
     * performed by {@link #baseEquals(Object)}, this method also checks whether
     * the synonyms are owned by the same song and the equality of the other
     * member fields.
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
        if (!baseEquals(obj))
        {
            return false;
        }

        SongSynonym c = (SongSynonym) obj;
        return ObjectUtils.equals(getArtistID(), c.getArtistID())
                && ObjectUtils.equals(getDuration(), c.getDuration())
                && ObjectUtils.equals(getSong(), c.getSong());
    }
}
