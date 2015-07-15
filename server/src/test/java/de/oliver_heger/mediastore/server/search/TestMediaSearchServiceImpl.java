package de.oliver_heger.mediastore.server.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.api.users.User;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.convert.EntityConverter;
import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.OrderDef;
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

    /** An array with some test songs. */
    private static final String[] SONG_NAMES = {
            "Thunderstrike", "House of the rising sun", "Run to you",
            "Do you really want to heart me?", "Thank you", "Electric Avenue",
            "Come to live", "What's going on?", "American idiot"
    };

    /** An array with some test albums. */
    private static final String[] ALBUM_NAMES = {
            "Back in Black", "Best of the Animals", "Best of Bryan Adams",
            "Kissing to be clever", "Dido & PlemPleminem", "Eddy's Greatests",
            "Open Door", "Classics", "American Idiot", "Stoosh"
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
    private MediaSearchServiceTestImpl service;

    @Before
    public void setUp() throws Exception
    {
        helper.setUp();
        service = new MediaSearchServiceTestImpl();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.tearDown();
    }

    /**
     * Creates a number of test artists.
     *
     * @return a map with the names of the entities created by this method and
     *         their ID values
     */
    private Map<String, Long> createArtists()
    {
        Map<String, Long> result = new HashMap<String, Long>();
        List<String> artistNames =
                new ArrayList<String>(Arrays.asList(ARTIST_NAMES));
        Collections.shuffle(artistNames);
        for (String name : artistNames)
        {
            ArtistEntity art = new ArtistEntity();
            art.setName(name);
            art.setUser(PersistenceTestHelper.getTestUser());
            helper.persist(art);
            result.put(name, art.getId());
        }
        // create some artists for a different user
        User usrOther =
                PersistenceTestHelper.getUser(PersistenceTestHelper.OTHER_USER);
        for (int i = 0; i < TEST_OBJ_COUNT; i++)
        {
            ArtistEntity art = new ArtistEntity();
            art.setName("Artist_" + PersistenceTestHelper.OTHER_USER + i);
            art.setUser(usrOther);
            helper.persist(art);
        }
        helper.closeEM();
        return result;
    }

    /**
     * Creates a number of test albums.
     *
     * @return a map with the names of the entities created by this method and
     *         the newly created entities
     */
    private Map<String, AlbumEntity> createAlbums()
    {
        Map<String, AlbumEntity> result =
                new LinkedHashMap<String, AlbumEntity>();
        List<String> albumNames =
                new ArrayList<String>(Arrays.asList(ALBUM_NAMES));
        Collections.shuffle(albumNames);
        for (String name : albumNames)
        {
            AlbumEntity album = new AlbumEntity();
            album.setName(name);
            album.setUser(PersistenceTestHelper.getTestUser());
            helper.persist(album);
            result.put(name, album);
        }
        // create some albums for a different user
        // create some artists for a different user
        User usrOther =
                PersistenceTestHelper.getUser(PersistenceTestHelper.OTHER_USER);
        for (int i = 0; i < TEST_OBJ_COUNT; i++)
        {
            AlbumEntity album = new AlbumEntity();
            album.setName("Album_" + PersistenceTestHelper.OTHER_USER + i);
            album.setUser(usrOther);
            helper.persist(album);
        }
        helper.closeEM();
        return result;
    }

    /**
     * Creates a number of test songs. The songs are associated with artists and
     * albums (based on their index in the array with names).
     *
     * @param persist a flag whether the entities should be persisted
     * @return the entities created by this method
     */
    private List<SongEntity> createSongs(boolean persist)
    {
        Map<String, Long> artists = createArtists();
        Map<String, AlbumEntity> albums = createAlbums();
        return createSongs(persist, artists, albums);
    }

    /**
     * Creates a number of test songs and associates them with the given artists
     * and albums.
     *
     * @param persist a flag whether the entities should be persisted
     * @param artists a map with data about artists
     * @param albums a map with data about albums
     * @return the entities created by this method
     */
    private List<SongEntity> createSongs(boolean persist,
            Map<String, Long> artists, Map<String, AlbumEntity> albums)
    {
        List<SongEntity> songs = new ArrayList<SongEntity>(SONG_NAMES.length);
        for (int i = 0; i < SONG_NAMES.length; i++)
        {
            SongEntity song = new SongEntity();
            song.setName(SONG_NAMES[i]);
            song.setPlayCount(i);
            song.setInceptionYear(1970 + i);
            song.setDuration((60 + i) * 1000L);
            song.setArtistID(artists.get(ARTIST_NAMES[i]));
            AlbumEntity alb = albums.get(ALBUM_NAMES[i]);
            if (alb != null)
            {
                song.setAlbumID(alb.getId());
            }
            song.setUser(PersistenceTestHelper.getTestUser());
            songs.add(song);
            if (persist)
            {
                helper.persist(song);
            }
        }
        return songs;
    }

    /**
     * Tests a search result of an artist query.
     *
     * @param artists the list with the artists found
     * @param start the start index
     * @param length the expected length (-1 for all)
     */
    private void checkArtistResult(List<? extends ArtistInfo> artists,
            int start, int length)
    {
        int size = (length < 0) ? ARTIST_NAMES.length - start : length;
        assertEquals("Wrong number of results", size, artists.size());
        int idx = start;
        for (ArtistInfo art : artists)
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
    private void checkArtistResult(SearchResult<? extends ArtistInfo> result,
            int start, int length)
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
    private void checkFoundArtists(List<ArtistInfo> artists, String... expNames)
    {
        assertEquals("Wrong number of artists: " + artists, expNames.length,
                artists.size());
        Set<String> names = new HashSet<String>();
        for (ArtistInfo art : artists)
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
        SearchResult<ArtistInfo> result = service.searchArtists(params, null);
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
        sit.setCurrentPosition(100);
        SearchResult<ArtistInfo> result =
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
        SearchResult<ArtistInfo> result = service.searchArtists(params, null);
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
        SearchResult<ArtistInfo> result = service.searchArtists(params, null);
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
        SearchResult<ArtistInfo> result = service.searchArtists(params, null);
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
     * Tests whether a search iterator object with a current position of 0 is
     * treated correctly.
     */
    @Test
    public void testIsNewSearchPos0()
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
        List<ArtistInfo> foundArtists = new ArrayList<ArtistInfo>();
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("van");
        params.setClientParameter(PARAM);
        SearchResult<ArtistInfo> result = service.searchArtists(params, null);
        int idx = 0;
        boolean moreResults = true;
        do
        {
            SearchIterator it = result.getSearchIterator();
            assertEquals("Wrong total count", ARTIST_NAMES.length,
                    it.getRecordCount());
            assertEquals("Wrong position",
                    Math.min((idx + 1) * CHUNK_SIZE, ARTIST_NAMES.length),
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
        SearchResult<ArtistInfo> result = service.searchArtists(params, null);
        assertTrue("No more records to search", result.getSearchIterator()
                .hasNext());
        assertEquals("Limit not taken into account", limit, result.getResults()
                .size());
        assertTrue("Wrong position: "
                + result.getSearchIterator().getCurrentPosition(), result
                .getSearchIterator().getCurrentPosition() < ARTIST_NAMES.length);
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
            SearchResult<ArtistInfo> result = service.searchArtists(params, it);
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
        SearchResult<ArtistInfo> result = service.searchArtists(params, null);
        assertTrue("Got results", result.getResults().isEmpty());
        assertFalse("More chunks", result.getSearchIterator().hasNext());
    }

    /**
     * Tests whether a chunk search for artists really retrieves all entities.
     */
    @Test
    public void testSearchArtistsInChunkAll()
    {
        createArtists();
        service.setChunkSize(CHUNK_SIZE);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("test");
        service.mockArtistFilter(params);
        List<ArtistInfo> foundArtists = new ArrayList<ArtistInfo>();
        SearchResult<ArtistInfo> result = service.searchArtists(params, null);
        boolean hasMore;
        do
        {
            foundArtists.addAll(result.getResults());
            hasMore = result.getSearchIterator().hasNext();
            if (hasMore)
            {
                result =
                        service.searchArtists(params,
                                result.getSearchIterator());
            }
        } while (hasMore);
        assertEquals("Wrong number of found artists", ARTIST_NAMES.length,
                foundArtists.size());
    }

    /**
     * Tests a chunk-based search for songs.
     */
    @Test
    public void testSearchSongsChunk()
    {
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        EasyMock.replay(sit);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("testSong");
        SearchResult<?> result = service.expectChunkSearch(params, sit);
        assertSame("Wrong result", result, service.searchSongs(params, sit));
        EasyMock.verify(sit);
    }

    /**
     * Tests a search for all songs.
     */
    @Test
    public void testSearchSongsAll()
    {
        List<SongEntity> songs = createSongs(false);
        MediaSearchParameters params = new MediaSearchParameters();
        service.expectFullSearch(params, songs);
        SearchResult<SongInfo> result = service.searchSongs(params, null);
        List<SongInfo> results = result.getResults();
        assertEquals("Wrong number of results", SONG_NAMES.length,
                results.size());
        int idx = 0;
        for (SongInfo info : results)
        {
            assertEquals("Wrong song name", SONG_NAMES[idx], info.getName());
            assertEquals("Wrong play count", idx, info.getPlayCount());
            assertEquals("Wrong year", Integer.valueOf(1970 + idx),
                    info.getInceptionYear());
            assertEquals("Wrong artist name", ARTIST_NAMES[idx],
                    info.getArtistName());
            assertEquals("Wrong album name", ALBUM_NAMES[idx],
                    info.getAlbumName());
            idx++;
        }
    }

    /**
     * Tests a chunk search for songs which actually hits the database.
     */
    @Test
    public void testSearchSongsChunkQuery()
    {
        createSongs(true);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("a");
        service.setChunkSize(2 * SONG_NAMES.length);
        SearchResult<SongInfo> result = service.searchSongs(params, null);
        boolean foundArtist = false;
        boolean foundAlbum = false;
        for (SongInfo si : result.getResults())
        {
            assertTrue("Unexpected result: " + si,
                    si.getName().toUpperCase(Locale.ENGLISH).indexOf('A') >= 0);
            if (si.getArtistName() != null)
            {
                foundArtist = true;
            }
            if (si.getAlbumName() != null)
            {
                foundAlbum = true;
            }
        }
        assertFalse("No results", result.getResults().isEmpty());
        assertTrue("No artist found", foundArtist);
        assertTrue("No album found", foundAlbum);
    }

    /**
     * Tests a search for all songs which actually hits the database.
     */
    @Test
    public void testSearchSongsAllQuery()
    {
        createSongs(true);
        SearchResult<SongInfo> result =
                service.searchSongs(new MediaSearchParameters(), null);
        assertEquals("Wrong number of results", SONG_NAMES.length, result
                .getResults().size());
    }

    /**
     * Tests a chunk-based search for albums.
     */
    @Test
    public void testSearchAlbumsChunk()
    {
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        EasyMock.replay(sit);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("testAlbum");
        SearchResult<?> result = service.expectChunkSearch(params, sit);
        assertSame("Wrong result", result, service.searchAlbums(params, sit));
        EasyMock.verify(sit);
    }

    /**
     * Checks whether a search result with albums also contains information
     * about songs.
     *
     * @param result the result to check
     */
    private void checkSongData(SearchResult<AlbumInfo> result)
    {
        boolean foundDuration = false;
        boolean foundInceptionYear = false;
        boolean foundSongCount = false;
        for (AlbumInfo info : result.getResults())
        {
            if (info.getDuration() != null)
            {
                foundDuration = true;
            }
            if (info.getInceptionYear() != null)
            {
                foundInceptionYear = true;
            }
            if (info.getNumberOfSongs() > 0)
            {
                foundSongCount = true;
            }
        }
        assertTrue("No duration found", foundDuration);
        assertTrue("No inception year found", foundInceptionYear);
        assertTrue("No song count found", foundSongCount);
    }

    /**
     * Tests a search for all albums.
     */
    @Test
    public void testSearchAlbumsAll()
    {
        Map<String, Long> artists = createArtists();
        Map<String, AlbumEntity> albums = createAlbums();
        MediaSearchParameters params = new MediaSearchParameters();
        createSongs(true, artists, albums);
        service.expectFullSearch(params,
                new ArrayList<AlbumEntity>(albums.values()));
        SearchResult<AlbumInfo> result = service.searchAlbums(params, null);
        List<AlbumInfo> results = result.getResults();
        assertEquals("Wrong number of albums", ALBUM_NAMES.length,
                results.size());
        checkSongData(result);
    }

    /**
     * Tests an actual chunk search for albums.
     */
    @Test
    public void testSearchAlbumsChunkQuery()
    {
        createSongs(true);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("a");
        service.setChunkSize(2 * ALBUM_NAMES.length);
        checkSongData(service.searchAlbums(params, null));
    }

    /**
     * Tests an actual search for all albums.
     */
    @Test
    public void testSearchAlbumsAllQuery()
    {
        createSongs(true);
        SearchResult<AlbumInfo> result =
                service.searchAlbums(new MediaSearchParameters(), null);
        assertEquals("Wrong number of search results", ALBUM_NAMES.length,
                result.getResults().size());
        checkSongData(result);
    }

    /**
     * Tests appendOrder() if the parameters contain an order definition.
     */
    @Test
    public void testAppendOrderDefined()
    {
        OrderDef od1 = new OrderDef();
        od1.setFieldName("field1");
        OrderDef od2 = new OrderDef();
        od2.setFieldName("field2");
        od2.setDescending(true);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setOrderDefinition(Arrays.asList(od1, od2));
        String query = "select e from Entity e";
        String queryOrder = MediaSearchServiceImpl.appendOrder(query, params);
        assertEquals("Wrong query with order", query
                + " order by e.field1, e.field2 DESC", queryOrder);
    }

    /**
     * Tests appendOrder() if the default order is to be used.
     */
    @Test
    public void testAppendOrderUndefined()
    {
        MediaSearchParameters params = new MediaSearchParameters();
        String query = "select e from Entity e";
        String queryOrder = MediaSearchServiceImpl.appendOrder(query, params);
        assertEquals("Wrong query with order", query + " order by e.searchName",
                queryOrder);
    }

    /**
     * Tests whether the order definition is taken into account when doing a
     * chunk search.
     */
    @Test
    public void testChunkSizeOrdered()
    {
        createSongs(true);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("u");
        OrderDef od = new OrderDef();
        od.setFieldName("inceptionYear");
        od.setDescending(true);
        params.setOrderDefinition(Collections.singletonList(od));
        List<SongInfo> foundSongs = new LinkedList<SongInfo>();
        SearchResult<SongInfo> result = service.searchSongs(params, null);
        boolean moreResults = true;
        do
        {
            foundSongs.addAll(result.getResults());
            SearchIterator it = result.getSearchIterator();
            if (!it.hasNext())
            {
                moreResults = false;
            }
            else
            {
                result = service.searchSongs(params, it);
            }
        } while (moreResults);
        assertTrue("Too few results", foundSongs.size() > 1);
        Iterator<SongInfo> it = foundSongs.iterator();
        int year = it.next().getInceptionYear().intValue();
        while (it.hasNext())
        {
            int currentYear = it.next().getInceptionYear().intValue();
            assertTrue("Wrong sort order", year > currentYear);
            year = currentYear;
        }
    }

    /**
     * A test implementation of the service which allows mocking some methods.
     */
    private static class MediaSearchServiceTestImpl extends
            MediaSearchServiceImpl
    {
        /**
         * The serial version UID.
         */
        private static final long serialVersionUID = 20110102L;

        /** A mock search filter. */
        private SearchFilter<?> mockFilter;

        /** A mock search converter. */
        private EntityConverter<?, ?> mockConverter;

        /** A mock search result. */
        @SuppressWarnings("rawtypes")
        private SearchResult mockSearchResult;

        /** A mock list with search results. */
        @SuppressWarnings("rawtypes")
        private List mockResultList;

        /** Expected search parameters. */
        private MediaSearchParameters expParameters;

        /** Expected search iterator. */
        private SearchIterator expIterator;

        /**
         * Prepares this object to expect a chunk search.
         *
         * @param params the search parameters
         * @param sit the search iterator
         * @return the mock result object
         */
        public SearchResult<?> expectChunkSearch(MediaSearchParameters params,
                SearchIterator sit)
        {
            mockFilter = EasyMock.createMock(SearchFilter.class);
            mockConverter = EasyMock.createMock(EntityConverter.class);
            expIterator = sit;
            expParameters = params;
            mockSearchResult = EasyMock.createMock(SearchResult.class);
            return mockSearchResult;
        }

        /**
         * Prepares this object to expect a full search.
         *
         * @param params the search parameters
         * @param results the results to return
         */
        public void expectFullSearch(MediaSearchParameters params,
                List<?> results)
        {
            expParameters = params;
            mockResultList = results;
        }

        /**
         * Prepares this object to mock a search for artists by using a filter
         * which accepts all objects.
         *
         * @param params the expected parameters
         */
        public void mockArtistFilter(MediaSearchParameters params)
        {
            mockFilter = new ArtistSearchFilter("test")
            {
                @Override
                public boolean accepts(ArtistEntity e)
                {
                    return true;
                }
            };
            expParameters = params;
        }

        /**
         * Either returns the mock artist filter or calls the super method.
         */
        @Override
        ArtistSearchFilter createArtistSearchFilter(MediaSearchParameters params)
        {
            if (mockFilter != null)
            {
                assertEquals("Wrong parameters", expParameters, params);
                return (ArtistSearchFilter) mockFilter;
            }
            return super.createArtistSearchFilter(params);
        }

        /**
         * Either returns the mock song filter or calls the super method.
         */
        @SuppressWarnings("unchecked")
        @Override
        SearchFilter<SongEntity> createSongSearchFilter(
                MediaSearchParameters params)
        {
            if (mockFilter != null)
            {
                assertEquals("Wrong parameters", expParameters, params);
                return (SearchFilter<SongEntity>) mockFilter;
            }
            return super.createSongSearchFilter(params);
        }

        /**
         * Either returns the mock song converter or calls the super method.
         */
        @SuppressWarnings("unchecked")
        @Override
        EntityConverter<SongEntity, SongInfo> createSongSearchConverter()
        {
            return (EntityConverter<SongEntity, SongInfo>) ((mockConverter != null) ? mockConverter
                    : super.createSongSearchConverter());
        }

        /**
         * Either returns the mock album filter or calls the super method.
         */
        @SuppressWarnings("unchecked")
        @Override
        SearchFilter<AlbumEntity> createAlbumSearchFilter(
                MediaSearchParameters params)
        {
            if (mockFilter != null)
            {
                assertEquals("Wrong parameters", expParameters, params);
                return (SearchFilter<AlbumEntity>) mockFilter;
            }
            return super.createAlbumSearchFilter(params);
        }

        /**
         * Either returns the mock album converter or calls the super method.
         */
        @SuppressWarnings("unchecked")
        @Override
        EntityConverter<AlbumEntity, AlbumInfo> createAlbumSearchConverter()
        {
            return (EntityConverter<AlbumEntity, AlbumInfo>) ((mockConverter != null) ? mockConverter
                    : super.createAlbumSearchConverter());
        }

        /**
         * Either returns the mock result or calls the super method.
         */
        @SuppressWarnings("unchecked")
        @Override
        <E, D> SearchResult<D> executeChunkSearch(MediaSearchParameters params,
                SearchIterator searchIterator, SearchFilter<E> filter,
                EntityConverter<E, D> converter, String queryStr)
        {
            if (mockSearchResult != null)
            {
                assertEquals("Wrong parameters", expParameters, params);
                assertSame("Wrong iterator", expIterator, searchIterator);
                assertSame("Wrong filter", mockFilter, filter);
                assertSame("Wrong converter", mockConverter, converter);
                return mockSearchResult;
            }
            return super.executeChunkSearch(params, searchIterator, filter,
                    converter, queryStr);
        }

        /**
         * Either returns the mock result or calls the super method.
         */
        @SuppressWarnings("unchecked")
        @Override
        <D> List<D> executeFullSearch(MediaSearchParameters params,
                SearchIteratorImpl sit, String queryStr)
        {
            if (mockResultList != null)
            {
                assertEquals("Wrong parameters", expParameters, params);
                return mockResultList;
            }
            return super.executeFullSearch(params, sit, queryStr);
        }
    }
}
