package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code SongDetailInfo}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongDetailInfo
{
    /** The object to be tested. */
    private SongDetailInfo info;

    @Before
    public void setUp() throws Exception
    {
        info = new SongDetailInfo();
    }

    /**
     * Tests whether the song ID can be queried as string.
     */
    @Test
    public void testGetIDAsString()
    {
        final String id = "testSongID";
        info.setSongID(id);
        assertEquals("Wrong ID string", id, info.getIDAsString());
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
     * Creates a map with test synonym data.
     *
     * @return the map
     */
    private static Map<String, String> createSynonyms()
    {
        Map<String, String> syns = new HashMap<String, String>();
        syns.put("s1", "syn1");
        syns.put("s2", "syn2");
        syns.put("s3", "another Synonym");
        return syns;
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        info.setSynonymData(createSynonyms());
        SongDetailInfo info2 = RemoteMediaStoreTestHelper.serialize(info);
        assertEquals("Wrong synonyms", createSynonyms(), info2.getSynonymData());
    }
}
