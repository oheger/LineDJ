package de.oliver_heger.mediastore.server.model;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A specialized synonym class for the synonyms of an {@link AlbumEntity}.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
public class AlbumSynonym extends AbstractSynonym
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110118L;

    /** The owning album. */
    @ManyToOne
    private AlbumEntity album;

    /** The inception year of the synonym album. */
    private Integer inceptionYear;

    /**
     * Returns the album this synonym belongs to.
     *
     * @return the owning {@link AlbumEntity}
     */
    public AlbumEntity getAlbum()
    {
        return album;
    }

    /**
     * Sets the album this synonym belongs to.
     *
     * @param album the owning {@link AlbumEntity}
     */
    public void setAlbum(AlbumEntity album)
    {
        this.album = album;
    }

    /**
     * Returns the inception year of the synonym album.
     *
     * @return the inception year
     */
    public Integer getInceptionYear()
    {
        return inceptionYear;
    }

    /**
     * Sets the inception year of the synonym album.
     *
     * @param inceptionYear the inception year
     */
    public void setInceptionYear(Integer inceptionYear)
    {
        this.inceptionYear = inceptionYear;
    }

    /**
     * Compares this object with another one. In addition to the checks
     * performed by the base class, this implementation tests whether the
     * synonyms belong to the same album and have the same inception year.
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

        AlbumSynonym c = (AlbumSynonym) obj;
        return ObjectUtils.equals(getInceptionYear(), c.getInceptionYear())
                && ObjectUtils.equals(getAlbum(), c.getAlbum());
    }
}
