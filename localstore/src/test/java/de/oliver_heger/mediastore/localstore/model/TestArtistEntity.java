package de.oliver_heger.mediastore.localstore.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.localstore.JPATestHelper;

/**
 * Test class for {@code ArtistEntity}. This class also tests functionality of
 * the {@code SongOwner} base class.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistEntity
{
    /** Constant for the artist name. */
    private static final String NAME = "Mike Oldfield";

    /** Constant for the name of a song. */
    private static final String SONG_NAME = "Tubular Bells Part ";

    /** Constant for the number of test songs. */
    private static final int SONG_COUNT = 3;

    /** The JPA helper. */
    private JPATestHelper helper;

    /** The artist to be tested. */
    private ArtistEntity artist;

    @Before
    public void setUp() throws Exception
    {
        helper = new JPATestHelper();
        artist = new ArtistEntity();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.close();
    }

    /**
     * Adds test songs to the test entity. All songs added are also returned as
     * a list.
     *
     * @return the list with songs added to the artist
     */
    private List<SongEntity> initTestSongs()
    {
        List<SongEntity> songs = createTestSongs();
        for (SongEntity song : songs)
        {
            assertTrue("Could not add song: " + song, artist.addSong(song));
        }
        return songs;
    }

    /**
     * Creates a number of test songs.
     *
     * @return the test songs
     */
    private static List<SongEntity> createTestSongs()
    {
        List<SongEntity> songs = new ArrayList<SongEntity>(SONG_COUNT);
        for (int i = 0; i < SONG_COUNT; i++)
        {
            SongEntity song = new SongEntity();
            song.setName(SONG_NAME + i);
            song.setDuration((20 + i) * 60);
            songs.add(song);
        }
        return songs;
    }

    /**
     * Tests whether the given artist entity contains the expected test songs.
     *
     * @param artist the artist to test
     */
    private static void checkSongs(ArtistEntity artist)
    {
        assertEquals("Wrong number of test songs", SONG_COUNT, artist
                .getSongs().size());
        for (SongEntity song : createTestSongs())
        {
            assertTrue("Song not found: " + song,
                    artist.getSongs().contains(song));
        }
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertNull("Got an ID", artist.getId());
        assertNull("Got a name", artist.getName());
        assertTrue("Got songs", artist.getSongs().isEmpty());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(artist, artist, true);
        ArtistEntity a2 = new ArtistEntity();
        RemoteMediaStoreTestHelper.checkEquals(artist, a2, true);
        artist.setName(NAME);
        a2.setName(NAME);
        RemoteMediaStoreTestHelper.checkEquals(artist, a2, true);
        a2.setName(NAME.toLowerCase(Locale.ENGLISH));
        RemoteMediaStoreTestHelper.checkEquals(artist, a2, true);
        artist.getSongs().add(new SongEntity());
        RemoteMediaStoreTestHelper.checkEquals(artist, a2, true);
        artist.setId(20110305213810L);
        RemoteMediaStoreTestHelper.checkEquals(artist, a2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        ArtistEntity a2 = new ArtistEntity();
        artist.setName(NAME);
        RemoteMediaStoreTestHelper.checkEquals(artist, a2, false);
        a2.setName(NAME + "_other");
        RemoteMediaStoreTestHelper.checkEquals(artist, a2, false);
    }

    /**
     * Tests equals() for other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(artist);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        artist.setName(NAME);
        String s = artist.toString();
        assertTrue("Name not found: " + s, s.contains("name=" + NAME));
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        artist.setName(NAME);
        initTestSongs();
        ArtistEntity a2 = RemoteMediaStoreTestHelper.serialize(artist);
        assertEquals("Different artist", artist, a2);
        checkSongs(a2);
    }

    /**
     * Tries to add a null song.
     */
    @Test(expected = NullPointerException.class)
    public void testAddSongNull()
    {
        artist.addSong(null);
    }

    /**
     * Tests whether a song can be added successfully.
     */
    @Test
    public void testAddSong()
    {
        initTestSongs();
        assertEquals("Wrong number of songs", SONG_COUNT, artist.getSongs()
                .size());
        for (SongEntity song : createTestSongs())
        {
            assertTrue("Song not found", artist.getSongs().contains(song));
        }
    }

    /**
     * Tests whether duplicate songs are rejected.
     */
    @Test
    public void testAddSongDuplicate()
    {
        initTestSongs();
        SongEntity song = createTestSongs().get(0);
        assertFalse("Could add duplicate song", artist.addSong(song));
        assertNull("Song was attached", song.getArtist());
    }

    /**
     * Tests whether a song entity can be removed.
     */
    @Test
    public void testRemoveSong()
    {
        initTestSongs();
        for (int i = 0; i < SONG_COUNT; i++)
        {
            SongEntity song = artist.getSongs().iterator().next();
            assertTrue("Could not remove " + song, artist.removeSong(song));
            assertNull("Got an artist", song.getArtist());
        }
        assertTrue("Still got songs", artist.getSongs().isEmpty());
    }

    /**
     * Tries to remove a non-existing song.
     */
    @Test
    public void testRemoveSongNonExisting()
    {
        initTestSongs();
        SongEntity song = new SongEntity();
        song.setName(SONG_NAME + ":other");
        assertFalse("Wrong result", artist.removeSong(song));
    }

    /**
     * Tests whether removeSong() can handle null references.
     */
    @Test
    public void testRemoveSongNull()
    {
        initTestSongs();
        assertFalse("Wrong result", artist.removeSong(null));
    }

    /**
     * Tests whether an artist can be persisted.
     */
    @Test
    public void testPersist()
    {
        artist.setName(NAME);
        helper.persist(artist, true);
        helper.closeEM();
        assertNotNull("No artist ID", artist.getId());
        ArtistEntity a2 =
                helper.getEM().find(ArtistEntity.class, artist.getId());
        assertEquals("Different artist", artist, a2);
        assertTrue("Got songs", a2.getSongs().isEmpty());
    }

    /**
     * Tests whether songs can be persisted with an artist.
     */
    @Test
    public void testPersistWithSongs()
    {
        artist.setName(NAME);
        initTestSongs();
        helper.persist(artist, true);
        helper.closeEM();
        ArtistEntity a2 =
                helper.getEM().find(ArtistEntity.class, artist.getId());
        checkSongs(a2);
    }

    /**
     * Tests whether a persistent song can be removed.
     */
    @Test
    public void testRemovePersistentSong()
    {
        artist.setName(NAME);
        initTestSongs();
        helper.persist(artist, true);
        helper.closeEM();
        helper.begin();
        ArtistEntity a2 =
                helper.getEM().find(ArtistEntity.class, artist.getId());
        SongEntity song = a2.getSongs().iterator().next();
        assertTrue("Wrong result", a2.removeSong(song));
        helper.commit();
        a2 = helper.getEM().find(ArtistEntity.class, artist.getId());
        assertEquals("Wrong number of songs", SONG_COUNT - 1, a2.getSongs()
                .size());
        assertFalse("Song still found", a2.getSongs().contains(song));
        assertNotNull("Song entity was removed",
                helper.getEM().find(SongEntity.class, song.getId()));
    }
}
