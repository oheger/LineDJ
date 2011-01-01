package de.oliver_heger.mediastore.server.model;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.EntityManager;

import com.google.appengine.api.users.User;

import de.oliver_heger.mediastore.shared.model.Album;
import de.oliver_heger.mediastore.shared.model.Artist;
import de.oliver_heger.mediastore.shared.model.Song;

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
    /** Constant for the user parameter. */
    static final String PARAM_USER = "user";

    /** Constant for the name parameter. */
    static final String PARAM_NAME = "name";

    /** The parameter for the artist ID. */
    private static final String PARAM_ARTIST = "artID";

    /** The parameter for the album ID. */
    private static final String PARAM_ALBUM = "albumID";

    /** The part of a query for retrieving songs by an artist ID. */
    private static final String ARTIST_QUERY_SUFFIX = " from Song s "
            + "where s.artistID = :" + PARAM_ARTIST;

    /** The part of a query for retrieving songs by an album ID. */
    private static final String ALBUM_QUERY_SUFFIX = " from Song s "
            + "where s.albumID = :" + PARAM_ALBUM;

    /** The query string of the query for finding the songs of an artist. */
    private static final String QUERY_FIND_BY_ARTIST_DEF = "select s"
            + ARTIST_QUERY_SUFFIX;

    /** The query string of the query for finding the songs of an album. */
    private static final String QUERY_FIND_BY_ALBUM_DEF = "select s"
            + ALBUM_QUERY_SUFFIX;

    /** The query string of the query for finding song IDs of an artist. */
    private static final String QUERY_FIND_IDS_BY_ARTIST_DEF =
            "select s.albumID" + ARTIST_QUERY_SUFFIX;

    /** The query string of the query for finding song IDs of an album. */
    private static final String QUERY_FIND_IDS_BY_ALBUM_DEF =
            "select s.artistID" + ALBUM_QUERY_SUFFIX;

    /** The query string of the query for retrieving a set of artists. */
    private static final String QUERY_FIND_ARTISTS_DEF =
            "select a from Artist a " + "where a.id in (:" + PARAM_ARTIST + ")";

    /** The query string of the query for retrieving a set of albums. */
    private static final String QUERY_FIND_ALBUMS_DEF =
            "select a from Album a " + "where a.id in (:" + PARAM_ALBUM + ")";

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
     * @param art the {@code Artist} (must not be <b>null</b>)
     * @return a list with the songs of this artist
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<Song> findSongsByArtist(EntityManager em, Artist art)
    {
        Long artID = art.getId();
        @SuppressWarnings("unchecked")
        List<Song> songs =
                em.createQuery(QUERY_FIND_BY_ARTIST_DEF)
                        .setParameter(PARAM_ARTIST, artID).getResultList();
        return songs;
    }

    /**
     * Returns a list with the songs of the specified album.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param album the {@code Album} (must not be <b>null</b>)
     * @return a list with the songs of this album
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<Song> findSongsByAlbum(EntityManager em, Album album)
    {
        Long albumID = album.getId();
        @SuppressWarnings("unchecked")
        List<Song> songs =
                em.createQuery(QUERY_FIND_BY_ALBUM_DEF)
                        .setParameter(PARAM_ALBUM, albumID).getResultList();
        return songs;
    }

    /**
     * Returns a list of all albums referenced by the given songs.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param songs a collection with the songs
     * @return a list with all albums referenced by the songs
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<Album> findAlbumsForSongs(EntityManager em,
            Collection<? extends Song> songs)
    {
        Set<Long> albumIDs = new HashSet<Long>();
        for (Song s : songs)
        {
            albumIDs.add(s.getAlbumID());
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
    public static List<Album> findAlbumsForArtist(EntityManager em, Artist art)
    {
        Long artID = art.getId();
        @SuppressWarnings("unchecked")
        List<Long> albumIDs =
                em.createQuery(QUERY_FIND_IDS_BY_ARTIST_DEF)
                        .setParameter(PARAM_ARTIST, artID).getResultList();
        return findAlbumsByIDs(em, new HashSet<Long>(albumIDs));
    }

    /**
     * Returns a list of all artists referenced by the given songs.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param songs a collection with the songs
     * @return a list with all artists referenced by the songs
     * @throws NullPointerException if a required parameter is missing
     */
    public static List<Artist> findArtistsForSongs(EntityManager em,
            Collection<? extends Song> songs)
    {
        Set<Long> artistIDs = new HashSet<Long>();
        for (Song s : songs)
        {
            artistIDs.add(s.getArtistID());
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
    public static List<Artist> findArtistsForAlbum(EntityManager em, Album album)
    {
        Long albumID = album.getId();
        @SuppressWarnings("unchecked")
        List<Long> artistIDs =
                em.createQuery(QUERY_FIND_IDS_BY_ALBUM_DEF)
                        .setParameter(PARAM_ALBUM, albumID).getResultList();
        return findArtistsByIDs(em, new HashSet<Long>(artistIDs));
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
     * Returns the {@code Album} instances for the specified IDs.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param albumIDs a set with the IDs of the albums to retrieve
     * @return a list with the found albums
     * @throws NullPointerException if a required parameter is missing
     */
    private static List<Album> findAlbumsByIDs(EntityManager em,
            Set<Long> albumIDs)
    {
        @SuppressWarnings("unchecked")
        List<Album> albums =
                em.createQuery(QUERY_FIND_ALBUMS_DEF)
                        .setParameter(PARAM_ALBUM, albumIDs).getResultList();
        return albums;
    }

    /**
     * Returns the {@code Artist} instances of the specified IDs.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param artistIDs a set with the IDs of the artists to retrieve
     * @return a list with the found albums
     * @throws NullPointerException if a required parameter is missing
     */
    private static List<Artist> findArtistsByIDs(EntityManager em,
            Set<Long> artistIDs)
    {
        @SuppressWarnings("unchecked")
        List<Artist> artists =
                em.createQuery(QUERY_FIND_ARTISTS_DEF)
                        .setParameter(PARAM_ARTIST, artistIDs).getResultList();
        return artists;
    }
}
