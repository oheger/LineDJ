package de.oliver_heger.mediastore.localstore.impl;

import java.util.List;
import java.util.Locale;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.apache.commons.lang3.StringUtils;

import de.oliver_heger.mediastore.localstore.model.AlbumEntity;
import de.oliver_heger.mediastore.localstore.model.ArtistEntity;
import de.oliver_heger.mediastore.localstore.model.SongEntity;

/**
 * <p>
 * A helper class with some frequently used find methods on the entities of this
 * project.
 * </p>
 * <p>
 * This utility class provides some specialized find() methods which are used by
 * other services.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
final class Finders
{
    /**
     * Private constructor so that no instances can be created.
     */
    private Finders()
    {
    }

    /**
     * Searches for the artist with the given name. Result is <b>null</b> if no
     * such entity is found.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param name the name of the artist in question
     * @return the found entity or <b>null</b>
     * @throws NullPointerException if the {@code EntityManager} is <b>null</b>
     */
    public static ArtistEntity findArtist(EntityManager em, String name)
    {
        return executeFindEntityQuery(addNameParameter(
                em.createNamedQuery(ArtistEntity.QUERY_FIND_BY_NAME),
                ArtistEntity.PARAM_NAME, name));
    }

    /**
     * Searches for an album entity with the given parameters. Result is
     * <b>null</b> if no such entity is found.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param name the name of the album in question
     * @param inceptionYear the inception year of the album
     * @return the found entity or <b>null</b>
     * @throws NullPointerException if the {@code EntityManager} is <b>null</b>
     */
    public static AlbumEntity findAlbum(EntityManager em, String name,
            Integer inceptionYear)
    {
        return executeFindEntityQuery(addNameParameter(
                em.createNamedQuery(AlbumEntity.QUERY_FIND_SPECIFIC),
                AlbumEntity.PARAM_NAME, name).setParameter(
                AlbumEntity.PARAM_YEAR, inceptionYear));
    }

    /**
     * Searches for a song entity with the given parameters. Result is
     * <b>null</b> if no such entity is found.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param name the name of the song in question
     * @param duration the duration of the song (in seconds)
     * @param artName the name of the associated artist (can be <b>null</b>
     *        meaning that the song does not have an artist associated)
     * @return the found entity or <b>null</b>
     * @throws NullPointerException if the {@code EntityManager} is <b>null</b>
     */
    public static SongEntity findSong(EntityManager em, String name,
            Integer duration, String artName)
    {
        if (artName != null)
        {
            return executeFindEntityQuery(addNameParameter(
                    addNameParameter(
                            em.createNamedQuery(SongEntity.QUERY_FIND_SPECIFIC_WITH_ARTIST),
                            SongEntity.PARAM_NAME, name),
                    SongEntity.PARAM_ARTIST, artName).setParameter(
                    SongEntity.PARAM_DURATION, duration));
        }
        else
        {
            return executeFindEntityQuery(addNameParameter(
                    em.createNamedQuery(SongEntity.QUERY_FIND_SPECIFIC_NO_ARTIST),
                    SongEntity.PARAM_NAME, name).setParameter(
                    SongEntity.PARAM_DURATION, duration));
        }
    }

    /**
     * Searches for songs to be synchronized with the server. This method
     * executes a query which selects songs with a current play count greater
     * than 0. These are the songs that have been played since the last sync
     * operation.
     *
     * @param em the {@code EntityManager} (must not be <b>null</b>)
     * @param limit the limit of the results to be returned (<b>null</b> means
     *        that there is no restriction)
     * @return a list with the song entities that should be synchronized
     * @throws NullPointerException if the {@code EntityManager} is <b>null</b>
     */
    public static List<SongEntity> findSongsToSync(EntityManager em,
            Integer limit)
    {
        Query query = em.createNamedQuery(SongEntity.QUERY_FIND_SONGS_TO_SYNC);
        if (limit != null)
        {
            query.setMaxResults(limit.intValue());
        }

        @SuppressWarnings("unchecked")
        List<SongEntity> result = query.getResultList();
        return result;
    }

    /**
     * Executes a query for a specific entity. The query can return a single
     * object or none.
     *
     * @param <E> the type of the result object
     * @param query the query to be executed
     * @return the found result object or <b>null</b> if there is no match
     */
    private static <E> E executeFindEntityQuery(Query query)
    {
        // ok, as the query should be compatible with the type
        @SuppressWarnings("unchecked")
        List<E> result = query.getResultList();
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Helper method for adding a parameter to the specified query which
     * represents an entity name. Names are case insensitive, therefore these
     * parameters are treated specially in queries.
     *
     * @param query the query
     * @param param the parameter name
     * @param value the value of the parameter
     * @return the query for enabling method chaining
     */
    private static Query addNameParameter(Query query, String param,
            String value)
    {
        return query.setParameter(param,
                StringUtils.upperCase(value, Locale.ENGLISH));
    }
}
