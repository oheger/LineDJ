package de.oliver_heger.mediastore.server.model;

import java.io.Serializable;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;

import com.google.appengine.api.users.User;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An entity class representing an album.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
public class AlbumEntity implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110117L;

    /** The album's ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The name of the album. */
    private String name;

    /** The search name of the album. This field is used by queries. */
    private String searchName;

    /** The user for which this album is stored. */
    private User user;

    /** The date when this album was created. */
    private Date creationDate;

    /** A set with the synonyms of this album. */
    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL)
    private Set<AlbumSynonym> synonyms = new HashSet<AlbumSynonym>();

    /**
     * Creates a new instance of {@code AlbumEntity}.
     */
    public AlbumEntity()
    {
        creationDate = new Date();
    }

    /**
     * Returns the ID of this album.
     *
     * @return the album ID
     */
    public Long getId()
    {
        return id;
    }

    /**
     * Returns the name of the album.
     *
     * @return the album name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of the album.
     *
     * @param name the album name
     */
    public void setName(String name)
    {
        this.name = name;
        searchName = EntityUtils.generateSearchString(name);
    }

    /**
     * Returns the user this album belongs to.
     *
     * @return the associated user
     */
    public User getUser()
    {
        return user;
    }

    /**
     * Sets the user this album belongs to.
     *
     * @param user the associated user
     */
    public void setUser(User user)
    {
        this.user = user;
    }

    /**
     * Returns the creation date of this album. This is the date when this album
     * entity was added to the database.
     *
     * @return the creation date of the album
     */
    public Date getCreationDate()
    {
        return creationDate;
    }

    /**
     * Sets the creation date of this album.
     *
     * @param creationDate the creation date
     */
    public void setCreationDate(Date creationDate)
    {
        this.creationDate = creationDate;
    }

    /**
     * Returns a set with the synonyms of this album.
     *
     * @return a set with the synonyms
     */
    public Set<AlbumSynonym> getSynonyms()
    {
        return synonyms;
    }

    /**
     * Sets the set with the synonyms of this album.
     *
     * @param synonyms the synonyms
     */
    public void setSynonyms(Set<AlbumSynonym> synonyms)
    {
        this.synonyms = synonyms;
    }

    /**
     * Adds a synonym to this album entity. This method also checks whether the
     * synonym already exists; in this case, it returns <b>false</b>.
     *
     * @param syn the synonym object to be added (must not be <b>null</b>)
     * @return a flag whether the synonym was added
     * @throws NullPointerException if the synonym is <b>null</b> or does not
     *         have a name
     */
    public boolean addSynonym(AlbumSynonym syn)
    {
        if (syn == null || syn.getName() == null)
        {
            throw new NullPointerException("Synonym must not be null!");
        }

        syn.setUser(getUser());
        syn.setAlbum(this);
        if (synonyms.add(syn))
        {
            return true;
        }
        else
        {
            syn.setAlbum(null);
            return false;
        }
    }

    /**
     * Adds a new synonym name to this album entity. This is a convenience
     * method that creates the corresponding {@link AlbumSynonym} object and
     * delegates to {@link #addSynonym(AlbumSynonym)}.
     *
     * @param synName the name of the synonym to be added
     * @return a flag whether the synonym could be added
     * @throws NullPointerException if the name is <b>null</b>
     */
    public boolean addSynonymName(String synName)
    {
        AlbumSynonym syn = new AlbumSynonym();
        syn.setName(synName);
        return addSynonym(syn);
    }

    /**
     * Removes the specified synonym object from this entity if it can be found.
     *
     * @param syn the {@link AlbumSynonym} to be removed
     * @return a flag whether the synonym could be removed
     */
    public boolean removeSynonym(AlbumSynonym syn)
    {
        if (syn == null || syn.getAlbum() != this)
        {
            return false;
        }

        boolean result = synonyms.remove(syn);
        syn.setAlbum(null);
        return result;
    }

    /**
     * Removes the specified synonym name from this entity if it can be found.
     *
     * @param synName the synonym name to be removed
     * @return a flag whether this operation was successful
     */
    public boolean removeSynonymName(String synName)
    {
        return removeSynonym(findSynonym(synName));
    }

    /**
     * Returns the {@link AlbumSynonym} entity with the specified synonym name
     * if it can be found.
     *
     * @param synName the synonym name
     * @return the corresponding {@link AlbumEntity} object or <b>null</b> if
     *         the synonym cannot be resolved
     */
    public AlbumSynonym findSynonym(String synName)
    {
        return AbstractSynonym.findSynonym(synonyms, synName);
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
        result = ObjectUtils.hash(getSearchName(), result);
        result = ObjectUtils.hash(getUser(), result);

        return result;
    }

    /**
     * Compares this object with another one. Two instances of
     * {@code AlbumEntity} are considered equal if they have the same name
     * (ignoring case) and belong to the same user.
     *
     * @param obj the object to compare to
     * @return a flag whether the objects are equal
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
        return ObjectUtils.equals(getSearchName(), c.getSearchName())
                && ObjectUtils.equals(getUser(), c.getUser());
    }

    /**
     * Returns a string representation of this object. This string contains the
     * values of the most important properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "name", getName(), true);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }

    /**
     * Returns the search name of this album. This property is updated
     * automatically when the name is changed. It is used by database queries.
     *
     * @return the search name for this album
     */
    String getSearchName()
    {
        return searchName;
    }
}
