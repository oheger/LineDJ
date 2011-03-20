package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.localstore.JPATestHelper;
import de.oliver_heger.mediastore.localstore.model.AlbumEntity;
import de.oliver_heger.mediastore.localstore.model.ArtistEntity;
import de.oliver_heger.mediastore.localstore.model.SongEntity;

/**
 * Test class for {@code Finders}. This test class mainly tests corner cases and
 * special input parameters. The standard functionality is tested by the tests
 * for the services which use the {@code Finders} class.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestFinders
{
    /** Constant for an entity name. */
    private static final String NAME = "TestName";

    /** The JPA test helper. */
    private JPATestHelper helper;

    @Before
    public void setUp() throws Exception
    {
        helper = new JPATestHelper();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.close();
    }

    /**
     * Tries to find an artist without an EM.
     */
    @Test(expected = NullPointerException.class)
    public void testFindArtistNoEM()
    {
        Finders.findArtist(null, "name");
    }

    /**
     * Tries to find an album without an EM.
     */
    @Test(expected = NullPointerException.class)
    public void testFindAlbumNoEM()
    {
        Finders.findAlbum(null, "name", 2011);
    }

    /**
     * Tries to find a song without an EM.
     */
    @Test(expected = NullPointerException.class)
    public void testFindSongNoEM()
    {
        Finders.findSong(null, "name", 100, "artName");
    }

    /**
     * Tests whether an album can be found that does not have an inception year.
     */
    @Test
    public void testFindAlbumNoYear()
    {
        AlbumEntity album = new AlbumEntity();
        album.setName(NAME);
        helper.persist(album, true);
        AlbumEntity album2 = Finders.findAlbum(helper.getEM(), NAME, null);
        assertEquals("Different album", album.getId(), album2.getId());
    }

    /**
     * Tests whether a song can be found that does not have an artist nor a
     * duration.
     */
    @Test
    public void testFindSongNoArtistNoDuration()
    {
        SongEntity song = new SongEntity();
        song.setName(NAME);
        helper.persist(song, true);
        SongEntity song2 = Finders.findSong(helper.getEM(), NAME, null, null);
        assertEquals("Different song", song.getId(), song2.getId());
    }

    /**
     * Tests whether a null name can be passed in (which does not really make
     * sense).
     */
    @Test
    public void testFindNullName()
    {
        ArtistEntity artist = new ArtistEntity();
        artist.setName(NAME);
        helper.persist(artist, true);
        assertNull("Got an artist", Finders.findArtist(helper.getEM(), null));
    }
}
