package de.oliver_heger.mediastore.server.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code SyncResultImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSyncResultImpl
{
    /** Constant for a test key. */
    private static final Long KEY = 20101120162655L;

    /** The object to be tested. */
    private SyncResultImpl<Long> result;

    @Before
    public void setUp() throws Exception
    {
        result = new SyncResultImpl<Long>(KEY, true);
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertEquals("Wrong key", KEY, result.getKey());
        assertTrue("Wrong import flag", result.imported());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        SyncResultImpl<Long> r2 = new SyncResultImpl<Long>(KEY, true);
        RemoteMediaStoreTestHelper.checkEquals(result, result, true);
        RemoteMediaStoreTestHelper.checkEquals(result, r2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        SyncResultImpl<Long> r2 = new SyncResultImpl<Long>(null, true);
        RemoteMediaStoreTestHelper.checkEquals(result, r2, false);
        r2 = new SyncResultImpl<Long>(Long.valueOf(KEY.longValue() + 1), true);
        RemoteMediaStoreTestHelper.checkEquals(result, r2, false);
        r2 = new SyncResultImpl<Long>(KEY, false);
        RemoteMediaStoreTestHelper.checkEquals(result, r2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(result);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        RemoteMediaStoreTestHelper.checkToString(result, "key = " + KEY
                + " imported = true");
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        RemoteMediaStoreTestHelper.checkSerialization(result);
    }
}
