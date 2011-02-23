package de.oliver_heger.mediastore.server.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
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
@NamedQueries({
        @NamedQuery(name = AlbumEntity.QUERY_FIND_BY_IDS, query = AlbumEntity.QUERY_FIND_BY_IDS_DEF),
        @NamedQuery(name = AlbumEntity.QUERY_FIND_BY_NAME, query = AlbumEntity.QUERY_FIND_BY_NAME_DEF),
        @NamedQuery(name = AlbumEntity.QUERY_FIND_BY_SYNONYM, query = AlbumEntity.QUERY_FIND_BY_SYNONYM_DEF)
})
public class AlbumEntity implements Serializable
{
    /** Constant for the prefix for album queries. */
    public static final String ALBUM_QUERY_PREFIX =
            "de.oliver_heger.mediastore.server.model.AlbumEntity.";

    /** Constant for the query for retrieving albums by IDs. */
    public static final String QUERY_FIND_BY_IDS = ALBUM_QUERY_PREFIX
            + "QUERY_FIND_BY_IDS";

    /** Constant for the name of the query for finding albums by name. */
    static final String QUERY_FIND_BY_NAME = ALBUM_QUERY_PREFIX
            + "QUERY_FIND_BY_NAME";

    /** Constant for the name of the query for finding albums by synonym name. */
    static final String QUERY_FIND_BY_SYNONYM = ALBUM_QUERY_PREFIX
            + "QUERY_FIND_BY_SYNONYM";

    /**
     * Constant for the definition of the query for retrieving artists by a set
     * of ID values.
     */
    static final String QUERY_FIND_BY_IDS_DEF = "select a from AlbumEntity a"
            + " where a.id in (:" + Finders.PARAM_ID + ")";

    /** Constant for the definition of the query for finding albums by name. */
    static final String QUERY_FIND_BY_NAME_DEF = "select a from AlbumEntity a "
            + "where a.user = :" + Finders.PARAM_USER + " and a.searchName = :"
            + Finders.PARAM_NAME;

    /** Constant for the definition of the query for finding albums by synonym. */
    static final String QUERY_FIND_BY_SYNONYM_DEF = "select syn.album "
            + "from AlbumSynonym syn " + "where syn.user = :"
            + Finders.PARAM_USER + " and syn.searchName = :"
            + Finders.PARAM_NAME;

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

    /** The inception year of this album. */
    private Integer inceptionYear;

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
     * Returns the inception year of this album. Result may be <b>null</b> if
     * the inception year is unknown.
     *
     * @return the inception year
     */
    public Integer getInceptionYear()
    {
        return inceptionYear;
    }

    /**
     * Sets the inception year of this album.
     *
     * @param inceptionYear the inception year
     */
    public void setInceptionYear(Integer inceptionYear)
    {
        this.inceptionYear = inceptionYear;
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
        result = ObjectUtils.hash(getInceptionYear(), result);

        return result;
    }

    /**
     * Compares this object with another one. Two instances of
     * {@code AlbumEntity} are considered equal if they have the same name
     * (ignoring case), belong to the same user, and have the same inception
     * year.
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
                && ObjectUtils.equals(getUser(), c.getUser())
                && ObjectUtils.equals(getInceptionYear(), c.getInceptionYear());
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
        ObjectUtils.appendToStringField(buf, "inceptionYear",
                getInceptionYear(), false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }

    /**
     * Searches for albums by name. The album name need not be unique, therefore
     * this method can return a list of albums.
     *
     * @param em the entity manager
     * @param user the current user
     * @param name the name of the album
     * @return a list with matching albums
     */
    public static List<AlbumEntity> findByName(EntityManager em, User user,
            String name)
    {
        return queryForAlbumsByName(em, QUERY_FIND_BY_NAME, user, name);
    }

    /**
     * Searches for albums by their synonym name. Multiple results can be
     * retrieved as synonym names need not be unique.
     *
     * @param em the entity manager
     * @param user the current user
     * @param name the synonym name to search for
     * @return a list with the matching albums
     */
    public static List<AlbumEntity> findBySynonym(EntityManager em, User user,
            String name)
    {
        return queryForAlbumsByName(em, QUERY_FIND_BY_SYNONYM, user, name);
    }

    /**
     * Searches for albums with the given name in both the name and synonym
     * properties. Duplicates are removed.
     *
     * @param em the entity manager
     * @param user the current user
     * @param name the name to search for
     * @return a set with the matching albums
     */
    public static List<AlbumEntity> findByNameAndSynonym(EntityManager em,
            User user, String name)
    {
        LinkedHashSet<AlbumEntity> result =
                new LinkedHashSet<AlbumEntity>(findByName(em, user, name));
        result.addAll(findBySynonym(em, user, name));
        return new ArrayList<AlbumEntity>(result);
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

    /**
     * Helper method for executing a named query for albums with a name and user
     * parameter.
     *
     * @param em the entity manager
     * @param query the name of the query
     * @param user the current user
     * @param name the name to be searched for
     * @return the query results
     */
    private static List<AlbumEntity> queryForAlbumsByName(EntityManager em,
            String query, User user, String name)
    {
        @SuppressWarnings("unchecked")
        List<AlbumEntity> result =
                em.createNamedQuery(query)
                        .setParameter(Finders.PARAM_USER, user)
                        .setParameter(Finders.PARAM_NAME,
                                EntityUtils.generateSearchString(name))
                        .getResultList();
        return result;
    }
}
