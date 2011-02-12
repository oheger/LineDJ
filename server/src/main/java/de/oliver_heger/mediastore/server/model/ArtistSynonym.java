package de.oliver_heger.mediastore.server.model;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A concrete synonym class for artists.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
public class ArtistSynonym extends AbstractSynonym
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101115L;

    /** Stores the artist this synonym belongs to. */
    @ManyToOne
    private ArtistEntity artist;

    /**
     * Returns the artist this synonym belongs to.
     *
     * @return the owning artist
     */
    public ArtistEntity getArtist()
    {
        return artist;
    }

    /**
     * Sets the artist this synonym belongs to.
     *
     * @param artist the owning artist
     */
    public void setArtist(ArtistEntity artist)
    {
        this.artist = artist;
    }

    /**
     * Compares this object with another one. This implementation calls the
     * {@link #baseEquals(Object)} method inherited from the base class. In
     * addition, it checks whether the synonyms belong to the same artist.
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

        ArtistSynonym c = (ArtistSynonym) obj;
        return ObjectUtils.equals(getArtist(), c.getArtist());
    }
}
