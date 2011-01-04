package de.oliver_heger.mediastore.server.model;

import java.io.Serializable;
import java.util.Date;
import java.util.HashSet;
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
 * An entity object representing an artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
@NamedQueries({
        @NamedQuery(name = ArtistEntity.QUERY_FIND_BY_NAME, query = ArtistEntity.QUERY_FIND_BY_NAME_DEF),
        @NamedQuery(name = ArtistEntity.QUERY_FIND_BY_SYNONYM, query = ArtistEntity.QUERY_FIND_BY_SYNONYM_DEF),
        @NamedQuery(name = ArtistEntity.QUERY_FIND_BY_IDS, query = ArtistEntity.QUERY_FIND_BY_IDS_DEF)
})
public class ArtistEntity implements Serializable
{
    /** Constant for the prefix for artist queries. */
    public static final String ARTIST_QUERY_PREFIX =
            "de.oliver_heger.mediastore.server.model.ArtistEntity.";

    /** Constant for the query for retrieving artists by IDs. */
    public static final String QUERY_FIND_BY_IDS = ARTIST_QUERY_PREFIX
            + "QUERY_FIND_BY_IDS";

    /** Constant for the name of the query for finding an artist by name. */
    static final String QUERY_FIND_BY_NAME = ARTIST_QUERY_PREFIX
            + "QUERY_FIND_BY_NAME";

    /** Constant for the definition of the query for finding an artist by name. */
    static final String QUERY_FIND_BY_NAME_DEF =
            "select a from ArtistEntity a " + "where a.user = :"
                    + Finders.PARAM_USER + " and a.searchName = :"
                    + Finders.PARAM_NAME;

    /** Constant for the name of the query for finding an artist by synonym. */
    static final String QUERY_FIND_BY_SYNONYM = ARTIST_QUERY_PREFIX
            + "QUERY_FIND_BY_SYNONYM";

    /**
     * Constant for the definition of the query for finding an artist by
     * synonym.
     */
    static final String QUERY_FIND_BY_SYNONYM_DEF = "select syn.artist "
            + "from ArtistSynonym syn " + "where syn.user = :"
            + Finders.PARAM_USER + " and syn.searchName = :"
            + Finders.PARAM_NAME;

    /**
     * Constant for the definition of the query for retrieving artists by a set
     * of ID values.
     */
    static final String QUERY_FIND_BY_IDS_DEF = "select a from ArtistEntity a"
            + " where a.id in (:" + Finders.PARAM_ID + ")";

    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20100911L;

    /** The artist's ID. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The name of the artist. */
    private String name;

    /** The search name of the artist. This field is used by queries. */
    private String searchName;

    /** The user for which this artist is stored. */
    private User user;

    /** The date when this user was created. */
    private Date creationDate;

    /** A set with synonyms associated with this artist. */
    @OneToMany(mappedBy = "artist", cascade = CascadeType.ALL)
    private Set<ArtistSynonym> synonyms = new HashSet<ArtistSynonym>();

    /**
     * Creates a new instance of {@code ArtistEntity}.
     */
    public ArtistEntity()
    {
        creationDate = new Date();
    }

    /**
     * Returns the ID of this artist.
     *
     * @return the ID of this artist
     */
    public Long getId()
    {
        return id;
    }

    /**
     * Returns the name of this artist.
     *
     * @return the name of this artist
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of this artist.
     *
     * @param name the name of this artist
     */
    public void setName(String name)
    {
        this.name = name;
        updateSearchName();
    }

    /**
     * Returns the user associated with this artist.
     *
     * @return the user
     */
    public User getUser()
    {
        return user;
    }

    /**
     * Sets the user. The artist belongs to the private data of this user.
     *
     * @param user the user
     */
    public void setUser(User user)
    {
        this.user = user;
    }

    /**
     * Returns the creation date of this entity.
     *
     * @return the creation date
     */
    public Date getCreationDate()
    {
        return creationDate;
    }

    /**
     * Sets the creation date of this entity.
     *
     * @param creationDate the creation date
     */
    public void setCreationDate(Date creationDate)
    {
        this.creationDate = creationDate;
    }

    /**
     * Returns a set with the synonyms defined for this artist.
     *
     * @return the synonyms of this artist
     */
    public Set<ArtistSynonym> getSynonyms()
    {
        return synonyms;
    }

    /**
     * Sets the synonyms defined for this artist.
     *
     * @param synonyms a set with the synonyms of this artist
     */
    public void setSynonyms(Set<ArtistSynonym> synonyms)
    {
        this.synonyms = synonyms;
    }

    /**
     * Adds the specified synonym to this artist.
     *
     * @param syn the synonym to be added (must not be <b>null</b>)
     * @return a flag whether the synonym could be added; a value of
     *         <b>false</b> means that a synonym with this name already exists
     * @throws NullPointerException if the synonym is <b>null</b> or does not
     *         have a name
     */
    public boolean addSynonym(ArtistSynonym syn)
    {
        AbstractSynonym.checkSynonym(syn);
        syn.setArtist(this);
        syn.setUser(getUser());

        if (synonyms.add(syn))
        {
            return true;
        }
        else
        {
            // do not assign duplicate synonym to this artist
            syn.setArtist(null);
            return false;
        }
    }

    /**
     * Adds the specified name as synonym to this artist. This is a convenience
     * method which creates the correct synonym entity.
     *
     * @param syn the synonym name (must not be <b>null</b>)
     * @return a flag whether the synonym could be added; a value of
     *         <b>false</b> means that a synonym with this name already exists
     */
    public boolean addSynonymName(String syn)
    {
        ArtistSynonym as = new ArtistSynonym();
        as.setName(syn);
        return addSynonym(as);
    }

    /**
     * Removes the specified synonym from this artist.
     *
     * @param syn the synonym to be removed
     * @return a flag whether the synonym could be removed
     */
    public boolean removeSynonym(ArtistSynonym syn)
    {
        if (syn == null || syn.getArtist() != this)
        {
            return false;
        }

        syn.setArtist(null);
        return synonyms.remove(syn);
    }

    /**
     * Removes the specified synonym name from this entity. If the synonym is
     * not found, this method has no effect.
     *
     * @param syn the synonym name to be removed
     * @return a flag whether the synonym was found and removed
     */
    public boolean removeSynonymName(String syn)
    {
        return removeSynonym(findSynonym(syn));
    }

    /**
     * Finds the synonym with the passed in synonym name. If it exists, the
     * corresponding {@link ArtistSynonym} is returned. Otherwise, result is
     * <b>null</b>.
     *
     * @param syn the synonym name to be searched
     * @return the {@link ArtistSynonym} with this name or <b>null</b>
     */
    public ArtistSynonym findSynonym(String syn)
    {
        return AbstractSynonym.findSynonym(getSynonyms(), syn);
    }

    /**
     * Compares this object with another one. Two artist are considered equal if
     * their names are equal (ignoring case) and they belong to the same user.
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
        if (!(obj instanceof ArtistEntity))
        {
            return false;
        }
        ArtistEntity c = (ArtistEntity) obj;
        return ObjectUtils.equalsIgnoreCase(getName(), c.getName())
                && ObjectUtils.equals(getUser(), c.getUser());
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
        result = ObjectUtils.hashIgnoreCase(getName(), result);
        result = ObjectUtils.hash(getUser(), result);
        return result;
    }

    /**
     * Returns a string representation of this object. This string contains the
     * properties defined for this instance.
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
     * Searches for an artist with the specified name. This method performs a
     * case-independent search for an artist's name for a given user. If a
     * matching artist is found, it is returned. Otherwise, result is
     * <b>null</b>. This method expects that only a single match can be found;
     * this is due to the way this application works. If multiple results are
     * found, only a single hit is returned.
     *
     * @param em the entity manager
     * @param user the user the artist belongs to
     * @param name the name to be searched for
     * @return the single matching artist or <b>null</b> if there is no match
     */
    public static ArtistEntity findByName(EntityManager em, User user,
            String name)
    {
        return queryArtist(em, QUERY_FIND_BY_NAME, user, name);
    }

    /**
     * Searches for an artist with a synonym matching the specified name. This
     * method works like {@link #findByName(EntityManager, User, String)}, but
     * the search is performed on the synonyms associated with artists.
     *
     * @param em the entity manager
     * @param user the user the artist belongs to
     * @param name the name to be searched for
     * @return the single matching artist or <b>null</b> if there is no match
     */
    public static ArtistEntity findBySynonym(EntityManager em, User user,
            String name)
    {
        return queryArtist(em, QUERY_FIND_BY_SYNONYM, user, name);
    }

    /**
     * Searches for an artist either directly by name or by the synonyms
     * associated with the artist. Using this method it is possible to find out
     * whether an artist exists with a specific name (case independent).
     *
     * @param em the entity manager
     * @param user the user the artist belongs to
     * @param name the name to be searched for
     * @return the single matching artist or <b>null</b> if there is no match
     */
    public static ArtistEntity findByNameOrSynonym(EntityManager em, User user,
            String name)
    {
        ArtistEntity result = findByName(em, user, name);
        if (result == null)
        {
            result = findBySynonym(em, user, name);
        }

        return result;
    }

    /**
     * Returns the search name of the artist. This property is updated whenever
     * the name is changed. It is addressed by queries. Basically, this is a
     * workaround for limits in the JPA implementation of the AppEngine.
     *
     * @return the search name of the artist
     */
    String getSearchName()
    {
        return searchName;
    }

    /**
     * Helper method for executing an artist query.
     *
     * @param em the entity manager
     * @param queryName the name of the query to be executed
     * @param user the user
     * @param name the name to be searched for
     * @return the single matching artist or <b>null</b> if there is no match
     */
    private static ArtistEntity queryArtist(EntityManager em, String queryName,
            User user, String name)
    {
        return (ArtistEntity) Finders.queryNamedEntity(em, queryName, user,
                name);
    }

    /**
     * Updates the search name field. This method is called whenever the
     * artist's name has changed.
     */
    private void updateSearchName()
    {
        searchName = EntityUtils.generateSearchString(name);
    }
}
