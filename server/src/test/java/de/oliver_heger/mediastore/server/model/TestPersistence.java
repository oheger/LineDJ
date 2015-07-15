package de.oliver_heger.mediastore.server.model;

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
     * Creates a test song entity with the given index.
     *
     * @param idx the index
     * @return the test song entity
     */
    private static SongEntity createTestSongEntity(int idx)
    {
        SongEntity song = new SongEntity();
        song.setName(SONG_NAME + idx);
        song.setDuration(20 * 60 * 1000L + idx);
        song.setTrackNo(idx);
        song.setInceptionYear(1973 + idx);
        song.setUser(PersistenceTestHelper.getTestUser());
        return song;
    }

    /**
     * Helper method for testing songs retrieved from the database.
     *
     * @param songs the collection with test songs
     */
    private static void checkSongEntities(List<SongEntity> songs)
    {
        assertEquals("Wrong number of songs", SONG_COUNT, songs.size());
        Set<Integer> tracks = new HashSet<Integer>();
        for (SongEntity s : songs)
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
        ArtistEntity a = new ArtistEntity();
        a.setName(artistName);
        a.setUser(PersistenceTestHelper.getTestUser());
        persist(a);
        helper.closeEM();
        assertNotNull("No ID", a.getId());
        ArtistEntity a2 = helper.getEM().find(ArtistEntity.class, a.getId());
        assertNotSame("Same artist", a, a2);
        assertEquals("Wrong artist name", artistName, a2.getName());
        assertEquals("Wrong artist user", PersistenceTestHelper.getTestUser(),
                a2.getUser());
    }

    /**
     * Tests whether a song can be persisted.
     */
    @Test
    public void testPersistSong()
    {
        SongEntity song = new SongEntity();
        song.setName("Garden Party");
        song.setDuration(50000L);
        song.setInceptionYear(1980);
        song.setTrackNo(2);
        persist(song);
        helper.closeEM();
        SongEntity song2 = helper.getEM().find(SongEntity.class, song.getId());
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
        AlbumEntity album = new AlbumEntity();
        album.setName("Misplaced Childhood");
        album.setUser(PersistenceTestHelper.getTestUser());
        persist(album);
        helper.closeEM();
        AlbumEntity album2 =
                helper.getEM().find(AlbumEntity.class, album.getId());
        assertNotSame("Same objects", album, album2);
        assertEquals("Not equal", album, album2);
    }

    /**
     * Tests whether an artist with a number of songs can be persisted.
     */
    @Test
    public void testPersistArtistAndSongs()
    {
        ArtistEntity a = new ArtistEntity();
        a.setName("Mike Oldfield");
        a.setUser(PersistenceTestHelper.getTestUser());
        persist(a);
        for (int i = 0; i < SONG_COUNT; i++)
        {
            SongEntity song = createTestSongEntity(i);
            song.setArtistID(a.getId());
            persist(song);
        }
        helper.closeEM();
        List<SongEntity> songs = Finders.findSongsByArtist(helper.getEM(), a);
        checkSongEntities(songs);
    }

    /**
     * Tests whether songs for an album can be persisted and retrieved.
     */
    @Test
    public void testPersistAlbumAndSongs()
    {
        AlbumEntity album = new AlbumEntity();
        album.setName("Tubular Bells Collection");
        album.setUser(PersistenceTestHelper.getTestUser());
        persist(album);
        for (int i = 0; i < SONG_COUNT; i++)
        {
            SongEntity song = createTestSongEntity(i);
            song.setAlbumID(album.getId());
            persist(song);
        }
        persist(createTestSongEntity(SONG_COUNT));
        helper.closeEM();
        List<SongEntity> songs =
                Finders.findSongsByAlbum(helper.getEM(), album);
        checkSongEntities(songs);
    }

    /**
     * Creates the test instances for a test with songs, albums, and artists.
     *
     * @return the data object with the test instances
     */
    private MediaTestData prepareMediaTest()
    {
        ArtistEntity art1 = new ArtistEntity();
        art1.setName("Mike Oldfield");
        art1.setUser(PersistenceTestHelper.getTestUser());
        persist(art1);
        ArtistEntity art2 = new ArtistEntity();
        art2.setName("Marillion");
        art2.setUser(PersistenceTestHelper.getTestUser());
        persist(art2);
        AlbumEntity album1 = new AlbumEntity();
        album1.setName("Tubular Bells Collection");
        album1.setUser(PersistenceTestHelper.getTestUser());
        persist(album1);
        AlbumEntity album2 = new AlbumEntity();
        album2.setName("A script for a Jester's tear");
        album2.setUser(PersistenceTestHelper.getTestUser());
        persist(album2);
        for (int i = 0; i < SONG_COUNT; i++)
        {
            SongEntity song = createTestSongEntity(i);
            song.setAlbumID(album1.getId());
            song.setArtistID(art1.getId());
            persist(song);
            song = createTestSongEntity(i + SONG_COUNT);
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
        List<AlbumEntity> albums =
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
        List<AlbumEntity> newAlbums =
                new ArrayList<AlbumEntity>(albumCount + 1);
        for (int i = 0; i < albumCount; i++)
        {
            AlbumEntity album = new AlbumEntity();
            album.setName("Additional Album " + i);
            album.setUser(PersistenceTestHelper.getTestUser());
            persist(album);
            newAlbums.add(album);
            SongEntity song = createTestSongEntity(startIdx++);
            song.setArtistID(data.getArtist1().getId());
            song.setAlbumID(album.getId());
            persist(song);
        }
        helper.closeEM();
        newAlbums.add(data.getAlbum1());
        List<AlbumEntity> albums =
                new ArrayList<AlbumEntity>(Finders.findAlbumsForArtist(
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
        List<ArtistEntity> artists =
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
        List<ArtistEntity> newArtists =
                new ArrayList<ArtistEntity>(artistCount + 1);
        for (int i = 0; i < artistCount; i++)
        {
            ArtistEntity art = new ArtistEntity();
            art.setName("New Artist " + i);
            art.setUser(PersistenceTestHelper.getTestUser());
            persist(art);
            newArtists.add(art);
            SongEntity song = createTestSongEntity(startIdx++);
            song.setAlbumID(data.getAlbum2().getId());
            song.setArtistID(art.getId());
            persist(song);
        }
        helper.closeEM();
        newArtists.add(data.getArtist2());
        List<ArtistEntity> artists =
                new ArrayList<ArtistEntity>(Finders.findArtistsForAlbum(
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
        Set<Long> params =
                new HashSet<Long>(Arrays.asList(data.getArtist1().getId(), data
                        .getArtist2().getId()));
        List<?> artists =
                helper.getEM()
                        .createQuery(
                                "select a from ArtistEntity a "
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
        private final ArtistEntity artist1;

        /** The second test artist. */
        private final ArtistEntity artist2;

        /** The first test album. */
        private final AlbumEntity album1;

        /** The second test album. */
        private final AlbumEntity album2;

        /**
         * Creates a new instance of {@code MediaTestData} and initializes it.
         *
         * @param art1 test artist 1
         * @param art2 test artist 2
         * @param alb1 test album 1
         * @param alb2 test album 2
         */
        public MediaTestData(ArtistEntity art1, ArtistEntity art2,
                AlbumEntity alb1, AlbumEntity alb2)
        {
            artist1 = art1;
            artist2 = art2;
            album1 = alb1;
            album2 = alb2;
        }

        public ArtistEntity getArtist1()
        {
            return artist1;
        }

        public ArtistEntity getArtist2()
        {
            return artist2;
        }

        public AlbumEntity getAlbum1()
        {
            return album1;
        }

        public AlbumEntity getAlbum2()
        {
            return album2;
        }
    }
}
