package de.olix.playa.engine;

import java.io.IOException;

import junit.framework.TestCase;

/**
 * Test class for EndAudioStreamData.
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class TestEndAudioStreamData extends TestCase
{
    /** Stores the instance to be tested. */
    private EndAudioStreamData data;

    protected void setUp() throws Exception
    {
        super.setUp();
        data = new EndAudioStreamData();
    }

    /**
     * Tests the name returned by the stream data object.
     */
    public void testGetName()
    {
        assertEquals("Wrong stream name", EndAudioStreamData.END_STREAM_NAME,
                data.getName());
    }

    /**
     * Tests the returned position. This should always be 0.
     */
    public void testGetPosition()
    {
        assertEquals("Wrong position", 0, data.getPosition());
    }

    /**
     * Tests the returned stream. Here null is expected.
     */
    public void testGetStream() throws IOException
    {
        assertNull("A stream was returned", data.getStream());
    }

    /**
     * Tests the size() method. Here a value less than 0 must be returned.
     */
    public void testSize()
    {
        assertTrue("Wrong stream size", data.size() < 0);
    }

    /**
     * Tests whether the correct ID is returned.
     */
    public void testGetID()
    {
        assertEquals("Wrong stream ID", EndAudioStreamData.END_STREAM_ID, data
                .getID());
    }
}
