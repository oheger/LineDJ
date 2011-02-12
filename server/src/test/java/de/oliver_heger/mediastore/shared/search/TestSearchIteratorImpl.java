package de.oliver_heger.mediastore.shared.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code SearchIteratorImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSearchIteratorImpl
{
    /** Constant for the record count. */
    private static final long REC_COUNT = 10254;

    /** Constant for the current position. */
    private static final long POSITION = 4444;

    /** Constant for the size of a page. */
    private static final int PAGE_SIZE = 100;

    /** Constant for the page index. */
    private static final Integer PAGE = (int) (POSITION / PAGE_SIZE);

    /** Constant for the page count. */
    private static final Integer PAGE_COUNT = (int) (REC_COUNT / PAGE_SIZE) + 1;

    /** The object to be tested. */
    private SearchIteratorImpl iterator;

    @Before
    public void setUp() throws Exception
    {
        iterator = new SearchIteratorImpl();
    }

    /**
     * Initializes the test object with default values.
     *
     * @param it the instance to be initialized
     */
    private void initTestInstance(SearchIteratorImpl it)
    {
        it.setCurrentPosition(POSITION);
        it.setHasNext(true);
        it.setRecordCount(REC_COUNT);
        it.initializePaging(PAGE, PAGE_COUNT);
    }

    /**
     * Tests a newly created instance.
     */
    @Test
    public void testInit()
    {
        assertEquals("Wrong current position", 0, iterator.getCurrentPosition());
        assertEquals("Wrong record count", 0, iterator.getRecordCount());
        assertFalse("Has next", iterator.hasNext());
        assertNull("Got a current page", iterator.getCurrentPage());
        assertNull("Got page count", iterator.getPageCount());
    }

    /**
     * Tests whether paging information can be set.
     */
    @Test
    public void testInitializePaging()
    {
        iterator.initializePaging(PAGE, PAGE_COUNT);
        assertEquals("Wrong current page", PAGE, iterator.getCurrentPage());
        assertEquals("Wrong page count", PAGE_COUNT, iterator.getPageCount());
    }

    /**
     * Tries to initialize paging information with a null page.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitializePagingNullPage()
    {
        iterator.initializePaging(null, PAGE_COUNT);
    }

    /**
     * Tries to initialize paging information with a null page count.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitializePagingNullCount()
    {
        iterator.initializePaging(PAGE, null);
    }

    /**
     * Tests whether paging information can be set to null.
     */
    @Test
    public void testInitializePagingNull()
    {
        initTestInstance(iterator);
        iterator.initializePaging(null, null);
        assertNull("Got a current page", iterator.getCurrentPage());
        assertNull("Got page count", iterator.getPageCount());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        RemoteMediaStoreTestHelper.checkEquals(iterator, iterator, true);
        SearchIteratorImpl it2 = new SearchIteratorImpl();
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, true);
        initTestInstance(iterator);
        initTestInstance(it2);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        SearchIteratorImpl it2 = new SearchIteratorImpl();
        iterator.setCurrentPosition(POSITION);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, false);
        it2.setCurrentPosition(POSITION + 1);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, false);
        it2.setCurrentPosition(POSITION);
        iterator.setRecordCount(REC_COUNT);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, false);
        it2.setRecordCount(REC_COUNT + 1);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, false);
        it2.setRecordCount(REC_COUNT);
        iterator.setHasNext(true);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, false);
        it2.setHasNext(true);
        iterator.initializePaging(PAGE, PAGE_COUNT);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, false);
        it2.initializePaging(PAGE, PAGE_COUNT + 1);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, false);
        it2.initializePaging(PAGE + 1, PAGE_COUNT);
        RemoteMediaStoreTestHelper.checkEquals(iterator, it2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        initTestInstance(iterator);
        RemoteMediaStoreTestHelper.checkEqualsTrivial(iterator);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        initTestInstance(iterator);
        String s = iterator.toString();
        assertTrue("Position not found: " + s,
                s.contains("currentPosition = " + POSITION));
        assertTrue("Record count not found: " + s,
                s.contains("recordCount = " + REC_COUNT));
        assertTrue("Has next not found: " + s, s.contains("hasNext = true"));
        assertTrue("Page index not found: " + s,
                s.contains("currentPage = " + PAGE));
        assertTrue("Page count not found: " + s,
                s.contains("pageCount = " + PAGE_COUNT));
    }

    /**
     * Tests whether empty fields are not present in the string representation.
     */
    @Test
    public void testToStringEmptyFields()
    {
        String s = iterator.toString();
        assertFalse("Got current page: " + s, s.contains("currentPage ="));
        assertFalse("Got page count: " + s, s.contains("pageCount ="));
    }

    /**
     * Tests whether an instance can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        initTestInstance(iterator);
        RemoteMediaStoreTestHelper.checkSerialization(iterator);
    }
}
