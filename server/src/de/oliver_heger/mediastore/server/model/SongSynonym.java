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
    private static final long serialVersionUID = 20101231L;

    /** The owning song. */
    @ManyToOne
    private SongEntity song;

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
     * Compares this object with another one. In addition to the checks
     * performed by {@link #baseEquals(Object)}, this method also checks whether
     * the synonyms are owned by the same song.
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
        return ObjectUtils.equals(getSong(), c.getSong());
    }
}
