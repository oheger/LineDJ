package de.oliver_heger.mediastore.server.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import com.google.appengine.api.users.User;

/**
 * <p>
 * A helper class defining a bunch of finder methods on the entity classes.
 * </p>
 * <p>
 * The methods provided by this class can be used to execute various predefined
 * queries on the model objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public final class Finders
{
    /** Constant for the ID parameter. */
    public static final String PARAM_ID = "id";

    /** Constant for the user parameter. */
    static final String PARAM_USER = "user";

    /** Constant for the name parameter. */
    static final String PARAM_NAME = "name";

    /**
     * Constant for the threshold for in conditions. If an in condition contains
     * more elements than this number, the query is split.
     */
    private static final int IN_THRESHOLD = 100;

    /**
     * Private constructor so that no instances can be created.
     */
    private Finders()
    {
    }

    /**
     * Returns a list with the songs of the specified artist.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param art the {@code ArtistEntity} (must not be <b>null</b>)
     * @return a list with the songs of this artist
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<SongEntity> findSongsByArtist(EntityManager em,
            ArtistEntity art)
    {
        return SongEntity.findByArtist(em, art);
    }

    /**
     * Returns a list with the songs of the specified album.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param art the {@code AlbumEntity} (must not be <b>null</b>)
     * @return a list with the songs of this album
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<SongEntity> findSongsByAlbum(EntityManager em,
            AlbumEntity album)
    {
        return SongEntity.findByAlbum(em, album);
    }

    /**
     * Returns the {@code AlbumEntity} instances for the specified IDs.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param albumIDs a set with the IDs of the albums to retrieve
     * @return a list with the found albums
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<AlbumEntity> findAlbumsByIDs(EntityManager em,
            Set<Long> albumIDs)
    {
        // the query guarantees that the correct type is returned
        @SuppressWarnings("unchecked")
        List<AlbumEntity> albums =
                (List<AlbumEntity>) queryByIDs(em,
                        AlbumEntity.QUERY_FIND_BY_IDS, albumIDs);
        return albums;
    }

    /**
     * Returns the {@code ArtistEntity} instances of the specified IDs.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param artistIDs a set with the IDs of the artists to retrieve
     * @return a list with the found albums
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<ArtistEntity> findArtistsByIDs(EntityManager em,
            Set<Long> artistIDs)
    {
        // the query guarantees that the correct type is returned
        @SuppressWarnings("unchecked")
        List<ArtistEntity> artists =
                (List<ArtistEntity>) queryByIDs(em,
                        ArtistEntity.QUERY_FIND_BY_IDS, artistIDs);
        return artists;
    }

    /**
     * Returns a list with all songs associated with one of the passed in
     * albums.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param albums the list with the albums (must not be <b>null</b>)
     * @return a list with the songs associated with these albums
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<SongEntity> findSongsByAlbums(EntityManager em,
            Collection<? extends AlbumEntity> albums)
    {
        List<Long> albumIDs = new ArrayList<Long>(albums.size());
        for (AlbumEntity e : albums)
        {
            albumIDs.add(e.getId());
        }

        Map<String, Object> params =
                Collections.singletonMap(SongEntity.PARAM_ALBUM,
                        (Object) albumIDs);
        // the query returns objects of the correct type
        @SuppressWarnings("unchecked")
        List<SongEntity> result =
                (List<SongEntity>) queryInCondition(em,
                        SongEntity.QUERY_FIND_BY_ALBUMLIST, params,
                        SongEntity.PARAM_ALBUM);
        return result;
    }

    /**
     * Returns a list of all albums referenced by the given songs.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param songs a collection with the songs
     * @return a list with all albums referenced by the songs
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<AlbumEntity> findAlbumsForSongs(EntityManager em,
            Collection<? extends SongEntity> songs)
    {
        Set<Long> albumIDs = new HashSet<Long>();
        for (SongEntity s : songs)
        {
            if (s.getAlbumID() != null)
            {
                albumIDs.add(s.getAlbumID());
            }
        }

        return findAlbumsByIDs(em, albumIDs);
    }

    /**
     * Returns a list with all albums containing songs of the specified artist.
     * This is a convenience method which first retrieves the songs of this
     * artist. Then all albums referenced by the songs are searched.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param art the {@code Artist} (must not be <b>null</b>)
     * @return a list with the albums of this artist
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<AlbumEntity> findAlbumsForArtist(EntityManager em,
            ArtistEntity art)
    {
        List<SongEntity> songs = findSongsByArtist(em, art);
        return findAlbumsForSongs(em, songs);
    }

    /**
     * Returns a list of all artists referenced by the given songs.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param songs a collection with the songs
     * @return a list with all artists referenced by the songs
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<ArtistEntity> findArtistsForSongs(EntityManager em,
            Collection<? extends SongEntity> songs)
    {
        Set<Long> artistIDs = new HashSet<Long>();
        for (SongEntity s : songs)
        {
            if (s.getArtistID() != null)
            {
                artistIDs.add(s.getArtistID());
            }
        }

        return findArtistsByIDs(em, artistIDs);
    }

    /**
     * Returns a list with all artists that appear on the specified album. This
     * is a convenience method which first retrieves the songs of this album.
     * Then all artists referenced by the songs are searched.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param album the {@code Album} (must not be <b>null</b>)
     * @return a list with the artists of this album
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<ArtistEntity> findArtistsForAlbum(EntityManager em,
            AlbumEntity album)
    {
        List<SongEntity> songs = findSongsByAlbum(em, album);
        return findArtistsForSongs(em, songs);
    }

    /**
     * Executes a query for an entity which is searched by its name. This method
     * executes a named query, fills in the parameters for the name and the
     * user, and handles the numbers of results correctly. It is expected that a
     * single result is retrieved as names should be unique. If no match is
     * found, result is <b>null</b>. If there are multiple results, a warning is
     * logged, and an arbitrary object from the result set is returned. (It can
     * happen that there are multiple results if there was a problem during a
     * merge operation.)
     *
     * @param em the entity manager
     * @param queryName the name of the query to be executed
     * @param user the user
     * @param name the name to be searched for
     * @return the single matching artist or <b>null</b> if there is no match
     */
    public static Object queryNamedEntity(EntityManager em, String queryName,
            User user, String name)
    {
        List<?> results =
                em.createNamedQuery(queryName)
                        .setParameter(PARAM_USER, user)
                        .setParameter(PARAM_NAME,
                                EntityUtils.generateSearchString(name))
                        .getResultList();
        if (results.isEmpty())
        {
            return null;
        }
        else
        {
            if (results.size() > 1)
            {
                Logger log = Logger.getLogger(Finders.class.getName());
                log.log(Level.WARNING, String.format(
                        "Got multiple results for query "
                                + "%s, name = %s, user = %s!", queryName, name,
                        String.valueOf(user)));
            }

            return results.get(0);
        }
    }

    /**
     * Executes a named query which contains an in condition. The problem with
     * in conditions is that the condition can become arbitrary complex; if the
     * elements in the parameter list of the condition exceed a certain number,
     * a database error is thrown. This method implements a safe way of
     * executing such queries: It checks the number of elements in the in
     * condition. If it is below a threshold, the query can be executed
     * directly. Otherwise, the query is executed multiple times with chunks of
     * the in condition. The results of the single queries are then combined.
     *
     * @param em the entity manager
     * @param queryName the name of the query to be executed
     * @param params a map with the query parameters
     * @param paramIn the name of the parameter for the in condition; the value
     *        must be a list
     * @return the result of the query
     */
    @SuppressWarnings("unchecked")
    // we don't have any type info at all
    public static List<?> queryInCondition(EntityManager em, String queryName,
            Map<String, Object> params, String paramIn)
    {
        Collection<?> inList = (Collection<?>) params.get(paramIn);
        if (inList.isEmpty())
        {
            return Collections.emptyList();
        }

        if (inList.size() <= IN_THRESHOLD)
        {
            Query query = em.createNamedQuery(queryName);
            initParameters(query, params);
            return query.getResultList();
        }

        Map<String, Object> paramsCopy = new HashMap<String, Object>(params);
        List<?> idList =
                (inList instanceof List) ? (List<?>) inList
                        : new ArrayList<Object>(inList);
        paramsCopy.put(paramIn, idList.subList(0, IN_THRESHOLD));
        List<?> result1 = queryInCondition(em, queryName, paramsCopy, paramIn);
        paramsCopy.put(paramIn, idList.subList(IN_THRESHOLD, inList.size()));
        List<?> result2 = queryInCondition(em, queryName, paramsCopy, paramIn);
        @SuppressWarnings("rawtypes")
        List result = new ArrayList(result1.size() + result2.size());
        result.addAll(result1);
        result.addAll(result2);
        return result;
    }

    /**
     * Executes a named query whose single parameter is a list of IDs. This is a
     * convenience method of invoking {@code queryInCondition()} manually.
     *
     * @param em the entity manager
     * @param queryName the name of the query
     * @param ids the collection with IDs
     * @return the results of the query
     * @throws NullPointerException if a required parameter is missing
     */
    private static List<?> queryByIDs(EntityManager em, String queryName,
            Collection<?> ids)
    {
        if (em == null)
        {
            throw new NullPointerException("EntityManager must not be null!");
        }
        Map<String, Object> params =
                Collections.singletonMap(PARAM_ID, (Object) ids);
        return queryInCondition(em, queryName, params, PARAM_ID);
    }

    /**
     * Helper method for setting query parameters.
     *
     * @param query the query
     * @param params the map with the parameters
     */
    private static void initParameters(Query query, Map<String, Object> params)
    {
        for (Map.Entry<String, Object> e : params.entrySet())
        {
            query.setParameter(e.getKey(), e.getValue());
        }
    }
}
