package de.oliver_heger.mediastore.server.model;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code SongSynonym}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongSynonym
{
    /** The synonym to be tested. */
    private SongSynonym synonym;

    @Before
    public void setUp() throws Exception
    {
        synonym = new SongSynonym();
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(synonym, synonym, true);
        SongSynonym syn2 = new SongSynonym();
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, true);
        SongEntity song = new SongEntity();
        synonym.setSong(song);
        syn2.setSong(song);
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        SongEntity song1 = new SongEntity();
        song1.setName("song1");
        SongEntity song2 = new SongEntity();
        song2.setName("song2");
        synonym.setSong(song1);
        SongSynonym syn2 = new SongSynonym();
        syn2.setSong(song2);
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, false);
        syn2.setSong(synonym.getSong());
        syn2.setName("aSynonym");
        RemoteMediaStoreTestHelper.checkEquals(synonym, syn2, false);
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        synonym.setName("TestSongSyn");
        synonym.setSong(new SongEntity());
        RemoteMediaStoreTestHelper.checkSerialization(synonym);
    }
}
