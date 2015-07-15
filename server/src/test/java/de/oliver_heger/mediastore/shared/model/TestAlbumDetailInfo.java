package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code AlbumDetailInfo}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumDetailInfo
{
    /** The instance to be tested. */
    private AlbumDetailInfo info;

    @Before
    public void setUp() throws Exception
    {
        info = new AlbumDetailInfo();
    }

    /**
     * Tests whether the ID can be queried as string.
     */
    @Test
    public void testGetIDAsString()
    {
        final Long albumID = 20110831205604L;
        info.setAlbumID(albumID);
        assertEquals("Wrong string ID", albumID.toString(),
                info.getIDAsString());
    }

    /**
     * Tests getIDAsString() if no album ID is available.
     */
    @Test
    public void testGetIDAsStringUndefined()
    {
        assertNull("Got an ID string", info.getIDAsString());
    }

    /**
     * Tests whether an empty map is returned if there are no synonyms.
     */
    @Test
    public void testGetSynonymDataEmpty()
    {
        assertTrue("Got synonyms", info.getSynonymData().isEmpty());
    }

    /**
     * Tests whether an empty list is returned if there are no songs.
     */
    @Test
    public void testGetSongsEmpty()
    {
        assertTrue("Got songs", info.getSongs().isEmpty());
    }

    /**
     * Tests whether an empty list is returned if there are no artists.
     */
    @Test
    public void testGetArtistsEmpty()
    {
        assertTrue("Got artists", info.getArtists().isEmpty());
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        Map<String, String> synonyms = new HashMap<String, String>();
        synonyms.put("key1", "syn1");
        synonyms.put("key2", "syn2");
        info.setSynonymData(synonyms);
        List<SongInfo> songs = new ArrayList<SongInfo>();
        SongInfo song = new SongInfo();
        song.setName("LaLa1");
        songs.add(song);
        song = new SongInfo();
        song.setName("LaLa2");
        songs.add(song);
        info.setSongs(songs);
        List<ArtistInfo> artists = new ArrayList<ArtistInfo>();
        ArtistInfo art = new ArtistInfo();
        art.setName("Artist1");
        artists.add(art);
        art = new ArtistInfo();
        art.setName("Another Artist");
        artists.add(art);
        info.setArtists(artists);
        AlbumDetailInfo info2 = RemoteMediaStoreTestHelper.serialize(info);
        assertEquals("Wrong synonyms", synonyms, info2.getSynonymData());
        assertEquals("Wrong number of songs", songs.size(), info2.getSongs()
                .size());
        Iterator<SongInfo> songIt = info2.getSongs().iterator();
        for (SongInfo si : songs)
        {
            assertEquals("Wrong song name", si.getName(), songIt.next()
                    .getName());
        }
        assertEquals("Wrong number of artists", artists.size(), info2
                .getArtists().size());
        Iterator<ArtistInfo> artIt = info2.getArtists().iterator();
        for (ArtistInfo ai : artists)
        {
            assertEquals("Wrong artist name", ai.getName(), artIt.next()
                    .getName());
        }
    }
}
