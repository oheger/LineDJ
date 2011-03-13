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

    /** Constant for the name of the test artist. */
    private static final String ARTIST_NAME = "Mike Oldfield";

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
        song.setCurrentPlayCount(2);
    }

    /**
     * Returns a test artist instance.
     *
     * @return the test artist
     */
    private ArtistEntity createTestArtist()
    {
        ArtistEntity art = new ArtistEntity();
        art.setName(ARTIST_NAME);
        return art;
    }

    /**
     * Tests some initialization values.
     */
    @Test
    public void testInit()
    {
        assertEquals("Wrong current count", 0, song.getCurrentPlayCount());
        assertEquals("Wrong total count", 0, song.getTotalPlayCount()
                .intValue());
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
        song.setCurrentPlayCount(42);
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
        song.setArtist(createTestArtist());
        RemoteMediaStoreTestHelper.checkEquals(song, s2, false);
        s2.setArtist(new ArtistEntity());
        RemoteMediaStoreTestHelper.checkEquals(song, s2, false);
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
        ArtistEntity art = createTestArtist();
        song.setArtist(art);
        String s = song.toString();
        assertTrue("Name not found: " + s, s.contains("name=" + SONG_NAME));
        assertTrue("Duration not found: " + s,
                s.contains("duration=" + DURATION));
        assertTrue("Artist not found: " + s, s.contains("artist=" + art));
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
        assertEquals("Wrong play count", song.getCurrentPlayCount(),
                s2.getCurrentPlayCount());
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
        assertEquals("Wrong play count", 2, s2.getCurrentPlayCount());
    }

    /**
     * Tests whether the total play count field is correctly initialized.
     */
    @Test
    public void testGetTotalPlayCountInit()
    {
        assertEquals("Wrong initial play count", 0, song.getTotalPlayCount()
                .intValue());
    }

    /**
     * Tests getTotalPlayCount() for a legacy record.
     */
    @Test
    public void testGetTotalPlayCountLegacy()
    {
        song.setTotalPlayCount(null);
        song.setCurrentPlayCount(5);
        assertEquals("Wrong total play count", song.getCurrentPlayCount(), song
                .getTotalPlayCount().intValue());
    }

    /**
     * Tests whether the play counters can be incremented.
     */
    @Test
    public void testIncrementPlayCount()
    {
        final int currentCount = 10;
        final int totalCount = 100;
        song.setCurrentPlayCount(currentCount);
        song.setTotalPlayCount(totalCount);
        song.incrementPlayCount();
        assertEquals("Wrong current count", currentCount + 1,
                song.getCurrentPlayCount());
        assertEquals("Wrong total count", totalCount + 1, song
                .getTotalPlayCount().intValue());
    }

    /**
     * Tests incrementPlayCount() for legacy data.
     */
    @Test
    public void testIncrementPlayCountLegacy()
    {
        final int currentCount = 10;
        song.setTotalPlayCount(null);
        song.setCurrentPlayCount(currentCount);
        song.incrementPlayCount();
        assertEquals("Wrong current count", currentCount + 1,
                song.getCurrentPlayCount());
        assertEquals("Wrong total count", currentCount + 1, song
                .getTotalPlayCount().intValue());
    }

    /**
     * Tests whether the current count can be reset.
     */
    @Test
    public void testResetCurrentCount()
    {
        final Integer count = 47;
        song.setCurrentPlayCount(count.intValue());
        song.setTotalPlayCount(count);
        song.resetCurrentCount();
        assertEquals("Wrong current count", 0, song.getCurrentPlayCount());
        assertEquals("Wrong total count", count, song.getTotalPlayCount());
    }

    /**
     * Tests resetCurrentCount() for legacy data.
     */
    @Test
    public void testResetCurrentCountLegacy()
    {
        final int count = 5;
        song.setCurrentPlayCount(count);
        song.setTotalPlayCount(null);
        song.resetCurrentCount();
        assertEquals("Wrong current count", 0, song.getCurrentPlayCount());
        assertEquals("Wrong total count", count, song.getTotalPlayCount()
                .intValue());
    }
}
