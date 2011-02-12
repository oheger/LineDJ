package de.oliver_heger.mediastore.server.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.easymock.EasyMock;
import org.junit.Test;

import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code Finders}. This class mainly tests exceptional
 * invocations. Actual execution of find methods is tested together with the
 * entity classes.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestFinders
{
    /** Constant for a named query. */
    private static final String QUERY_NAME = "testNamedQuery";

    /** Constant for the name of an entity. */
    private static final String ENTITY_NAME = "TestEntity";

    /** Constant for a parameter name. */
    private static final String PARAM = "testParam";

    /**
     * Tries to search for the songs of an artist without an entity manager.
     */
    @Test(expected = NullPointerException.class)
    public void testFindSongsByArtistNoEM()
    {
        Finders.findSongsByArtist(null, new ArtistEntity());
    }

    /**
     * Tries to search for the songs of an artist without providing the artist.
     */
    @Test
    public void testFindSongsByArtistNoArtist()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        try
        {
            Finders.findSongsByArtist(em, null);
            fail("Missing artist parameter not detected!");
        }
        catch (NullPointerException npex)
        {
            EasyMock.verify(em);
        }
    }

    /**
     * Tries to search for the songs of an album without an entity manager.
     */
    @Test(expected = NullPointerException.class)
    public void testFindSongsByAlbumNoEM()
    {
        Finders.findSongsByAlbum(null, new AlbumEntity());
    }

    /**
     * Tries to search for the songs of an album without providing the album.
     */
    @Test
    public void testFindSongsByAlbumNoAlbum()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        try
        {
            Finders.findSongsByAlbum(em, null);
            fail("Missing album parameter not detected!");
        }
        catch (NullPointerException npex)
        {
            EasyMock.verify(em);
        }
    }

    /**
     * Tries to find the albums of a list of songs without an entity manager.
     */
    @Test(expected = NullPointerException.class)
    public void testFindAlbumsForSongsNoEM()
    {
        Finders.findAlbumsForSongs(null, new HashSet<SongEntity>());
    }

    /**
     * Tries to find the albums of a list of songs if no list is provided.
     */
    @Test
    public void testFindAlbumsForSongsNoSongs()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        try
        {
            Finders.findAlbumsForSongs(em, null);
            fail("Missing songs parameter not detected!");
        }
        catch (NullPointerException npex)
        {
            EasyMock.verify(em);
        }
    }

    /**
     * Tests whether songs with no album ID are skipped when fetching the albums
     * for a list of songs.
     */
    @Test
    public void testFindAlbumsForSongsNoAlbumID()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        SongEntity song = new SongEntity();
        assertTrue("Got albums",
                Finders.findAlbumsForSongs(em, Collections.singleton(song))
                        .isEmpty());
        EasyMock.verify(em);
    }

    /**
     * Tests whether songs with no artist ID are skipped when fetching the
     * artists for a list of songs.
     */
    @Test
    public void testFindArtistsForSongsNoArtistID()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        SongEntity song = new SongEntity();
        assertTrue("Got artists",
                Finders.findArtistsForSongs(em, Collections.singleton(song))
                        .isEmpty());
        EasyMock.verify(em);
    }

    /**
     * Tries to find the albums of an artist without an entity manager.
     */
    @Test(expected = NullPointerException.class)
    public void testFindAlbumsForArtistNoEM()
    {
        Finders.findAlbumsForArtist(null, new ArtistEntity());
    }

    /**
     * Tries to find the albums of an artist without providing the artist.
     */
    @Test
    public void testFindAlbumsForArtistNoArtist()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        try
        {
            Finders.findAlbumsForArtist(em, null);
            fail("Missing artist parameter not detected!");
        }
        catch (NullPointerException npex)
        {
            EasyMock.verify(em);
        }
    }

    /**
     * Tries to find the artists of an album without an entity manager.
     */
    @Test(expected = NullPointerException.class)
    public void testFindArtistsForAlbumNoEM()
    {
        Finders.findArtistsForAlbum(null, new AlbumEntity());
    }

    /**
     * Tries to find the artists of an album without providing the album.
     */
    @Test
    public void testFindArtistsForAlbumNoAlbum()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        try
        {
            Finders.findArtistsForAlbum(em, null);
            fail("Missing album parameter not detected!");
        }
        catch (NullPointerException npex)
        {
            EasyMock.verify(em);
        }
    }

    /**
     * Helper method for setting up a query mock to be used for a test for a
     * named entity query.
     *
     * @param resultList the result of the query
     * @return the query mock
     */
    private Query initQueryMockForNamedEntityQuery(List<?> resultList)
    {
        Query query = EasyMock.createMock(Query.class);
        EasyMock.expect(
                query.setParameter("user", PersistenceTestHelper.getTestUser()))
                .andReturn(query);
        EasyMock.expect(
                query.setParameter("name",
                        ENTITY_NAME.toUpperCase(Locale.ENGLISH))).andReturn(
                query);
        EasyMock.expect(query.getResultList()).andReturn(resultList);
        return query;
    }

    /**
     * Tests whether an entity can be queried by name if there is a single
     * match.
     */
    @Test
    public void testQueryNamedEntitySingle()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        final Object entity = new Object();
        Query query =
                initQueryMockForNamedEntityQuery(Collections
                        .singletonList(entity));
        EasyMock.expect(em.createNamedQuery(QUERY_NAME)).andReturn(query);
        EasyMock.replay(em, query);
        assertSame(
                "Wrong result",
                entity,
                Finders.queryNamedEntity(em, QUERY_NAME,
                        PersistenceTestHelper.getTestUser(), ENTITY_NAME));
        EasyMock.verify(query, em);
    }

    /**
     * Tests whether an entity can be queried by name if there are multiple
     * matches.
     */
    @Test
    public void testQueryNamedEntityMultiple()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        final int matchCount = 4;
        List<Integer> results = new ArrayList<Integer>(matchCount);
        for (int i = 0; i < matchCount; i++)
        {
            results.add(i);
        }
        Query query = initQueryMockForNamedEntityQuery(results);
        EasyMock.expect(em.createNamedQuery(QUERY_NAME)).andReturn(query);
        EasyMock.replay(em, query);
        assertSame(
                "Wrong result",
                results.get(0),
                Finders.queryNamedEntity(em, QUERY_NAME,
                        PersistenceTestHelper.getTestUser(), ENTITY_NAME));
        EasyMock.verify(em, query);
    }

    /**
     * Tests queryNamedEntity() if there is no match.
     */
    @Test
    public void testQueryNamedEntityNoMatch()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        Query query = initQueryMockForNamedEntityQuery(new ArrayList<Object>());
        EasyMock.expect(em.createNamedQuery(QUERY_NAME)).andReturn(query);
        EasyMock.replay(em, query);
        assertNull(
                "Got a result",
                Finders.queryNamedEntity(em, QUERY_NAME,
                        PersistenceTestHelper.getTestUser(), ENTITY_NAME));
        EasyMock.verify(em, query);
    }

    /**
     * Generates a list with the values in the given range.
     *
     * @param from the start value
     * @param to the end value (excluding)
     * @return the list with all these values
     */
    private static List<Long> range(long from, long to)
    {
        List<Long> result = new ArrayList<Long>((int) (to - from + 1));
        for (long l = from; l < to; l++)
        {
            result.add(l);
        }
        return result;
    }

    /**
     * Creates a map with the given number of test query parameters.
     *
     * @param count the number of parameters
     * @return the parameters map
     */
    private static Map<String, Object> createParametersMap(int count)
    {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i < count; i++)
        {
            map.put(PARAM + i, i);
        }
        return map;
    }

    /**
     * Prepares the specified query mock to expect the given number of test
     * parameters.
     *
     * @param query the query mock
     * @param count the number of parameters
     */
    private static void expectParameters(Query query, int count)
    {
        for (int i = 0; i < count; i++)
        {
            EasyMock.expect(query.setParameter(PARAM + i, i)).andReturn(query);
        }
    }

    /**
     * Tests queryInCondition() if there is no need to split the query.
     */
    @Test
    public void testQueryInConditionBelowThreshold()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        Query query = EasyMock.createMock(Query.class);
        final int paramCount = 4;
        expectParameters(query, paramCount);
        List<Long> ids = range(0, 10);
        EasyMock.expect(query.setParameter(PARAM, ids)).andReturn(query);
        EasyMock.expect(query.getResultList()).andReturn(ids);
        EasyMock.expect(em.createNamedQuery(QUERY_NAME)).andReturn(query);
        EasyMock.replay(em, query);
        Map<String, Object> params = createParametersMap(paramCount);
        params.put(PARAM, ids);
        List<Long> expectedResult = new ArrayList<Long>(ids);
        assertEquals("Wrong result", expectedResult,
                Finders.queryInCondition(em, QUERY_NAME, params, PARAM));
        EasyMock.verify(em, query);
    }

    /**
     * Tests queryInCondition() if the query has to be executed multiple times.
     */
    @Test
    public void testQueryInConditionAboveThreshold()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        Query query = EasyMock.createMock(Query.class);
        EasyMock.expect(em.createNamedQuery(QUERY_NAME)).andReturn(query)
                .times(3);
        final int paramCount = 2;
        Map<String, Object> params = createParametersMap(paramCount);
        params.put(PARAM, range(0, 250));
        expectParameters(query, paramCount);
        List<Long> ids = range(0, 100);
        EasyMock.expect(query.setParameter(PARAM, ids)).andReturn(query);
        EasyMock.expect(query.getResultList()).andReturn(ids);
        expectParameters(query, paramCount);
        ids = range(100, 200);
        EasyMock.expect(query.setParameter(PARAM, ids)).andReturn(query);
        EasyMock.expect(query.getResultList()).andReturn(ids);
        expectParameters(query, paramCount);
        ids = range(200, 250);
        EasyMock.expect(query.setParameter(PARAM, ids)).andReturn(query);
        EasyMock.expect(query.getResultList()).andReturn(ids);
        EasyMock.replay(em, query);
        Set<Object> results =
                new HashSet<Object>(Finders.queryInCondition(em, QUERY_NAME,
                        params, PARAM));
        assertEquals("Wrong number of results", 250, results.size());
        for (Long id : range(0, 250))
        {
            assertTrue("Value not found: " + id, results.contains(id));
        }
        EasyMock.verify(em, query);
    }

    /**
     * Tests queryInCondition() if an empty collection is passed as parameter
     * for the in list. This has to be treated in a special way, otherwise an
     * exception is thrown by the persistence provider.
     */
    @Test
    public void testQueryInConditionEmptyList()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        Map<String, Object> params = createParametersMap(10);
        params.put(PARAM, range(0, 0));
        assertTrue("Got results",
                Finders.queryInCondition(em, QUERY_NAME, params, PARAM)
                        .isEmpty());
        EasyMock.verify(em);
    }

    /**
     * Tries to find songs for a null collection of albums.
     */
    @Test(expected = NullPointerException.class)
    public void testFindSongsByAlbumsNullAlbums()
    {
        EntityManager em = EasyMock.createNiceMock(EntityManager.class);
        Finders.findSongsByAlbums(em, null);
    }

    /**
     * Tests findSongsByAlbums() for an empty list of albums.
     */
    @Test
    public void testFindSongsByAlbumsNoAlbums()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.replay(em);
        List<AlbumEntity> albums = Collections.emptyList();
        assertTrue("Got songs", Finders.findSongsByAlbums(em, albums).isEmpty());
        EasyMock.verify(em);
    }
}
