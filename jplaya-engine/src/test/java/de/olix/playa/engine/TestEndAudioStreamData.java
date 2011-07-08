package de.olix.playa.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for EndAudioStreamData.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestEndAudioStreamData
{
    /** Stores the instance to be tested. */
    private EndAudioStreamData data;

    @Before
    public void setUp() throws Exception
    {
        data = new EndAudioStreamData();
    }

    /**
     * Tests the name returned by the stream data object.
     */
    @Test
    public void testGetName()
    {
        assertEquals("Wrong stream name", EndAudioStreamData.END_STREAM_NAME,
                data.getName());
    }

    /**
     * Tests the returned position. This should always be 0.
     */
    @Test
    public void testGetPosition()
    {
        assertEquals("Wrong position", 0, data.getPosition());
    }

    /**
     * Tests the returned stream. Here null is expected.
     */
    @Test
    public void testGetStream() throws IOException
    {
        assertNull("A stream was returned", data.getStream());
    }

    /**
     * Tests the size() method. Here a value less than 0 must be returned.
     */
    @Test
    public void testSize()
    {
        assertTrue("Wrong stream size", data.size() < 0);
    }

    /**
     * Tests whether the correct ID is returned.
     */
    @Test
    public void testGetID()
    {
        assertEquals("Wrong stream ID", EndAudioStreamData.END_STREAM_ID,
                data.getID());
    }

    /**
     * Tests the index returned by the data object.
     */
    @Test
    public void testGetIndex()
    {
        assertEquals("Wrong index",
                EndAudioStreamData.END_STREAM_ID.intValue(), data.getIndex());
    }
}
