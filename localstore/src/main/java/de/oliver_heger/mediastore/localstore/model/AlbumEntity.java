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

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.builder.ToStringBuilder;

/**
 * <p>
 * An entity class representing an album.
 * </p>
 * <p>
 * Albums are pretty simple entities. The major part of the functionality is
 * implemented by the base class. There is a 1:n relation to songs.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
@Table(name = "ALBUM")
@NamedQueries({
    @NamedQuery(name = AlbumEntity.QUERY_FIND_SPECIFIC, query = AlbumEntity.QUERY_FIND_SPECIFIC_DEF)
})
public class AlbumEntity extends SongOwner
{
    /** Constant for the album name parameter. */
    public static final String PARAM_NAME = "albumName";

    /** Constant for the inception year parameter. */
    public static final String PARAM_YEAR = "inceptionYear";

    /** Constant for the prefix used for all named album queries. */
    public static final String ALBUM_QUERY_PREFIX =
            "de.oliver_heger.mediastore.localstore.model.AlbumEntity.";

    /**
     * Constant for the name of the query for finding an album by its defining
     * properties.
     */
    public static final String QUERY_FIND_SPECIFIC = ALBUM_QUERY_PREFIX
            + "FIND_SPECIFIC";

    /** The definition of the find specific query. */
    static final String QUERY_FIND_SPECIFIC_DEF =
            "select a from AlbumEntity a where upper(a.name) = :" + PARAM_NAME
                    + " and ((a.inceptionYear = :" + PARAM_YEAR + ") or (:"
                    + PARAM_YEAR + " is null and a.inceptionYear is null))";

    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110307L;

    /** The inception year of this album. */
    private Integer inceptionYear;

    /**
     * Returns the ID of this album.
     *
     * @return the ID of this album
     */
    @Id
    @GeneratedValue
    @Column(name = "ALBUM_ID")
    public Long getId()
    {
        return getInternalId();
    }

    /**
     * Returns the name of this album.
     *
     * @return the album name
     */
    @Column(name = "ALBUM_NAME", length = 255, nullable = false)
    public String getName()
    {
        return getInternalName();
    }

    /**
     * Returns a set with the songs associated with this album.
     *
     * @return a set with the songs of this album
     */
    @OneToMany(mappedBy = "album", cascade = {
            CascadeType.PERSIST, CascadeType.MERGE
    })
    public Set<SongEntity> getSongs()
    {
        return getInternalSongs();
    }

    /**
     * Returns the inception year of this album. This may be <b>null</b> if
     * unknown.
     *
     * @return the inception year of this album
     */
    @Column(name = "INTERCEPTION_YEAR")
    public Integer getInceptionYear()
    {
        return inceptionYear;
    }

    /**
     * Sets the inception year of this album.
     *
     * @param inceptionYear the inception year of this album
     */
    public void setInceptionYear(Integer inceptionYear)
    {
        this.inceptionYear = inceptionYear;
    }

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code for this object
     */
    @Override
    public int hashCode()
    {
        int result = hashName();
        if (getInceptionYear() != null)
        {
            result = HASH_FACTOR * result + getInceptionYear().hashCode();
        }
        return result;
    }

    /**
     * Compares this object with another one. Two {@code AlbumEntity} instances
     * are considered equal if the following properties are equal: name
     * (ignoring case), inception year.
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
        if (!(obj instanceof AlbumEntity))
        {
            return false;
        }

        AlbumEntity c = (AlbumEntity) obj;
        return equalsName(c)
                && ObjectUtils.equals(getInceptionYear(), c.getInceptionYear());
    }

    /**
     * Appends additional fields to the string builder. This implementation also
     * adds the inception year field.
     *
     * @param tsb the builder to be initialized
     */
    @Override
    protected void initializeToStringBuilder(ToStringBuilder tsb)
    {
        tsb.append("inceptionYear", getInceptionYear());
    }

    /**
     * Attaches the given song to this album. This implementation sets the
     * reference to the album in the {@link SongEntity}.
     *
     * @param song the song to be attached
     */
    @Override
    protected void attachSong(SongEntity song)
    {
        song.setAlbum(this);
    }

    /**
     * Detaches the given song from this album. This implementation clears the
     * reference to this album in the {@link SongEntity}.
     *
     * @param song the song to be detached
     */
    @Override
    protected void detachSong(SongEntity song)
    {
        song.setAlbum(null);
    }
}
