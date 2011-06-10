package de.olix.playa.playlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code KeepGroup}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestKeepGroup
{
    /** Constant for the prefix for song URIs. */
    private static final String SONG_URI = "MyTestSong_";

    /** Constant for the number of test songs. */
    private static final int COUNT = 8;

    /**
     * Generates a list with URIs for test songs which can be used to initialize
     * a keep group.
     *
     * @return the list with URIs
     */
    private static List<String> songList()
    {
        List<String> result = new ArrayList<String>(COUNT);
        for (int i = 0; i < COUNT; i++)
        {
            result.add(SONG_URI + i);
        }
        return result;
    }

    /**
     * Creates a test keep group instance.
     *
     * @return the test group
     */
    private static KeepGroup createGroup()
    {
        return new KeepGroup(songList());
    }

    /**
     * Tests whether the passed in group contains the expected songs.
     *
     * @param grp the group to be tested
     */
    private static void checkGroup(KeepGroup grp)
    {
        assertEquals("Wrong number of songs", COUNT, grp.size());
        for (int i = 0; i < COUNT; i++)
        {
            assertEquals("Wrong song URI at " + i, SONG_URI + i,
                    grp.getSongURI(i));
        }
    }

    /**
     * Tries to create an instance without a collection.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoList()
    {
        new KeepGroup(null);
    }

    /**
     * Tries to create an instance with not enough items.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitTooFewItems()
    {
        new KeepGroup(Collections.singleton(SONG_URI));
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        KeepGroup grp = createGroup();
        checkGroup(grp);
    }

    /**
     * Tests whether a defensive copy is created by the constructor.
     */
    @Test
    public void testInitDefensiveCopy()
    {
        List<String> list = songList();
        KeepGroup grp = new KeepGroup(list);
        list.add(SONG_URI + "new");
        checkGroup(grp);
    }

    /**
     * Tries to access a song at an invalid index.
     */
    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetSongURIInvalidIndex()
    {
        KeepGroup grp = createGroup();
        grp.getSongURI(COUNT);
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        KeepGroup grp1 = createGroup();
        RemoteMediaStoreTestHelper.checkEquals(grp1, grp1, true);
        KeepGroup grp2 = createGroup();
        RemoteMediaStoreTestHelper.checkEquals(grp1, grp2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        KeepGroup grp1 = createGroup();
        List<String> list = songList();
        list.remove(0);
        RemoteMediaStoreTestHelper
                .checkEquals(grp1, new KeepGroup(list), false);
        list = songList();
        list.set(COUNT - 1, SONG_URI + COUNT);
        RemoteMediaStoreTestHelper
                .checkEquals(grp1, new KeepGroup(list), false);
        list = songList();
        String s = list.get(0);
        list.set(0, list.get(1));
        list.set(1, s);
        RemoteMediaStoreTestHelper
                .checkEquals(grp1, new KeepGroup(list), false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(createGroup());
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        String s = createGroup().toString();
        assertTrue("List not found: " + s, s.contains("songs=" + songList()));
    }
}
