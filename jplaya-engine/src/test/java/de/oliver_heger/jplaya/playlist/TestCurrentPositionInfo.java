package de.oliver_heger.jplaya.playlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.jplaya.playlist.CurrentPositionInfo;
import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code CurrentPositionInfo}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestCurrentPositionInfo
{
    /** Constant for a position. */
    private static final long POS = 20110607082248L;

    /** Constant for a time. */
    private static final long TIME = 1111111L;

    /** The object to be tested. */
    private CurrentPositionInfo info;

    @Before
    public void setUp() throws Exception
    {
        info = new CurrentPositionInfo(POS, TIME);
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertEquals("Wrong position", POS, info.getPosition());
        assertEquals("Wrong time", TIME, info.getTime());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(info, info, true);
        CurrentPositionInfo info2 = new CurrentPositionInfo(POS, TIME);
        RemoteMediaStoreTestHelper.checkEquals(info, info2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        CurrentPositionInfo info2 = new CurrentPositionInfo(POS, TIME + 1);
        RemoteMediaStoreTestHelper.checkEquals(info, info2, false);
        info2 = new CurrentPositionInfo(POS - 1, TIME);
        RemoteMediaStoreTestHelper.checkEquals(info, info2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(info);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        String s = info.toString();
        assertTrue("Position not found: " + s, s.contains("position=" + POS));
        assertTrue("Time not found: " + s, s.contains("time=" + TIME));
    }
}
