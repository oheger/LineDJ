package de.oliver_heger.mediastore.shared.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;

/**
 * Test class for {@code MediaSearchParameters}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestMediaSearchParameters
{
    /** Constant for a test search text. */
    private static final String SEARCH_TEXT = "TestSearchText";

    /** Constant for a test client parameter. */
    private static final String CLIENT_PARAM = "TestClientParam";

    /** Constant for a test numeric value. */
    private static final int VALUE = 10;

    /** Constant for a different numeric value. */
    private static final int VALUE2 = 42;

    /** The object to be tested. */
    private MediaSearchParameters params;

    @Before
    public void setUp() throws Exception
    {
        params = new MediaSearchParameters();
    }

    /**
     * Helper method for testing an instance with default values.
     *
     * @param data the instance to be tested
     */
    private void checkDefaultInstance(MediaSearchParameters data)
    {
        assertNull("Got a search text", data.getSearchText());
        assertEquals("Got a start position", 0, data.getFirstResult());
        assertEquals("Got a limit", 0, data.getMaxResults());
        assertNull("Got a client parameter", data.getClientParameter());
    }

    /**
     * Initializes the test object with default values.
     */
    private void initTestInstance()
    {
        params.setSearchText(SEARCH_TEXT);
        params.setFirstResult(VALUE);
        params.setMaxResults(VALUE2);
        params.setClientParameter(CLIENT_PARAM);
    }

    /**
     * Tests whether an instance with default settings can be created.
     */
    @Test
    public void testCreateDefaults()
    {
        checkDefaultInstance(params);
    }

    /**
     * Tries to set a negative first result.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateWithFirstResultNegative()
    {
        params.setFirstResult(-1);
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(params, params, true);
        MediaSearchParameters d2 = new MediaSearchParameters();
        RemoteMediaStoreTestHelper.checkEquals(params, d2, true);
        params.setSearchText(SEARCH_TEXT);
        d2.setSearchText(SEARCH_TEXT);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, true);
        params.setFirstResult(VALUE);
        d2.setFirstResult(VALUE);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, true);
        params.setMaxResults(VALUE2);
        d2.setMaxResults(VALUE2);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, true);
        params.setClientParameter(CLIENT_PARAM);
        d2.setClientParameter(CLIENT_PARAM);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        MediaSearchParameters d2 = new MediaSearchParameters();
        params.setSearchText(SEARCH_TEXT);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        d2.setSearchText(SEARCH_TEXT + "_other");
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        d2.setSearchText(params.getSearchText());
        params.setFirstResult(VALUE);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        d2.setFirstResult(VALUE2);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        d2.setFirstResult(params.getFirstResult());
        params.setMaxResults(VALUE);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        d2.setMaxResults(VALUE2);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        d2.setMaxResults(params.getMaxResults());
        params.setClientParameter(CLIENT_PARAM);
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        d2.setClientParameter(CLIENT_PARAM + "_other");
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        RemoteMediaStoreTestHelper.checkEqualsTrivial(params);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        initTestInstance();
        String s = params.toString();
        assertTrue("No search text: " + s,
                s.contains("searchText = " + SEARCH_TEXT));
        assertTrue("No first result: " + s,
                s.contains("firstResult = " + VALUE));
        assertTrue("No max results: " + s, s.contains("maxResults = " + VALUE2));
        assertTrue("No client param: " + s,
                s.contains("clientParameter = " + CLIENT_PARAM));
    }

    /**
     * Tests the string representation for a mostly empty object.
     */
    @Test
    public void testToStringMinimal()
    {
        String s = params.toString();
        assertFalse("Found max results: " + s, s.contains("maxResults = "));
        assertFalse("Found client parameters: " + s,
                s.contains("clientParameter = "));
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        initTestInstance();
        RemoteMediaStoreTestHelper.checkSerialization(params);
    }
}
