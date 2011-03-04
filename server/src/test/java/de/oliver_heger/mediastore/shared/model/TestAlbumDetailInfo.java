package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
     * Tests whether an empty set is returned if there are no synonyms.
     */
    @Test
    public void testGetSynonymsEmpty()
    {
        assertTrue("Got synonyms", info.getSynonyms().isEmpty());
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
        Set<String> synonyms = new HashSet<String>();
        synonyms.add("syn1");
        synonyms.add("syn2");
        info.setSynonyms(synonyms);
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
        assertEquals("Wrong synonyms", synonyms, info2.getSynonyms());
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
