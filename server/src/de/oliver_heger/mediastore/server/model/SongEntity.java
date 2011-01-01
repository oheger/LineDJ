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

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.users.User;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An entity object representing a song.
 * </p>
 * <p>
 * In addition to storing some meta data, a {@code SongEntity} object links the
 * entities {@link ArtistEntity} and {@link AlbumEntity} together. Both are
 * referenced. Specialized find methods are defined for retrieving all songs of
 * an artist and all songs that belong to an album. Because of restrictions of
 * the persistence provided by Google AppEngine no JPA relations are defined,
 * but the primary keys are stored directly.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@Entity
@NamedQueries({
        @NamedQuery(name = SongEntity.QUERY_FIND_BY_NAME, query = SongEntity.QUERY_FIND_BY_NAME_DEF),
        @NamedQuery(name = SongEntity.QUERY_FIND_BY_SYNONYM, query = SongEntity.QUERY_FIND_BY_SYNONYM_DEF)
})
public class SongEntity implements Serializable
{
    /** Constant for the prefix for song queries. */
    static final String SONG_QUERY_PREFIX =
            "de.oliver_heger.mediastore.server.model.SongEntity.";

    /** Constant for the name of the query for finding a song by name. */
    static final String QUERY_FIND_BY_NAME = SONG_QUERY_PREFIX
            + "QUERY_FIND_BY_NAME";

    /** Constant for the definition of the query for finding a song by name. */
    static final String QUERY_FIND_BY_NAME_DEF = "select s from SongEntity s "
            + "where s.user = :" + Finders.PARAM_USER + " and s.searchName = :"
            + Finders.PARAM_NAME;

    /** Constant for the name of the query for finding a song by synonym. */
    static final String QUERY_FIND_BY_SYNONYM = SONG_QUERY_PREFIX
            + "QUERY_FIND_BY_SYNONYM";

    /**
     * Constant for the definition of the query for finding a song by synonym.
     */
    static final String QUERY_FIND_BY_SYNONYM_DEF = "select song "
            + "from SongSynonym syn " + "where syn.user = :"
            + Finders.PARAM_USER + " and syn.searchName = :"
            + Finders.PARAM_NAME;

    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101231L;

    /** Stores the primary key of this song entity. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Key id;

    /** The user this song belongs to. */
    private User user;

    /** The name of the song. */
    private String name;

    /** The search name (used for queries). */
    private String searchName;

    /** The date when this song was created. */
    private Date creationDate;

    /** The duration in milliseconds. */
    private Long duration;

    /** The inception year. */
    private Integer inceptionYear;

    /** Stores the ID performing artist. */
    private Long artistID;

    /** Stores the track number (if this song belongs to an album). */
    private Integer trackNo;

    /** Stores the number how often this song has been played so far. */
    private int playCount;

    /** A set with the synonyms of this song. */
    @OneToMany(mappedBy = "song", cascade = CascadeType.ALL)
    private Set<SongSynonym> synonyms = new HashSet<SongSynonym>();

    /**
     * Creates a new instance of {@code SongEntity}.
     */
    public SongEntity()
    {
        creationDate = new Date();
    }

    /**
     * Returns the primary key of this entity.
     *
     * @return the primary key of this song
     */
    public Key getId()
    {
        return id;
    }

    /**
     * Returns the user this song belongs to.
     *
     * @return the owning user
     */
    public User getUser()
    {
        return user;
    }

    /**
     * Sets the user this song belongs to.
     *
     * @param user the owning user
     */
    public void setUser(User user)
    {
        this.user = user;
    }

    /**
     * Returns the name of this song.
     *
     * @return the song name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets the name of this song.
     *
     * @param name the song name
     */
    public void setName(String name)
    {
        this.name = name;
        searchName = EntityUtils.generateSearchString(name);
    }

    /**
     * Returns the duration of this song in milliseconds.
     *
     * @return the song duration
     */
    public Long getDuration()
    {
        return duration;
    }

    /**
     * Sets the duration of this song in milliseconds.
     *
     * @param duration the duration
     */
    public void setDuration(Long duration)
    {
        this.duration = duration;
    }

    /**
     * Returns the inception year of this song.
     *
     * @return the inception year
     */
    public Integer getInceptionYear()
    {
        return inceptionYear;
    }

    /**
     * Sets the inception year of this song.
     *
     * @param inceptionYear the inception year
     */
    public void setInceptionYear(Integer inceptionYear)
    {
        this.inceptionYear = inceptionYear;
    }

    /**
     * Returns the ID of the artist who performed this song. This may be
     * <b>null</b> if the artist is unknown.
     *
     * @return the artist of this song
     */
    public Long getArtistID()
    {
        return artistID;
    }

    /**
     * Sets the ID of the artist who performed this song.
     *
     * @param artist the artist of this song
     */
    public void setArtistID(Long artist)
    {
        this.artistID = artist;
    }

    /**
     * Returns the track number if this song belongs to an album.
     *
     * @return the track number
     */
    public Integer getTrackNo()
    {
        return trackNo;
    }

    /**
     * Sets the track number of this song.
     *
     * @param trackNo the track number
     */
    public void setTrackNo(Integer trackNo)
    {
        this.trackNo = trackNo;
    }

    /**
     * Returns the number of times this song has been played.
     *
     * @return the play count
     */
    public int getPlayCount()
    {
        return playCount;
    }

    /**
     * Sets the number of times this song has been played.
     *
     * @param playCount the play count
     */
    public void setPlayCount(int playCount)
    {
        this.playCount = playCount;
    }

    /**
     * Returns the date when this entity has been created.
     *
     * @return the creation date
     */
    public Date getCreationDate()
    {
        return creationDate;
    }

    /**
     * Sets the date when this entity has been created.
     *
     * @param creationDate the creation date
     */
    public void setCreationDate(Date creationDate)
    {
        this.creationDate = creationDate;
    }

    /**
     * Returns the synonyms of this song.
     *
     * @return a set with the synonyms of this song
     */
    public Set<SongSynonym> getSynonyms()
    {
        return synonyms;
    }

    /**
     * Sets the synonyms of this song.
     *
     * @param synonyms a set with the synonyms of this song
     */
    public void setSynonyms(Set<SongSynonym> synonyms)
    {
        this.synonyms = synonyms;
    }

    /**
     * Adds the specified synonym to this song if it does not yet exist.
     *
     * @param syn the synonym to be added
     * @return a flag whether the synonym was added
     * @throws NullPointerException if the synonym is <b>null</b> or does not
     *         have a name
     */
    public boolean addSynonym(SongSynonym syn)
    {
        AbstractSynonym.checkSynonym(syn);
        syn.setUser(getUser());
        syn.setSong(this);

        if (synonyms.add(syn))
        {
            return true;
        }
        else
        {
            syn.setSong(null);
            return false;
        }
    }

    /**
     * Adds the specified synonym name to this song if it does not yet exist.
     *
     * @param syn the synonym name to be added
     * @return a flag whether the synonym name was added
     * @throws NullPointerException if the name is <b>null</b>
     */
    public boolean addSynonymName(String syn)
    {
        SongSynonym ssyn = new SongSynonym();
        ssyn.setName(syn);
        return addSynonym(ssyn);
    }

    /**
     * Removes the specified synonym from this song if it can be found.
     *
     * @param syn the synonym to be removed
     * @return a flag whether the synonym could be removed
     */
    public boolean removeSynonym(SongSynonym syn)
    {
        if (syn == null || syn.getSong() != this)
        {
            return false;
        }

        syn.setSong(null);
        return synonyms.remove(syn);
    }

    /**
     * Removes the specified synonym name from this song if it can be found.
     *
     * @param syn the synonym name to be removed
     * @return a flag whether the synonym could be removed
     */
    public boolean removeSynonymName(String syn)
    {
        return removeSynonym(findSynonym(syn));
    }

    /**
     * Returns the {@link SongSynonym} object with the specified name if it can
     * be found.
     *
     * @param syn the synonym name
     * @return the corresponding synonym object or <b>null</b> if there is no
     *         such synonym name
     */
    public SongSynonym findSynonym(String syn)
    {
        return AbstractSynonym.findSynonym(synonyms, syn);
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
        result = ObjectUtils.hash(getDuration(), result);
        result = ObjectUtils.hash(getArtistID(), result);
        result = ObjectUtils.hash(getUser(), result);

        return result;
    }

    /**
     * Compares this object with another one. Two objects are considered equals
     * if the following properties match: name, duration, artist, user.
     *
     * @param obj the object to compare to
     * @return a flag whether these objects are equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (obj == this)
        {
            return true;
        }
        if (!(obj instanceof SongEntity))
        {
            return false;
        }

        SongEntity c = (SongEntity) obj;
        return ObjectUtils.equals(getSearchName(), c.getSearchName())
                && ObjectUtils.equals(getDuration(), c.getDuration())
                && ObjectUtils.equals(getUser(), c.getUser())
                && ObjectUtils.equals(getArtistID(), c.getArtistID());
    }

    /**
     * Returns a string representation of this object. This string contains the
     * value of some of the most important properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "name", getName(), true);
        ObjectUtils.appendToStringField(buf, "artistID", getArtistID(), false);
        if (getDuration() != null)
        {
            ObjectUtils.appendToStringField(buf, "duration", getDuration()
                    + " ms", false);
        }
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }

    /**
     * Searches for an entity with the specified name. This method expects that
     * song names are unique for a given user. If a song with the specified name
     * (ignoring case) is found, it is returned. Otherwise, result is
     * <b>null</b>.
     *
     * @param em the entity manager
     * @param user the user
     * @param name the name of the song
     * @return the found entity or <b>null</b>
     */
    public static SongEntity findByName(EntityManager em, User user, String name)
    {
        return querySong(em, QUERY_FIND_BY_NAME, user, name);
    }

    /**
     * Searches for an entity with the specified synonym name. Works like
     * {@link #findByName(EntityManager, User, String)}, but a case-independent
     * search is performed in the synonym names.
     *
     * @param em the entity manager
     * @param user the user
     * @param name the synonym name of the song
     * @return the found entity or <b>null</b>
     */
    public static SongEntity findBySynonym(EntityManager em, User user,
            String name)
    {
        return querySong(em, QUERY_FIND_BY_SYNONYM, user, name);
    }

    /**
     * Searches for a song either directly by name or by the associated
     * synonyms. This method combines the methods
     * {@link #findByName(EntityManager, User, String)} and
     * {@link #findBySynonym(EntityManager, User, String)}.
     *
     * @param em the entity manager
     * @param user the user
     * @param name the name of the song to be searched for
     * @return the found entity or <b>null</b>
     */
    public static SongEntity findByNameOrSynonym(EntityManager em, User user,
            String name)
    {
        SongEntity result = findByName(em, user, name);
        if (result == null)
        {
            result = findBySynonym(em, user, name);
        }

        return result;
    }

    /**
     * Returns the search name of this song entity. The search name is used by
     * database queries. It is updated automatically whenever the regular name
     * is changed.
     *
     * @return the search name of this song
     */
    String getSearchName()
    {
        return searchName;
    }

    /**
     * Helper method for performing a query for a single song entity.
     *
     * @param em the entity manager
     * @param queryName the name of the query to be issued
     * @param user the user
     * @param name the name of the song
     * @return the entity found
     */
    private static SongEntity querySong(EntityManager em, String queryName,
            User user, String name)
    {
        return (SongEntity) Finders.queryNamedEntity(em, queryName, user, name);
    }
}
