package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code ArtistDetailInfo}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistDetailInfo
{
    /** The object to be tested. */
    private ArtistDetailInfo info;

    @Before
    public void setUp() throws Exception
    {
        info = new ArtistDetailInfo();
    }

    /**
     * Tests whether a string representation of the ID can be queried.
     */
    @Test
    public void testGetIDAsString()
    {
        final Long id = 20110831211610L;
        info.setArtistID(id);
        assertEquals("Wrong ID", id.toString(), info.getIDAsString());
    }

    /**
     * Tests getIDAsString() if there is no ID.
     */
    @Test
    public void testGetIDAsStringUndefined()
    {
        assertNull("Got an ID", info.getIDAsString());
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
     * Tests whether an empty list is returned if there are no albums.
     */
    @Test
    public void testGetAlbumsEmpty()
    {
        assertTrue("Got albums", info.getAlbums().isEmpty());
    }

    /**
     * Creates a map with some test synonyms.
     *
     * @return the map with synonym data
     */
    private static Map<String, String> createSynonyms()
    {
        final String[] synonyms = {
                "Art1", "The Artist"
        };
        final String key = "synKey";
        Map<String, String> syns = new HashMap<String, String>();
        for (int i = 0; i < synonyms.length; i++)
        {
            syns.put(key + i, synonyms[i]);
        }
        return syns;
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        final String[] songNames = {
                "Song1", "Another Song", "The new song"
        };
        final String[] albumNames = {
            "MyAlbum"
        };
        info.setName("Artist");
        info.setSynonymData(createSynonyms());
        List<SongInfo> songs = new ArrayList<SongInfo>(songNames.length);
        for (String songName : songNames)
        {
            SongInfo song = new SongInfo();
            song.setName(songName);
            songs.add(song);
        }
        info.setSongs(songs);
        List<AlbumInfo> albums = new ArrayList<AlbumInfo>(albumNames.length);
        for (String albumName : albumNames)
        {
            AlbumInfo album = new AlbumInfo();
            album.setName(albumName);
            albums.add(album);
        }
        info.setAlbums(albums);
        ArtistDetailInfo info2 = RemoteMediaStoreTestHelper.serialize(info);
        assertEquals("Wrong synonyms", createSynonyms(), info2.getSynonymData());
        assertEquals("Wrong number of songs", songNames.length, info2
                .getSongs().size());
        for (int i = 0; i < songNames.length; i++)
        {
            assertEquals("Wrong song at " + i, songNames[i], info2.getSongs()
                    .get(i).getName());
        }
        assertEquals("Wrong number of albums", albumNames.length, info2
                .getAlbums().size());
        for (int i = 0; i < albumNames.length; i++)
        {
            assertEquals("Wrong album at " + i, albumNames[i], info2
                    .getAlbums().get(i).getName());
        }
    }
}
