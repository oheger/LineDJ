package de.oliver_heger.mediastore.server.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.shared.model.Artist;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchIteratorImpl;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class of {@code MediaSearchServiceImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestMediaSearchServiceImpl
{
    /** An array with the names of test artists. */
    private static final String[] ARTIST_NAMES = {
            "AC/DC", "Adam Ant", "Bryan Adams", "Culture Club", "Dido",
            "Eddy Grant", "Evanescense", "Four Non Blonds", "Greenday",
            "Heroes del Silencio", "Indica", "Jekyl", "Kim Wild",
            "Lisa Standsfield", "Marillion", "Mike Oldfield", "Nightwish",
            "OMD", "Pearl Jam", "Queen", "REO Speedwagon", "Supertramp",
            "The Sisters of Mercy", "U2", "Van Canto", "Van Halen", "Vangelis",
            "Within Temptation", "Yellow", "ZZ Top"
    };

    /** Constant for a test client parameter. */
    private static final Serializable PARAM = Integer.valueOf(42);

    /** Constant for the number of test objects. */
    private static final int TEST_OBJ_COUNT = 8;

    /** Constant for the test chunk size. */
    private static final int CHUNK_SIZE = 10;

    /** The persistence test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    /** The service to be tested. */
    private MediaSearchServiceImpl service;

    @Before
    public void setUp() throws Exception
    {
        helper.setUp();
        service = new MediaSearchServiceImpl();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.tearDown();
    }

    /**
     * Creates a number of test artists.
     */
    private void createArtists()
    {
        List<String> artistNames =
                new ArrayList<String>(Arrays.asList(ARTIST_NAMES));
        Collections.shuffle(artistNames);
        for (String name : artistNames)
        {
            Artist art = new Artist();
            art.setName(name);
            art.setUserID(PersistenceTestHelper.USER);
            helper.persist(art);
        }
        // create some artists for a different user
        for (int i = 0; i < TEST_OBJ_COUNT; i++)
        {
            Artist art = new Artist();
            art.setName("Artist_" + PersistenceTestHelper.OTHER_USER + i);
            art.setUserID(PersistenceTestHelper.OTHER_USER);
            helper.persist(art);
        }
        helper.closeEM();
    }

    /**
     * Tests a search result of an artist query.
     *
     * @param artists the list with the artists found
     * @param start the start index
     * @param length the expected length (-1 for all)
     */
    private void checkArtistResult(List<Artist> artists, int start, int length)
    {
        int size = (length < 0) ? ARTIST_NAMES.length - start : length;
        assertEquals("Wrong number of results", size, artists.size());
        int idx = start;
        for (Artist art : artists)
        {
            assertEquals("Wrong artist", ARTIST_NAMES[idx], art.getName());
            idx++;
        }
    }

    /**
     * Tests a search result object produced by an artist query.
     *
     * @param result the result object
     * @param start the start index
     * @param length the expected length (-1 for all)
     */
    private void checkArtistResult(SearchResult<Artist> result, int start,
            int length)
    {
        checkArtistResult(result.getResults(), start, length);
        SearchIterator sit = result.getSearchIterator();
        assertFalse("More results", sit.hasNext());
        assertEquals("Wrong position", start, sit.getCurrentPosition());
        assertEquals("Wrong record count", ARTIST_NAMES.length,
                sit.getRecordCount());
    }

    /**
     * Tests the result of a search for artists with a search text.
     *
     * @param artists the list with the artists found
     * @param expNames the expected names
     */
    private void checkFoundArtists(List<Artist> artists, String... expNames)
    {
        assertEquals("Wrong number of artists: " + artists, expNames.length,
                artists.size());
        Set<String> names = new HashSet<String>();
        for (Artist art : artists)
        {
            names.add(art.getName());
        }
        for (String name : expNames)
        {
            assertTrue("Name not found: " + name, names.contains(name));
        }
    }

    /**
     * Tries to perform a search operation if no user is logged in.
     */
    @Test(expected = IllegalStateException.class)
    public void testSearchArtistsNotLoggedIn()
    {
        helper.getLocalServiceTestHelper().setEnvIsLoggedIn(false);
        service.searchArtists(new MediaSearchParameters(), null);
    }

    /**
     * Tries to search for artists with a null parameter object.
     */
    @Test(expected = NullPointerException.class)
    public void testSearchArtistsNull()
    {
        service.searchArtists(null, null);
    }

    /**
     * Tests whether all artists can be found and are correctly ordered.
     */
    @Test
    public void testSearchArtistsAll()
    {
        createArtists();
        MediaSearchParameters params = new MediaSearchParameters();
        SearchResult<Artist> result = service.searchArtists(params, null);
        checkArtistResult(result, 0, -1);
        assertSame("Wrong parameters", params, result.getSearchParameters());
        SearchIterator sit = result.getSearchIterator();
        assertFalse("Got more results", sit.hasNext());
        assertEquals("Wrong current page", Integer.valueOf(0),
                sit.getCurrentPage());
        assertEquals("Wrong page count", Integer.valueOf(1), sit.getPageCount());
    }

    /**
     * Tests whether the iterator object is ignored when no search text is
     * provided.
     */
    @Test
    public void testSearchArtistsAllWithIterator()
    {
        createArtists();
        SearchIteratorImpl sit = new SearchIteratorImpl();
        sit.setSearchKey(10);
        SearchResult<Artist> result =
                service.searchArtists(new MediaSearchParameters(), sit);
        checkArtistResult(result, 0, -1);
    }

    /**
     * Tests whether artists can be retrieved starting with a given index.
     */
    @Test
    public void testSearchArtistsOffset()
    {
        final int startPos = 10;
        createArtists();
        MediaSearchParameters params = new MediaSearchParameters();
        params.setFirstResult(startPos);
        SearchResult<Artist> result = service.searchArtists(params, null);
        checkArtistResult(result, startPos, -1);
        SearchIterator sit = result.getSearchIterator();
        assertFalse("Got more results", sit.hasNext());
        assertEquals("Wrong current page", Integer.valueOf(0),
                sit.getCurrentPage());
        assertEquals("Wrong page count", Integer.valueOf(1), sit.getPageCount());
    }

    /**
     * Tests whether a specific page of artists (start index and length) can be
     * obtained.
     */
    @Test
    public void testSearchArtistsPaged()
    {
        final int startPos = 15;
        final int pageSize = 10;
        createArtists();
        MediaSearchParameters params = new MediaSearchParameters();
        params.setFirstResult(startPos);
        params.setMaxResults(pageSize);
        SearchResult<Artist> result = service.searchArtists(params, null);
        checkArtistResult(result, startPos, pageSize);
        SearchIterator sit = result.getSearchIterator();
        assertFalse("Got more results", sit.hasNext());
        assertEquals("Wrong current page", Integer.valueOf(1),
                sit.getCurrentPage());
        int pageCount = ARTIST_NAMES.length / pageSize;
        if (ARTIST_NAMES.length % pageSize != 0)
        {
            pageCount++;
        }
        assertEquals("Wrong page count", Integer.valueOf(pageCount),
                sit.getPageCount());
    }

    /**
     * Tests a search for all artists if there is no data.
     */
    @Test
    public void testSearchArtistsAllNoData()
    {
        MediaSearchParameters params = new MediaSearchParameters();
        SearchResult<Artist> result = service.searchArtists(params, null);
        assertTrue("Got results", result.getResults().isEmpty());
        assertEquals("Wrong total size", 0, result.getSearchIterator()
                .getRecordCount());
        assertFalse("More elements", result.getSearchIterator().hasNext());
    }

    /**
     * Tests the default chunk size.
     */
    @Test
    public void testGetChunkSizeDefault()
    {
        assertEquals("Wrong default chunk size", 50, service.getChunkSize());
    }

    /**
     * Tests whether the chunk size can be changed.
     */
    @Test
    public void testSetChunkSize()
    {
        final int newSize = 100;
        service.setChunkSize(newSize);
        assertEquals("Wrong chunk size", newSize, service.getChunkSize());
    }

    /**
     * Tries to test the search iterator if it is of an invalid type.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testIsNewSearchInvalidIterator()
    {
        service.isNewSearch(EasyMock.createNiceMock(SearchIterator.class));
    }

    /**
     * Tests whether a search iterator object without a search key is treated
     * correctly.
     */
    @Test
    public void testIsNewSearchNoSearchKey()
    {
        SearchIteratorImpl it = new SearchIteratorImpl();
        assertTrue("Not a new search", service.isNewSearch(it));
    }

    /**
     * Tests a search for artists in multiple chunks.
     */
    @Test
    public void testSearchArtistsInChunks()
    {
        createArtists();
        service.setChunkSize(CHUNK_SIZE);
        List<Artist> foundArtists = new ArrayList<Artist>();
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("van");
        params.setClientParameter(PARAM);
        SearchResult<Artist> result = service.searchArtists(params, null);
        int idx = 0;
        boolean moreResults = true;
        do
        {
            SearchIterator it = result.getSearchIterator();
            assertEquals("Wrong total count", ARTIST_NAMES.length,
                    it.getRecordCount());
            assertEquals("Wrong position", idx * CHUNK_SIZE,
                    it.getCurrentPosition());
            assertEquals("Wrong parameter", params,
                    result.getSearchParameters());
            foundArtists.addAll(result.getResults());
            if (!it.hasNext())
            {
                moreResults = false;
            }
            else
            {
                result = service.searchArtists(params, it);
            }
            idx++;
        } while (moreResults);
        checkFoundArtists(foundArtists, "Evanescense", "Van Halen",
                "Van Canto", "Vangelis");
    }

    /**
     * Tests whether a search limit is taken into account.
     */
    @Test
    public void testSearchArtistsWithLimit()
    {
        createArtists();
        service.setChunkSize(2 * ARTIST_NAMES.length);
        final int limit = 2;
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("van");
        params.setMaxResults(limit);
        SearchResult<Artist> result = service.searchArtists(params, null);
        assertTrue("No more records to search", result.getSearchIterator()
                .hasNext());
        assertEquals("Limit not taken into account", limit, result.getResults()
                .size());
    }

    /**
     * Tests a search that does not find any results.
     */
    @Test
    public void testSearchArtistsNoHits()
    {
        createArtists();
        service.setChunkSize(CHUNK_SIZE);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("thisSearchHasNoResults!");
        SearchIterator it = null;
        while (it == null || it.hasNext())
        {
            SearchResult<Artist> result = service.searchArtists(params, it);
            assertTrue("Got results", result.getResults().isEmpty());
            it = result.getSearchIterator();
        }
    }

    /**
     * Tests a search operation if no data is available.
     */
    @Test
    public void testSearchArtistsNoData()
    {
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("Van");
        SearchResult<Artist> result = service.searchArtists(params, null);
        assertTrue("Got results", result.getResults().isEmpty());
        assertFalse("More chunks", result.getSearchIterator().hasNext());
    }
}
