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
 * Test class for {@code AlbumInfo}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumInfo
{
    /** The info object to be tested. */
    private AlbumInfo info;

    @Before
    public void setUp() throws Exception
    {
        info = new AlbumInfo();
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertNull("Got an ID", info.getAlbumID());
        assertNull("Got a name", info.getName());
        assertNull("Got a creation date", info.getCreationDate());
        assertNull("Got a duration", info.getDuration());
        assertNull("Got an inception year", info.getInceptionYear());
        assertEquals("Wrong number of songs", 0, info.getNumberOfSongs());
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        info.setAlbumID(20110123174840L);
        info.setName("Test album");
        info.setCreationDate(new Date());
        String s = info.toString();
        assertTrue("ID not found: " + s,
                s.contains("albumID = " + info.getAlbumID()));
        assertTrue("Name not found: " + s,
                s.contains("name = " + info.getName()));
        assertTrue("Date not found: " + s,
                s.contains("creationDate = " + info.getCreationDate()));
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        info.setAlbumID(20110123175135L);
        info.setName("Blow it out of your ass");
        AlbumInfo info2 = RemoteMediaStoreTestHelper.serialize(info);
        assertEquals("Wrong ID", info.getAlbumID(), info2.getAlbumID());
        assertEquals("Wrong name", info.getName(), info2.getName());
    }

    /**
     * Tests whether the formatted duration can be queried.
     */
    @Test
    public void testGetFormattedDuration()
    {
        info.setDuration((74 * 60 + 12) * 1000L);
        assertEquals("Wrong formatted duration", "1:14:12",
                info.getFormattedDuration());
    }
}
