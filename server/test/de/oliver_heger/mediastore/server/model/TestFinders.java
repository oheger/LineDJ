package de.oliver_heger.mediastore.server.model;

import static org.junit.Assert.fail;

import java.util.HashSet;

import javax.persistence.EntityManager;

import org.easymock.EasyMock;
import org.junit.Test;

import de.oliver_heger.mediastore.server.model.Finders;
import de.oliver_heger.mediastore.shared.model.Album;
import de.oliver_heger.mediastore.shared.model.Artist;
import de.oliver_heger.mediastore.shared.model.Song;

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
    /**
     * Tries to search for the songs of an artist without an entity manager.
     */
    @Test(expected = NullPointerException.class)
    public void testFindByArtistNoEM()
    {
        Finders.findSongsByArtist(null, new Artist());
    }

    /**
     * Tries to search for the songs of an artist without providing the artist.
     */
    @Test
    public void testFindByArtistNoArtist()
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
        Finders.findSongsByAlbum(null, new Album());
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
        Finders.findAlbumsForSongs(null, new HashSet<Song>());
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
     * Tries to find the albums of an artist without an entity manager.
     */
    @Test(expected = NullPointerException.class)
    public void testFindAlbumsForArtistNoEM()
    {
        Finders.findAlbumsForArtist(null, new Artist());
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
        Finders.findArtistsForAlbum(null, new Album());
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
}
