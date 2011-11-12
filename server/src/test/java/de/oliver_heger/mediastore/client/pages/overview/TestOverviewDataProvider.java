package de.oliver_heger.mediastore.client.pages.overview;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.easymock.EasyMock;
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
        EasyMock.expect(
                getCallbackFactory().createParameterSearchCallback(
                        getSearchService(), getQueryHandler(), data))
                .andReturn(callback);
        prepareOrderProvider();
        getQueryHandler().executeQuery(getSearchService(), params, null,
                callback);
        replay(data, callback);
        OverviewDataProvider<ArtistInfo> provider = createProvider();
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
}
