package de.oliver_heger.mediastore.shared.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.easymock.EasyMock;
import org.junit.Test;

import de.oliver_heger.mediastore.shared.RemoteMediaStoreTestHelper;

/**
 * Test class for {@code SearchResultImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSearchResultImpl
{
    /** Constant for the number of test objects. */
    private static final int RESULT_COUNT = 16;

    /**
     * Creates a list with test results.
     *
     * @return the list
     */
    private static List<Integer> createResultList()
    {
        List<Integer> result = new ArrayList<Integer>(RESULT_COUNT);
        for (int i = 0; i < RESULT_COUNT; i++)
        {
            result.add(Integer.valueOf(i));
        }
        return result;
    }

    /**
     * Helper method for testing the result list of a result object.
     *
     * @param resultList the actual results
     */
    private static void checkResults(List<Integer> resultList)
    {
        List<Integer> expected = createResultList();
        assertEquals("Wrong number of results", expected.size(),
                resultList.size());
        Iterator<Integer> it = resultList.iterator();
        for (Integer value : expected)
        {
            assertEquals("Wrong value", value, it.next());
        }
    }

    /**
     * Tests whether all properties can be correctly initialized.
     */
    @Test
    public void testInit()
    {
        SearchIterator searchIt = EasyMock.createNiceMock(SearchIterator.class);
        MediaSearchParameters params = new MediaSearchParameters();
        SearchResultImpl<Integer> result =
                new SearchResultImpl<Integer>(createResultList(), searchIt,
                        params);
        assertSame("Wrong search iterator", searchIt,
                result.getSearchIterator());
        assertSame("Wrong search parameters", params,
                result.getSearchParameters());
        checkResults(result.getResults());
    }

    /**
     * Tests whether a null collection can be passed to the constructor.
     */
    @Test
    public void testInitNullCollection()
    {
        SearchResultImpl<Integer> result =
                new SearchResultImpl<Integer>(null, null, null);
        assertTrue("Wrong result list", result.getResults().isEmpty());
    }

    /**
     * Tests whether a defensive copy of the result list is created when a new
     * object is constructed.
     */
    @Test
    public void testInitModifyResultList()
    {
        List<Integer> list = createResultList();
        SearchResultImpl<Integer> result =
                new SearchResultImpl<Integer>(list, null, null);
        list.add(RESULT_COUNT);
        checkResults(result.getResults());
    }

    /**
     * Tests equals() if the expected result is true.
     */
    @Test
    public void testEqualsTrue()
    {
        SearchResultImpl<Integer> r1 =
                new SearchResultImpl<Integer>(null, null, null);
        SearchResultImpl<Integer> r2 =
                new SearchResultImpl<Integer>(null, null, null);
        RemoteMediaStoreTestHelper.checkEquals(r1, r2, true);
        r1 =
                new SearchResultImpl<Integer>(createResultList(),
                        new SearchIteratorImpl(), new MediaSearchParameters());
        r2 =
                new SearchResultImpl<Integer>(createResultList(),
                        new SearchIteratorImpl(), new MediaSearchParameters());
        RemoteMediaStoreTestHelper.checkEquals(r1, r2, true);
        RemoteMediaStoreTestHelper.checkEquals(r1, r1, true);
    }

    /**
     * Tests equals() if the expected result is false.
     */
    @Test
    public void testEqualsFalse()
    {
        SearchResultImpl<Integer> r1 =
                new SearchResultImpl<Integer>(createResultList(),
                        new SearchIteratorImpl(), new MediaSearchParameters());
        SearchResultImpl<Integer> r2 =
                new SearchResultImpl<Integer>(null, r1.getSearchIterator(),
                        r1.getSearchParameters());
        RemoteMediaStoreTestHelper.checkEquals(r1, r2, false);
        List<Integer> resultList = createResultList();
        resultList.add(42);
        r2 =
                new SearchResultImpl<Integer>(resultList,
                        r1.getSearchIterator(), r1.getSearchParameters());
        RemoteMediaStoreTestHelper.checkEquals(r1, r2, false);
        r2 =
                new SearchResultImpl<Integer>(createResultList(), null,
                        r1.getSearchParameters());
        RemoteMediaStoreTestHelper.checkEquals(r1, r2, false);
        SearchIteratorImpl sit = new SearchIteratorImpl();
        sit.setCurrentPosition(RESULT_COUNT);
        r2 =
                new SearchResultImpl<Integer>(r1.getResults(), sit,
                        r1.getSearchParameters());
        RemoteMediaStoreTestHelper.checkEquals(r1, r2, false);
        r2 =
                new SearchResultImpl<Integer>(r1.getResults(),
                        r1.getSearchIterator(), null);
        RemoteMediaStoreTestHelper.checkEquals(r1, r2, false);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("testSearch");
        r2 =
                new SearchResultImpl<Integer>(r1.getResults(),
                        r1.getSearchIterator(), params);
        RemoteMediaStoreTestHelper.checkEquals(r1, r2, false);
    }

    /**
     * Tests equals() with other objects.
     */
    @Test
    public void testEqualsTrivial()
    {
        SearchResultImpl<Integer> result =
                new SearchResultImpl<Integer>(createResultList(), null, null);
        RemoteMediaStoreTestHelper.checkEqualsTrivial(result);
    }

    /**
     * Tests the string representation.
     */
    @Test
    public void testToString()
    {
        List<Integer> resultList = createResultList();
        MediaSearchParameters params = new MediaSearchParameters();
        SearchIteratorImpl sit = new SearchIteratorImpl();
        SearchResultImpl<Integer> result =
                new SearchResultImpl<Integer>(resultList, sit, params);
        String s = result.toString();
        assertTrue("Search iterator not found: " + s,
                s.contains("searchIterator = " + sit));
        assertTrue("Params not found: " + s,
                s.contains("searchParameters = " + params));
        assertTrue("Results not found: " + s,
                s.contains("results = " + resultList));
    }

    /**
     * Tests a string representation if no properties are set.
     */
    @Test
    public void testToStringMinimum()
    {
        String s = new SearchResultImpl<Integer>(null, null, null).toString();
        assertTrue("Wrong string start: " + s,
                s.startsWith("SearchResultImpl@"));
        assertTrue("Wrong string end: " + s, s.endsWith("[ ]"));
    }

    /**
     * Tests whether the result list is stripped.
     */
    @Test
    public void testToStringResultsStripped()
    {
        List<Integer> resultList = createResultList();
        resultList.addAll(Arrays.asList(100, 2000, 3000, 44444, 5555555));
        SearchResultImpl<Integer> result =
                new SearchResultImpl<Integer>(resultList, null, null);
        String s = result.toString();
        String exp =
                "[ results = " + resultList.size() + " elements starting with "
                        + createResultList() + " ]";
        assertTrue("Results not found: " + s, s.endsWith(exp));
    }

    /**
     * Tests whether a result object can be serialized.
     */
    @Test
    public void testSerialization() throws IOException
    {
        SearchIteratorImpl sit = new SearchIteratorImpl();
        sit.setRecordCount(RESULT_COUNT);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setMaxResults(RESULT_COUNT);
        params.setSearchText("Test");
        SearchResultImpl<Integer> result =
                new SearchResultImpl<Integer>(createResultList(), sit, params);
        RemoteMediaStoreTestHelper.checkSerialization(result);
    }
}
