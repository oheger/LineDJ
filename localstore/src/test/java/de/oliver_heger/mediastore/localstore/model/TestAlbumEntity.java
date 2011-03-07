package de.oliver_heger.mediastore.localstore.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.localstore.JPATestHelper;

/**
 * Test class for {@code AlbumEntity}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumEntity
{
    /** Constant for the name of the test album. */
    private static final String ALBUM_NAME = "Love over Gold";

    /** An array with the names of the songs of the test album. */
    private static final String[] SONG_NAMES = {
            "Telegraph Road", "Private Investigations", "Industrial desease",
            "Love over gold", "It never rains"
    };

    /** Constant for the inception year. */
    private static final Integer YEAR = 1982;

    /** The JPA helper. */
    private JPATestHelper helper;

    /** The entity to be tested. */
    private AlbumEntity album;

    @Before
    public void setUp() throws Exception
    {
        helper = new JPATestHelper();
        album = new AlbumEntity();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.close();
    }

    /**
     * Initializes the test album with default values.
     */
    private void initAlbum()
    {
        album.setName(ALBUM_NAME);
        album.setInceptionYear(YEAR);
        for (int i = 0; i < SONG_NAMES.length; i++)
        {
            SongEntity song = new SongEntity();
            song.setName(SONG_NAMES[i]);
            song.setInceptionYear(YEAR);
            album.addSong(song);
        }
    }

    /**
     * Tests whether the given album entity contains the expected test values.
     *
     * @param alb the entity to check
     */
    private void checkAlbum(AlbumEntity alb)
    {
        assertEquals("Wrong album name", ALBUM_NAME, alb.getName());
        assertEquals("Wrong inception year", YEAR, alb.getInceptionYear());
        assertEquals("Wrong number of songs", SONG_NAMES.length, alb.getSongs()
                .size());
        Set<String> songNames = new HashSet<String>(Arrays.asList(SONG_NAMES));
        for (SongEntity song : alb.getSongs())
        {
            assertTrue("Unexpected song: " + song,
                    songNames.remove(song.getName()));
        }
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(album, album, true);
        AlbumEntity a2 = new AlbumEntity();
        RemoteMediaStoreTestHelper.checkEquals(album, a2, true);
        album.setName(ALBUM_NAME);
        a2.setName(ALBUM_NAME.toLowerCase(Locale.ENGLISH));
        RemoteMediaStoreTestHelper.checkEquals(album, a2, true);
        album.setInceptionYear(YEAR);
        a2.setInceptionYear(YEAR);
        RemoteMediaStoreTestHelper.checkEquals(album, a2, true);
        SongEntity song = new SongEntity();
        song.setName(SONG_NAMES[0]);
        album.getSongs().add(song);
        RemoteMediaStoreTestHelper.checkEquals(album, a2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        AlbumEntity a2 = new AlbumEntity();
        album.setName(ALBUM_NAME);
        RemoteMediaStoreTestHelper.checkEquals(album, a2, false);
        a2.setName(ALBUM_NAME + "_other");
        RemoteMediaStoreTestHelper.checkEquals(album, a2, false);
        a2.setName(ALBUM_NAME);
        album.setInceptionYear(YEAR);
        RemoteMediaStoreTestHelper.checkEquals(album, a2, false);
        a2.setInceptionYear(YEAR + 1);
        RemoteMediaStoreTestHelper.checkEquals(album, a2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        initAlbum();
        RemoteMediaStoreTestHelper.checkEqualsTrivial(album);
    }

    /**
     * Tests the string representation of the album.
     */
    @Test
    public void testToString()
    {
        initAlbum();
        String s = album.toString();
        assertTrue("Name not found: " + s, s.contains("name=" + ALBUM_NAME));
        assertTrue("Year not found: " + s, s.contains("inceptionYear=" + YEAR));
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        initAlbum();
        AlbumEntity a2 = RemoteMediaStoreTestHelper.serialize(album);
        checkAlbum(a2);
    }

    /**
     * Tests whether an album with songs can be persisted.
     */
    @Test
    public void testPersist()
    {
        initAlbum();
        helper.persist(album, true);
        helper.closeEM();
        AlbumEntity a2 = helper.getEM().find(AlbumEntity.class, album.getId());
        checkAlbum(a2);
    }

    /**
     * Tests whether a song can be removed from an album.
     */
    @Test
    public void testRemoveSong()
    {
        initAlbum();
        SongEntity song = new SongEntity();
        song.setName("Money for nothing");
        song.setInceptionYear(1980);
        song.setDuration(520);
        album.addSong(song);
        helper.persist(album, true);
        helper.begin();
        AlbumEntity a2 = helper.getEM().find(AlbumEntity.class, album.getId());
        assertTrue("Wrong result", a2.removeSong(song));
        helper.commit();
        a2 = helper.getEM().find(AlbumEntity.class, album.getId());
        checkAlbum(a2);
    }
}
