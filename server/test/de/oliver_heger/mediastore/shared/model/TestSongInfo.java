package de.oliver_heger.mediastore.shared.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code SongInfo}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongInfo
{
    /** Constant for the song duration. */
    private static final long DURATION = (8 * 60 + 40) * 1000L;

    /** The info object to be tested. */
    private SongInfo info;

    @Before
    public void setUp() throws Exception
    {
        info = new SongInfo();
    }

    /**
     * Fills the given object with test data.
     *
     * @param si the object to be populated
     */
    private void populate(SongInfo si)
    {
        si.setCreationDate(new Date());
        si.setName("Music");
        si.setDuration(DURATION);
        si.setInceptionYear(1984);
        si.setPlayCount(111);
        si.setArtistName("John Miles");
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertNull("Got a name", info.getName());
        assertNull("Got a creation date", info.getCreationDate());
        assertNull("Got an artist ID", info.getArtistID());
        assertNull("Got an artist name", info.getArtistName());
        assertEquals("Wrong play count", 0, info.getPlayCount());
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        populate(info);
        String s = info.toString();
        assertTrue("Name not found: " + s,
                s.contains("name = " + info.getName()));
        assertTrue("Artist not found: " + s,
                s.contains("artist = " + info.getArtistName()));
        assertTrue("Play count not found: " + s,
                s.contains("playCount = " + info.getPlayCount()));
        assertTrue("Year not found: " + s,
                s.contains("inceptionYear = " + info.getInceptionYear()));
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        populate(info);
        SongInfo si = RemoteMediaStoreTestHelper.serialize(info);
        assertEquals("Wrong name", info.getName(), si.getName());
        assertEquals("Wrong creation date", info.getCreationDate(),
                si.getCreationDate());
        assertEquals("Wrong duration", info.getDuration(), si.getDuration());
        assertEquals("Wrong year", info.getInceptionYear(),
                si.getInceptionYear());
        assertEquals("Wrong count", info.getPlayCount(), si.getPlayCount());
        assertEquals("Wrong artist", info.getArtistName(), si.getArtistName());
    }

    /**
     * Tests whether a formatted duration can be queried if the duration is not
     * set.
     */
    @Test
    public void testGetFormattedDurationNull()
    {
        assertNull("Wrong formatted duration", info.getFormattedDuration());
    }

    /**
     * Tests whether the duration can be formatted.
     */
    @Test
    public void testGetFormattedDuration()
    {
        populate(info);
        assertEquals("Wrong formatted duration", "08:40",
                info.getFormattedDuration());
    }
}
