package de.oliver_heger.mediastore.localstore.model;

import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;

/**
 * <p>
 * Entity class representing an artist.
 * </p>
 * <p>
 * Artists are pretty simple entities. They have an 1:n relation to songs.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
@Table(name = "ARTIST")
@NamedQueries({
    @NamedQuery(name = ArtistEntity.QUERY_FIND_BY_NAME, query = ArtistEntity.QUERY_FIND_BY_NAME_DEF)
})
public class ArtistEntity extends SongOwner
{
    /** Constant for the artist name parameter. */
    public static final String PARAM_NAME = "artistName";

    /** Constant for the prefix used for all named artist queries. */
    public static final String ARTIST_QUERY_PREFIX =
            "de.oliver_heger.mediastore.localstore.model.ArtistEntity.";

    /** Constant for the name of the query for finding an artist by name. */
    public static final String QUERY_FIND_BY_NAME = ARTIST_QUERY_PREFIX
            + "FIND_BY_NAME";

    /** The definition of the find by name query. */
    static final String QUERY_FIND_BY_NAME_DEF =
            "select a from ArtistEntity a where upper(a.name) = :" + PARAM_NAME;

    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110306L;

    /**
     * Returns the ID of this artist.
     *
     * @return the ID of this artist
     */
    @Id
    @GeneratedValue
    @Column(name = "ARTIST_ID")
    public Long getId()
    {
        return getInternalId();
    }

    /**
     * Returns the name of this artist.
     *
     * @return the artist name
     */
    @Column(name = "ARTIST_NAME", length = 255, nullable = false)
    public String getName()
    {
        return getInternalName();
    }

    /**
     * Returns the set with the songs associated with this artist.
     *
     * @return the songs of this artist
     */
    @OneToMany(mappedBy = "artist", cascade = {
            CascadeType.PERSIST, CascadeType.MERGE
    })
    public Set<SongEntity> getSongs()
    {
        return getInternalSongs();
    }

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code for this object
     */
    @Override
    public int hashCode()
    {
        return hashName();
    }

    /**
     * Compares this object with another one. Two instances of
     * {@code ArtistEntity} are considered equal if they have the same name
     * (ignoring case).
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
        if (!(obj instanceof ArtistEntity))
        {
            return false;
        }

        ArtistEntity c = (ArtistEntity) obj;
        return equalsName(c);
    }

    /**
     * Attaches the given song to this artist. Sets the artist reference.
     *
     * @param song the song to be attached
     */
    @Override
    protected void attachSong(SongEntity song)
    {
        song.setArtist(this);
    }

    /**
     * Detaches the given song from this artist. Sets the artist reference to
     * <b>null</b>.
     *
     * @param song the song to be detached
     */
    @Override
    protected void detachSong(SongEntity song)
    {
        song.setArtist(null);
    }
}
