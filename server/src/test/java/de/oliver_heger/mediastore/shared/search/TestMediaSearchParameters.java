package de.oliver_heger.mediastore.shared.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.RemoteMediaStoreTestHelper;

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

    /** Constant for the name of a test field. */
    private static final String FIELD = "field1";

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
        assertNull("Got an order definition", data.getOrderDefinition());
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
        initOrderDefinition();
    }

    /**
     * Initializes the order definition for the test instance.
     */
    private void initOrderDefinition()
    {
        List<OrderDef> order = new ArrayList<OrderDef>();
        OrderDef od = new OrderDef();
        od.setFieldName(FIELD);
        order.add(od);
        od = new OrderDef();
        od.setFieldName(FIELD + 2);
        od.setDescending(true);
        order.add(od);
        params.setOrderDefinition(order);
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
     * Tests the order definition with defaults if an order is defined.
     */
    @Test
    public void testGetOrderDefinitionDefaultDefined()
    {
        initTestInstance();
        OrderDef od = new OrderDef();
        od.setFieldName("other field");
        List<OrderDef> order = params.getOrderDefinitionDefault(od);
        assertEquals("Wrong number of order definitions", 2, order.size());
        assertEquals("Wrong field name (1)", FIELD, order.get(0).getFieldName());
        assertEquals("Wrong field name (2)", FIELD + 2, order.get(1)
                .getFieldName());
    }

    /**
     * Tests whether the default order definition is returned if no order
     * definition is contained in the object.
     */
    @Test
    public void testGetOrderDefinitionDefaultUndefined()
    {
        OrderDef od = new OrderDef();
        od.setFieldName("other field");
        List<OrderDef> order = params.getOrderDefinitionDefault(od);
        assertEquals("Wrong number of order definitions", 1, order.size());
        assertEquals("Wrong order definition", od, order.get(0));
    }

    /**
     * Tests getOrderDefinitionDefault() if there is neither an order definition
     * nor a default.
     */
    @Test
    public void testGetOrderDefinitionDefaultUndefinedNoDefault()
    {
        List<OrderDef> order = params.getOrderDefinitionDefault();
        assertTrue("Got an order definition", order.isEmpty());
    }

    /**
     * Tries to pass null as default order definition.
     */
    @Test(expected = NullPointerException.class)
    public void testGetOrderDefinitionUndefinedNullDefault()
    {
        params.getOrderDefinitionDefault((OrderDef[]) null);
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
        OrderDef od1 = new OrderDef();
        od1.setFieldName(FIELD);
        params.setOrderDefinition(Collections.singletonList(od1));
        OrderDef od2 = new OrderDef();
        od2.setFieldName(od1.getFieldName());
        d2.setOrderDefinition(Collections.singletonList(od2));
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
        d2.setClientParameter(params.getClientParameter());
        OrderDef od1 = new OrderDef();
        od1.setFieldName(FIELD);
        params.setOrderDefinition(Collections.singletonList(od1));
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        OrderDef od2 = new OrderDef();
        od2.setFieldName("otherField");
        d2.setOrderDefinition(Collections.singletonList(od2));
        RemoteMediaStoreTestHelper.checkEquals(params, d2, false);
        od2.setFieldName(od1.getFieldName());
        od2.setDescending(true);
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
     * Tests the equals() implementation in OrderDef for other objects.
     */
    @Test
    public void testOrderDefEqualsTrivial()
    {
        OrderDef od = new OrderDef();
        od.setFieldName("testField");
        RemoteMediaStoreTestHelper.checkEqualsTrivial(od);
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
        assertTrue(
                "No order definition: " + s,
                s.contains("orderDefinition = "
                        + params.getOrderDefinition().toString()));
        assertTrue("Order field 1 not found: " + s, s.contains(FIELD));
        assertTrue("Order field 2 not found: " + s,
                s.contains(FIELD + "2 DESC"));
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
        assertFalse("Found order definition: " + s,
                s.contains("orderDefinition"));
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
