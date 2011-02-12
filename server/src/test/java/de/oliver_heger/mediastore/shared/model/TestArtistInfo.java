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
 * Test class for {@code ArtistInfo}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistInfo
{
    /** Constant for the artist ID. */
    private static final Long ID = 20101127211334L;

    /** Constant for the artist name. */
    private static final String NAME = "TestArtist";

    /** Constant for the creation date. */
    private static final Date CREATION_DATE = new Date();

    /** The object to be tested. */
    private ArtistInfo info;

    @Before
    public void setUp() throws Exception
    {
        info = new ArtistInfo();
    }

    /**
     * Writes test data into the specified instance.
     *
     * @param ai the object to be populated
     */
    private void populate(ArtistInfo ai)
    {
        ai.setArtistID(ID);
        ai.setCreationDate(CREATION_DATE);
        ai.setName(NAME);
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertNull("Got an ID", info.getArtistID());
        assertNull("Got a name", info.getName());
        assertNull("Got a creation date", info.getCreationDate());
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        populate(info);
        String s = info.toString();
        assertTrue("ID not found: " + s, s.contains("artistID = " + ID));
        assertTrue("Name not found: " + s, s.contains("name = " + NAME));
        assertTrue("Creation date not found:" + s,
                s.contains("creationDate = " + CREATION_DATE));
    }

    /**
     * Tests whether an object can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        populate(info);
        ArtistInfo info2 = RemoteMediaStoreTestHelper.serialize(info);
        assertEquals("Wrong ID", ID, info2.getArtistID());
        assertEquals("Wrong name", NAME, info2.getName());
        assertEquals("Wrong date", CREATION_DATE, info2.getCreationDate());
    }
}
