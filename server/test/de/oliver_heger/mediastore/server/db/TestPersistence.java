package de.oliver_heger.mediastore.server.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.model.Finders;
import de.oliver_heger.mediastore.shared.model.Album;
import de.oliver_heger.mediastore.shared.model.Artist;
import de.oliver_heger.mediastore.shared.model.Song;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * A test class for testing persistence of the model objects.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPersistence
{
    /** Constant for the name of a test song. */
    private static final String SONG_NAME = "Tubular Bells part";

    /** Constant for the number of test songs. */
    private static final int SONG_COUNT = 9;

    /** The test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    @Before
    public void setUp()
    {
        helper.setUp();
    }

    @After
    public void tearDown()
    {
        helper.tearDown();
    }

    /**
     * Helper method for saving an entity object in a new transaction.
     *
     * @param entity the entity to be saved
     */
    private void persist(Object entity)
    {
        helper.persist(entity, true);
    }

    /**
     * Creates a test song with the given index.
     *
     * @param idx the index
     * @return the test song
     */
    private static Song createTestSong(int idx)
    {
        Song song = new Song();
        song.setName(SONG_NAME + idx);
        song.setDuration(20 * 60 * 1000L + idx);
        song.setTrackNo(idx);
        song.setInceptionYear(1973 + idx);
        song.setUserID(PersistenceTestHelper.USER);
        return song;
    }

    /**
     * Helper method for testing songs retrieved from the database.
     *
     * @param songs the collection with test songs
     */
    private static void checkSongs(List<Song> songs)
    {
        assertEquals("Wrong number of songs", SONG_COUNT, songs.size());
        Set<Integer> tracks = new HashSet<Integer>();
        for (Song s : songs)
        {
            int i = s.getTrackNo();
            assertTrue("Invalid track: " + i, i >= 0 && i < SONG_COUNT);
            assertTrue("Duplicate ID: " + i, tracks.add(i));
            assertEquals("Wrong name", SONG_NAME + i, s.getName());
        }
    }

    /**
     * Tests whether an artist can be persisted.
     */
    @Test
    public void testPersistArtist()
    {
        final String artistName = "Marillion";
        Artist a = new Artist();
        a.setName(artistName);
        a.setUserID(PersistenceTestHelper.USER);
        persist(a);
        helper.closeEM();
        assertNotNull("No ID", a.getId());
        Artist a2 = helper.getEM().find(Artist.class, a.getId());
        assertNotSame("Same artist", a, a2);
        assertEquals("Wrong artist name", artistName, a2.getName());
        assertEquals("Wrong artist user", PersistenceTestHelper.USER,
                a2.getUserID());
    }

    /**
     * Tests whether a song can be persisted.
     */
    @Test
    public void testPersistSong()
    {
        Song song = new Song();
        song.setName("Garden Party");
        song.setDuration(50000L);
        song.setInceptionYear(1980);
        song.setTrackNo(2);
        persist(song);
        helper.closeEM();
        Song song2 = helper.getEM().find(Song.class, song.getId());
        assertNotSame("Same objects", song, song2);
        assertEquals("Not equal", song, song2);
        assertEquals("Wrong year", song.getInceptionYear(),
                song2.getInceptionYear());
        assertEquals("Wrong track", song.getTrackNo(), song2.getTrackNo());
    }

    /**
     * Tests whether an album can be persisted.
     */
    @Test
    public void testPersistAlbum()
    {
        Album album = new Album();
        album.setName("Misplaced Childhood");
        album.setInceptionYear(1985);
        album.setUserID(PersistenceTestHelper.USER);
        persist(album);
        helper.closeEM();
        Album album2 = helper.getEM().find(Album.class, album.getId());
        assertNotSame("Same objects", album, album2);
        assertEquals("Not equal", album, album2);
    }

    /**
     * Tests whether an artist with a number of songs can be persisted.
     */
    @Test
    public void testPersistArtistAndSongs()
    {
        Artist a = new Artist();
        a.setName("Mike Oldfield");
        a.setUserID(PersistenceTestHelper.USER);
        persist(a);
        for (int i = 0; i < SONG_COUNT; i++)
        {
            Song song = createTestSong(i);
            song.setArtistID(a.getId());
            persist(song);
        }
        helper.closeEM();
        List<Song> songs = Finders.findSongsByArtist(helper.getEM(), a);
        checkSongs(songs);
    }

    /**
     * Tests whether songs for an album can be persisted and retrieved.
     */
    @Test
    public void testPersistAlbumAndSongs()
    {
        Album album = new Album();
        album.setName("Tubular Bells Collection");
        album.setUserID(PersistenceTestHelper.USER);
        album.setInceptionYear(2010);
        persist(album);
        for (int i = 0; i < SONG_COUNT; i++)
        {
            Song song = createTestSong(i);
            song.setAlbumID(album.getId());
            persist(song);
        }
        persist(createTestSong(SONG_COUNT));
        helper.closeEM();
        List<Song> songs = Finders.findSongsByAlbum(helper.getEM(), album);
        checkSongs(songs);
    }

    /**
     * Creates the test instances for a test with songs, albums, and artists.
     *
     * @return the data object with the test instances
     */
    private MediaTestData prepareMediaTest()
    {
        Artist art1 = new Artist();
        art1.setName("Mike Oldfield");
        art1.setUserID(PersistenceTestHelper.USER);
        persist(art1);
        Artist art2 = new Artist();
        art2.setName("Marillion");
        art2.setUserID(PersistenceTestHelper.USER);
        persist(art2);
        Album album1 = new Album();
        album1.setName("Tubular Bells Collection");
        album1.setUserID(PersistenceTestHelper.USER);
        album1.setInceptionYear(2010);
        persist(album1);
        Album album2 = new Album();
        album2.setName("A script for a Jester's tear");
        album2.setUserID(PersistenceTestHelper.USER);
        album2.setInceptionYear(1983);
        persist(album2);
        for (int i = 0; i < SONG_COUNT; i++)
        {
            Song song = createTestSong(i);
            song.setAlbumID(album1.getId());
            song.setArtistID(art1.getId());
            persist(song);
            song = createTestSong(i + SONG_COUNT);
            song.setAlbumID(album2.getId());
            song.setArtistID(art2.getId());
            persist(song);
        }
        helper.closeEM();
        return new MediaTestData(art1, art2, album1, album2);
    }

    /**
     * Tests whether a single album of an artist can be found.
     */
    @Test
    public void testFindAlbumsForArtistSingle()
    {
        MediaTestData data = prepareMediaTest();
        List<Album> albums =
                Finders.findAlbumsForArtist(helper.getEM(), data.getArtist1());
        assertEquals("Wrong number of albums", 1, albums.size());
        assertEquals("Wrong album title", data.getAlbum1().getName(), albums
                .get(0).getName());
    }

    /**
     * Tests whether the albums of an artist can be retrieved if there are
     * multiple.
     */
    @Test
    public void testFindAlbumsForArtistMulti()
    {
        MediaTestData data = prepareMediaTest();
        final int albumCount = 6;
        int startIdx = 2 * SONG_COUNT + 1;
        List<Album> newAlbums = new ArrayList<Album>(albumCount + 1);
        for (int i = 0; i < albumCount; i++)
        {
            Album album = new Album();
            album.setName("Additional Album " + i);
            album.setInceptionYear(1983 + i);
            album.setUserID(PersistenceTestHelper.USER);
            persist(album);
            newAlbums.add(album);
            Song song = createTestSong(startIdx++);
            song.setArtistID(data.getArtist1().getId());
            song.setAlbumID(album.getId());
            persist(song);
        }
        helper.closeEM();
        newAlbums.add(data.getAlbum1());
        List<Album> albums =
                new ArrayList<Album>(Finders.findAlbumsForArtist(
                        helper.getEM(), data.getArtist1()));
        assertEquals("Wrong number of albums", newAlbums.size(), albums.size());
        assertTrue("Wrong albums: " + albums, albums.containsAll(newAlbums));
    }

    /**
     * Tests whether a single artist of an album can be found.
     */
    @Test
    public void testFindArtistsForAlbumSingle()
    {
        MediaTestData data = prepareMediaTest();
        List<Artist> artists =
                Finders.findArtistsForAlbum(helper.getEM(), data.getAlbum1());
        assertEquals("Wrong number of artists", 1, artists.size());
        assertEquals("Wrong artist name", data.getArtist1().getName(), artists
                .get(0).getName());
    }

    /**
     * Tests whether the artists of an album can be retrieved if there are
     * multiple.
     */
    @Test
    public void testFindArtistsForAlbumsMulti()
    {
        MediaTestData data = prepareMediaTest();
        final int artistCount = 12;
        int startIdx = 2 * SONG_COUNT + 1;
        List<Artist> newArtists = new ArrayList<Artist>(artistCount + 1);
        for (int i = 0; i < artistCount; i++)
        {
            Artist art = new Artist();
            art.setName("New Artist " + i);
            art.setUserID(PersistenceTestHelper.USER);
            persist(art);
            newArtists.add(art);
            Song song = createTestSong(startIdx++);
            song.setAlbumID(data.getAlbum2().getId());
            song.setArtistID(art.getId());
            persist(song);
        }
        helper.closeEM();
        newArtists.add(data.getArtist2());
        List<Artist> artists =
                new ArrayList<Artist>(Finders.findArtistsForAlbum(
                        helper.getEM(), data.getAlbum2()));
        assertEquals("Wrong number of artists", newArtists.size(),
                artists.size());
        assertTrue("Wrong artists: " + artists, artists.containsAll(newArtists));
    }

    /**
     * Tests executing a query with an in condition.
     */
    @Test
    public void testInCondition()
    {
        MediaTestData data = prepareMediaTest();
        List<Long> params =
                Arrays.asList(data.getArtist1().getId(), data.getArtist2()
                        .getId());
        List<?> artists =
                helper.getEM()
                        .createQuery(
                                "select a from Artist a "
                                        + "where a.id in (:ids)")
                        .setParameter("ids", params).getResultList();
        assertEquals("Wrong number of results", 2, artists.size());
    }

    /**
     * A simple data class storing the instances created for a unit test with
     * artists, songs, and albums.
     */
    private static class MediaTestData
    {
        /** The first test artist. */
        private final Artist artist1;

        /** The second test artist. */
        private final Artist artist2;

        /** The first test album. */
        private final Album album1;

        /** The second test album. */
        private final Album album2;

        /**
         * Creates a new instance of {@code MediaTestData} and initializes it.
         *
         * @param art1 test artist 1
         * @param art2 test artist 2
         * @param alb1 test album 1
         * @param alb2 test album 2
         */
        public MediaTestData(Artist art1, Artist art2, Album alb1, Album alb2)
        {
            artist1 = art1;
            artist2 = art2;
            album1 = alb1;
            album2 = alb2;
        }

        public Artist getArtist1()
        {
            return artist1;
        }

        public Artist getArtist2()
        {
            return artist2;
        }

        public Album getAlbum1()
        {
            return album1;
        }

        public Album getAlbum2()
        {
            return album2;
        }
    }
}
