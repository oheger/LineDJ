package de.oliver_heger.mediastore.client.pages.overview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.HasData;
import com.google.gwt.view.client.Range;

import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.OrderDef;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code OverviewDataProvider}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestOverviewDataProvider
{
    /** Constant for the number of test results of a search. */
    private static final int RESULT_COUNT = 16;

    /** A list with order definition objects. */
    private static List<OrderDef> orderDefinitions;

    /** A mock for the search service. */
    private MediaSearchServiceAsync service;

    /** A mock for the callback factory. */
    private OverviewCallbackFactory<ArtistInfo> factory;

    /** A mock for the query handler. */
    private OverviewQueryHandler<ArtistInfo> queryHandler;

    /** A mock for the order definition provider. */
    private OrderDefinitionProvider orderProvider;

    @BeforeClass
    public static void setUpBeforeClass()
    {
        List<OrderDef> defs = new ArrayList<OrderDef>();
        OrderDef od = new OrderDef();
        od.setFieldName("testfield");
        od.setDescending(true);
        defs.add(od);
        orderDefinitions = Collections.unmodifiableList(defs);
    }

    /**
     * Returns a mock for the search service. It is created on demand.
     *
     * @return the search service mock
     */
    private MediaSearchServiceAsync getSearchService()
    {
        if (service == null)
        {
            service = EasyMock.createMock(MediaSearchServiceAsync.class);
        }
        return service;
    }

    /**
     * Returns a mock for the callback factory. It is created on demand.
     *
     * @return the factory mock
     */
    private OverviewCallbackFactory<ArtistInfo> getCallbackFactory()
    {
        if (factory == null)
        {
            @SuppressWarnings("unchecked")
            OverviewCallbackFactory<ArtistInfo> temp =
                    EasyMock.createMock(OverviewCallbackFactory.class);
            factory = temp;
        }
        return factory;
    }

    /**
     * Returns a mock for the query handler. It is created on demand.
     *
     * @return the mock query handler
     */
    private OverviewQueryHandler<ArtistInfo> getQueryHandler()
    {
        if (queryHandler == null)
        {
            @SuppressWarnings("unchecked")
            OverviewQueryHandler<ArtistInfo> temp =
                    EasyMock.createMock(OverviewQueryHandler.class);
            queryHandler = temp;
        }
        return queryHandler;
    }

    /**
     * Returns a mock for the order provider. It is created on demand.
     *
     * @return the mock order provider
     */
    private OrderDefinitionProvider getOrderProvider()
    {
        if (orderProvider == null)
        {
            orderProvider = EasyMock.createMock(OrderDefinitionProvider.class);
        }
        return orderProvider;
    }

    /**
     * Replays the specified mock object if it is not <b>null</b>.
     *
     * @param mock the mock to be replayed
     */
    private static void replayMock(Object mock)
    {
        if (mock != null)
        {
            EasyMock.replay(mock);
        }
    }

    /**
     * Verifies the specified mock object if it is not <b>null</b>.
     *
     * @param mock the mock to be verified
     */
    private static void verifyMock(Object mock)
    {
        if (mock != null)
        {
            EasyMock.verify(mock);
        }
    }

    /**
     * Replays the specified mock objects. The mocks managed by this test class
     * are automatically replayed.
     *
     * @param mocks the (additional) mocks to be replayed
     */
    private void replay(Object... mocks)
    {
        EasyMock.replay(mocks);
        replayMock(service);
        replayMock(factory);
        replayMock(queryHandler);
        replayMock(orderProvider);
    }

    /**
     * Verifies the specified mock objects. The mocks managed by this test class
     * are automatically verified.
     *
     * @param mocks the (additional) mocks to be verified
     */
    private void verify(Object... mocks)
    {
        verifyMock(service);
        verifyMock(factory);
        verifyMock(queryHandler);
        verifyMock(orderProvider);
        EasyMock.verify(mocks);
    }

    /**
     * Creates a mock for the cell widget.
     *
     * @return the mock widget
     */
    private static HasData<ArtistInfo> createHasData()
    {
        @SuppressWarnings("unchecked")
        HasData<ArtistInfo> data = EasyMock.createMock(HasData.class);
        return data;
    }

    /**
     * Creates a mock for a callback object.
     *
     * @return the mock callback
     */
    private static AsyncCallback<SearchResult<ArtistInfo>> createCallback()
    {
        @SuppressWarnings("unchecked")
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                EasyMock.createMock(AsyncCallback.class);
        return callback;
    }

    /**
     * Creates a test provider instance.
     *
     * @return the test object
     */
    private OverviewDataProvider<ArtistInfo> createProvider()
    {
        return new OverviewDataProvider<ArtistInfo>(getSearchService(),
                getQueryHandler(), getCallbackFactory(), getOrderProvider());
    }

    /**
     * Helper method for testing a search request if no search text is provided.
     *
     * @param txt the search text
     */
    private void checkSearchNoText(String txt)
    {
        HasData<ArtistInfo> data = createHasData();
        AsyncCallback<SearchResult<ArtistInfo>> callback = createCallback();
        Range range = new Range(10, 20);
        EasyMock.expect(data.getVisibleRange()).andReturn(range);
        EasyMock.expect(getCallbackFactory().createSimpleSearchCallback(data))
                .andReturn(callback);
        prepareOrderProvider();
        MediaSearchParameters params = new MediaSearchParameters();
        params.setFirstResult(range.getStart());
        params.setMaxResults(range.getLength());
        params.setOrderDefinition(orderDefinitions);
        getQueryHandler().executeQuery(getSearchService(), params, null,
                callback);
        replay(data, callback);
        OverviewDataProvider<ArtistInfo> provider = createProvider();
        provider.setSearchText(txt);
        provider.onRangeChanged(data);
        verify(data, callback);
    }

    /**
     * Tests a search operation if no text is provided.
     */
    @Test
    public void testSearchNoText()
    {
        checkSearchNoText(null);
    }

    /**
     * Tests a search operation if an empty text is provided.
     */
    @Test
    public void testSearchEmptyText()
    {
        checkSearchNoText("  ");
    }

    /**
     * Helper method for testing a search operation with a search text.
     *
     * @return the provider test object
     */
    private OverviewDataProvider<ArtistInfo> checkSearchWithText()
    {
        HasData<ArtistInfo> data = createHasData();
        AsyncCallback<SearchResult<ArtistInfo>> callback = createCallback();
        final String searchText = "Hello";
        MediaSearchParameters params = new MediaSearchParameters();
        params.setOrderDefinition(orderDefinitions);
        params.setSearchText(searchText);
        params.setClientParameter(Long.valueOf(1));
        OverviewDataProvider<ArtistInfo> provider = createProvider();
        data.setRowCount(0);
        EasyMock.expect(
                getCallbackFactory().createParameterSearchCallback(
                        getSearchService(), getQueryHandler(), data, provider))
                .andReturn(callback);
        prepareOrderProvider();
        getQueryHandler().executeQuery(getSearchService(), params, null,
                callback);
        replay(data, callback);
        provider.setSearchText(" " + searchText + "  ");
        provider.onRangeChanged(data);
        verify(data, callback);
        return provider;
    }

    /**
     * Prepares the order provider mock to return the default order definitions.
     */
    private void prepareOrderProvider()
    {
        EasyMock.expect(getOrderProvider().getOrderDefinitions()).andReturn(
                orderDefinitions);
    }

    /**
     * Tests a search if a search text has been entered.
     */
    @Test
    public void testSearchWithText()
    {
        checkSearchWithText();
    }

    /**
     * Tests that a new search is started only if search parameters were changed
     * in the meantime.
     */
    @Test
    public void testRefreshRequiredNoChanges()
    {
        OverviewDataProvider<ArtistInfo> provider = checkSearchWithText();
        EasyMock.reset(getOrderProvider());
        prepareOrderProvider();
        EasyMock.replay(getOrderProvider());
        assertFalse("Wrong result", provider.refreshRequired());
        EasyMock.verify(getOrderProvider());
    }

    /**
     * Tests whether a new search is started if the order definition changes.
     */
    @Test
    public void testRefreshRequiredNewOrderDef()
    {
        OverviewDataProvider<ArtistInfo> provider = checkSearchWithText();
        EasyMock.reset(getOrderProvider());
        OrderDef od = new OrderDef();
        od.setFieldName("AnotherField");
        EasyMock.expect(getOrderProvider().getOrderDefinitions()).andReturn(
                Collections.singletonList(od));
        EasyMock.replay(getOrderProvider());
        assertTrue("Wrong result", provider.refreshRequired());
        EasyMock.verify(getOrderProvider());
    }

    /**
     * Tests whether a new search is started if the search text has changed.
     */
    @Test
    public void testRefreshRequiredNewText()
    {
        OverviewDataProvider<ArtistInfo> provider = checkSearchWithText();
        provider.setSearchText("new search Text!");
        assertTrue("Wrong result", provider.refreshRequired());
    }

    /**
     * Tests whether a new search can be enforced by calling refresh().
     */
    @Test
    public void testRefresh()
    {
        OverviewDataProvider<ArtistInfo> provider = checkSearchWithText();
        provider.refresh();
        assertTrue("Wrong result", provider.refreshRequired());
    }

    /**
     * Creates a list with the given number of artists that can be used as
     * search result.
     *
     * @param startIdx the index of the first artist to create
     * @param count the number of result objects
     * @return the list with artist objects
     */
    private static List<ArtistInfo> createSearchResults(int startIdx, int count)
    {
        List<ArtistInfo> result = new ArrayList<ArtistInfo>(count);
        for (int i = 0; i < count; i++)
        {
            ArtistInfo info = new ArtistInfo();
            info.setArtistID(Long.valueOf(startIdx + i));
            result.add(info);
        }
        return result;
    }

    /**
     * Helper method for testing the standard use case of receiving data from
     * the server and updating the widget's data. The range of a widget request
     * for new data can be specified. It is possible that this request has to be
     * corrected if indices are out of range.
     *
     * @param visibleRange the visible range of the widget
     * @param expectedEndIdx the expected corrected end index
     */
    @SuppressWarnings("unchecked")
    private void checkSearchResultsReceivedAndAccessCachedData(
            Range visibleRange, int expectedEndIdx)
    {
        OverviewDataProvider<ArtistInfo> provider = checkSearchWithText();
        HasData<ArtistInfo> widget = createHasData();
        widget.setRowCount(RESULT_COUNT);
        widget.setRowData(EasyMock.eq(0), EasyMock.anyObject(List.class));
        EasyMock.expectLastCall().andAnswer(
                new RowDataAnswer(0, RESULT_COUNT - 1));
        widget.setRowCount(2 * RESULT_COUNT);
        widget.setRowData(EasyMock.eq(RESULT_COUNT),
                EasyMock.anyObject(List.class));
        EasyMock.expectLastCall().andAnswer(
                new RowDataAnswer(RESULT_COUNT, 2 * RESULT_COUNT - 1));
        EasyMock.expect(widget.getVisibleRange()).andReturn(visibleRange);
        widget.setRowData(EasyMock.eq(visibleRange.getStart()),
                EasyMock.anyObject(List.class));
        EasyMock.expectLastCall().andAnswer(
                new RowDataAnswer(visibleRange.getStart(), expectedEndIdx));
        EasyMock.reset(getOrderProvider());
        prepareOrderProvider();
        EasyMock.replay(getOrderProvider(), widget);
        Object param = 1L;
        provider.searchResultsReceived(createSearchResults(0, RESULT_COUNT),
                widget, param);
        provider.searchResultsReceived(
                createSearchResults(RESULT_COUNT, RESULT_COUNT), widget, param);
        provider.onRangeChanged(widget);
        EasyMock.verify(getOrderProvider(), widget);
    }

    /**
     * Tests whether data passed to searchResultsReceived() is correctly
     * processed and stored so that it can be used to populate a widget later.
     */
    @Test
    public void testSearchResultsReceivedAndAccessCachedData()
    {
        checkSearchResultsReceivedAndAccessCachedData(new Range(
                RESULT_COUNT / 2, RESULT_COUNT), RESULT_COUNT / 2
                + RESULT_COUNT - 1);
    }

    /**
     * Tests whether the visible range of the widget is corrected if necessary
     * when display data is queried.
     */
    @Test
    public void testAccessCachedDataInvalidRange()
    {
        checkSearchResultsReceivedAndAccessCachedData(new Range(10,
                10 * RESULT_COUNT), 2 * RESULT_COUNT - 1);
    }

    /**
     * Tests that the client parameter of a search is taken into account when
     * results are retrieved from the server.
     */
    @Test
    public void testSearchResultsRecivedWrongParameter()
    {
        HasData<ArtistInfo> widget = createHasData();
        OverviewDataProvider<ArtistInfo> provider = createProvider();
        replay(widget);
        provider.searchResultsReceived(createSearchResults(0, RESULT_COUNT),
                widget, "wrong parameter");
        verify(widget);
    }

    /**
     * A special answer implementation for testing whether the widget is
     * populated with the expected data. An instance is initialized with a range
     * of artist IDs. The object checks whether the data list passed to the
     * widget contains exactly these IDs.
     */
    private static class RowDataAnswer implements IAnswer<Void>
    {
        /** The start of the range. */
        private final int from;

        /** The end of the range. */
        private final int to;

        /**
         * Creates a new instance of {@code RowDataAnswer} and initializes it
         * with the expected range.
         *
         * @param expFrom the expected start range
         * @param expTo the expected end range
         */
        public RowDataAnswer(int expFrom, int expTo)
        {
            from = expFrom;
            to = expTo;
        }

        /**
         * Checks the range.
         */
        @Override
        public Void answer() throws Throwable
        {
            @SuppressWarnings("unchecked")
            List<ArtistInfo> data =
                    (List<ArtistInfo>) EasyMock.getCurrentArguments()[1];
            Iterator<ArtistInfo> it = data.iterator();
            for (int i = from; i <= to; i++)
            {
                ArtistInfo info = it.next();
                assertEquals("Wrong artist", Long.valueOf(i),
                        info.getArtistID());
            }
            assertFalse("Too many items", it.hasNext());
            return null;
        }

    }
}
