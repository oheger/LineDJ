package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code Song}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSong
{
    /** Constant for the name of a test song. */
    private static final String SONG_NAME = "Garden Party";

    /** Constant for the artist ID. */
    private static final Long ARTIST = 42L;

    /** Constant for the duration of the song. */
    private static final Long DURATION = 5 * 60 * 1000L;

    /** Constant for the inception year. */
    private static final int YEAR = 1982;

    /** Constant for the track number. */
    private static final int TRACK = 3;

    /** Constant for the play count. */
    private static final int PLAY_COUNT = 12;

    /**
     * Creates a song object with test data.
     *
     * @return the test song
     */
    private Song createTestSong()
    {
        Song song = new Song();
        song.setArtistID(ARTIST);
        song.setDuration(DURATION);
        song.setInceptionYear(YEAR);
        song.setName(SONG_NAME);
        song.setPlayCount(PLAY_COUNT);
        song.setTrackNo(TRACK);
        song.setUserID(PersistenceTestHelper.USER);
        return song;
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        Song song = new Song();
        assertNull("Got an artist", song.getArtistID());
        assertNull("Got an inception year", song.getInceptionYear());
        assertNull("Got a duration", song.getDuration());
        assertNull("Got a track number", song.getTrackNo());
        assertNull("Got a name", song.getName());
        assertNull("Got a user", song.getUserID());
        assertEquals("Wrong play count", 0, song.getPlayCount());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        Song song1 = createTestSong();
        PersistenceTestHelper.checkEquals(song1, song1, true);
        Song song2 = createTestSong();
        PersistenceTestHelper.checkEquals(song1, song2, true);
        song2.setInceptionYear(null);
        PersistenceTestHelper.checkEquals(song1, song2, true);
        song2.setTrackNo(null);
        PersistenceTestHelper.checkEquals(song1, song2, true);
        song2.setPlayCount(0);
        PersistenceTestHelper.checkEquals(song1, song2, true);
        song2.setArtistID(null);
        song1.setArtistID(null);
        PersistenceTestHelper.checkEquals(song1, song2, true);
        song2.setDuration(null);
        song1.setDuration(null);
        PersistenceTestHelper.checkEquals(song1, song2, true);
        song2.setUserID(null);
        song1.setUserID(null);
        PersistenceTestHelper.checkEquals(song1, song2, true);
        song2.setName(SONG_NAME.toLowerCase());
        PersistenceTestHelper.checkEquals(song1, song2, true);
        song2.setName(null);
        song1.setName(null);
        PersistenceTestHelper.checkEquals(song1, song2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        Song song1 = new Song();
        Song song2 = new Song();
        song1.setName(SONG_NAME);
        PersistenceTestHelper.checkEquals(song1, song2, false);
        song2.setName(SONG_NAME + "_other");
        PersistenceTestHelper.checkEquals(song1, song2, false);
        song2.setName(SONG_NAME);
        song1.setDuration(DURATION);
        PersistenceTestHelper.checkEquals(song1, song2, false);
        song2.setDuration(DURATION.longValue() + 1);
        PersistenceTestHelper.checkEquals(song1, song2, false);
        song2.setDuration(DURATION);
        song1.setUserID(PersistenceTestHelper.USER);
        PersistenceTestHelper.checkEquals(song1, song2, false);
        song2.setUserID(PersistenceTestHelper.OTHER_USER);
        PersistenceTestHelper.checkEquals(song1, song2, false);
        song2.setUserID(song1.getUserID());
        song1.setArtistID(ARTIST);
        PersistenceTestHelper.checkEquals(song1, song2, false);
        song2.setArtistID(ARTIST + 1);
        PersistenceTestHelper.checkEquals(song1, song2, false);
    }

    /**
     * Tests equals() for other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        PersistenceTestHelper.checkEqualsTrivial(createTestSong());
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        String s = createTestSong().toString();
        assertTrue("Name not found: " + s, s.contains("name = " + SONG_NAME));
        assertTrue("Duration not found: " + s,
                s.contains("duration = " + DURATION + " ms"));
        assertTrue("Year not found: " + s,
                s.contains("inceptionYear = " + YEAR));
        assertTrue("Track number not found: " + s,
                s.contains("track = " + TRACK));
    }

    /**
     * Tests toString() if some properties are null.
     */
    @Test
    public void testToStringNullValues()
    {
        String s = new Song().toString();
        assertFalse("Got a duration: " + s, s.contains("duration = "));
        assertFalse("Got a year: " + s, s.contains("inceptionYear = "));
        assertFalse("Got a track: " + s, s.contains("track = "));
        assertTrue("Name not found: " + s, s.contains("name = null"));
    }

    /**
     * Tests whether an object can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        PersistenceTestHelper.checkSerialization(createTestSong());
    }
}
