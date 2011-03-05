package de.oliver_heger.mediastore.localstore.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.localstore.JPATestHelper;

/**
 * Test class for {@code SongEntity}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongEntity
{
    /** Constant for the song name. */
    private static final String SONG_NAME = "Crisis";

    /** Constant for the duration of the song. */
    private static final Integer DURATION = 20 * 60 + 43;

    /** The test helper. */
    private JPATestHelper helper;

    /** The song entity to be tested. */
    private SongEntity song;

    @Before
    public void setUp() throws Exception
    {
        helper = new JPATestHelper();
        song = new SongEntity();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.close();
    }

    /**
     * Helper method for initializing the test song's properties with default
     * values.
     */
    private void initSong()
    {
        song.setName(SONG_NAME);
        song.setDuration(DURATION);
        song.setInceptionYear(1980);
        song.setTrackNo(1);
        song.setPlayCount(2);
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(song, song, true);
        SongEntity s2 = new SongEntity();
        RemoteMediaStoreTestHelper.checkEquals(song, s2, true);
        song.setName(SONG_NAME);
        s2.setName(SONG_NAME.toLowerCase(Locale.ENGLISH));
        RemoteMediaStoreTestHelper.checkEquals(song, s2, true);
        song.setDuration(DURATION);
        s2.setDuration(DURATION);
        RemoteMediaStoreTestHelper.checkEquals(song, s2, true);
        song.setInceptionYear(2011);
        song.setPlayCount(42);
        song.setTrackNo(2);
        RemoteMediaStoreTestHelper.checkEquals(song, s2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        SongEntity s2 = new SongEntity();
        song.setName(SONG_NAME);
        RemoteMediaStoreTestHelper.checkEquals(song, s2, false);
        s2.setName(SONG_NAME + "_other");
        RemoteMediaStoreTestHelper.checkEquals(song, s2, false);
        s2.setName(SONG_NAME);
        song.setDuration(DURATION);
        RemoteMediaStoreTestHelper.checkEquals(song, s2, false);
        s2.setDuration(DURATION + 1);
        RemoteMediaStoreTestHelper.checkEquals(song, s2, false);
        s2.setDuration(DURATION);
        // TODO add tests with artist
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(song);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        initSong();
        String s = song.toString();
        assertTrue("Name not found: " + s, s.contains("name=" + SONG_NAME));
        assertTrue("Duration not found: " + s,
                s.contains("duration=" + DURATION));
        // TODO check artist
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        initSong();
        SongEntity s2 = RemoteMediaStoreTestHelper.serialize(song);
        assertEquals("Wrong inception year", song.getInceptionYear(),
                s2.getInceptionYear());
        assertEquals("Wrong track number", song.getTrackNo(), s2.getTrackNo());
        assertEquals("Wrong play count", song.getPlayCount(), s2.getPlayCount());
    }

    /**
     * Tests whether an entity can be persisted.
     */
    @Test
    public void testPersist()
    {
        initSong();
        helper.persist(song, true);
        assertNotNull("No song ID", song.getId());
        SongEntity s2 = helper.getEM().find(SongEntity.class, song.getId());
        assertEquals("Wrong song name", SONG_NAME, s2.getName());
        assertEquals("Wrong duration", DURATION, s2.getDuration());
        assertEquals("Wrong inception year", song.getInceptionYear(),
                s2.getInceptionYear());
        assertEquals("Wrong track number", song.getTrackNo(), s2.getTrackNo());
        assertEquals("Wrong play count", 2, s2.getPlayCount());
    }
}
