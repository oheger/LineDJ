package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.event.shared.HasHandlers;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.CustomButton;
import com.google.gwt.user.client.ui.FlexTable;
import com.google.gwt.user.client.ui.HasWidgets;
import com.google.gwt.user.client.ui.PushButton;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.ImageResources;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;

/**
 * Test class for {@code OverviewTable}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OverviewTableTestGwt extends GWTTestCase
{
    /** Constant for a search text. */
    private static final String SEARCH_TEXT = "TextToSearch*";

    /** Constant for the number of columns in the test table. */
    private static final int COL_COUNT = 3;

    /** Constant for the column header prefix. */
    private static final String COL_HEADER = "Header";

    /** Constant for the cell data prefix. */
    private static final String CELL_DATA = "cell_data_";

    /** Constant for a label for multiple element handlers. */
    private static final String LABEL = "Action_";

    /** Constant for the number of element handlers. */
    private static final int HANDLER_COUNT = 4;

    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Generates a cell data string for the specified position.
     *
     * @param row the row index
     * @param col the column index
     * @return the string for this cell
     */
    private static String generateCellData(int row, int col)
    {
        StringBuilder buf = new StringBuilder(CELL_DATA);
        buf.append('(').append(row).append(',').append(col).append(')');
        return buf.toString();
    }

    /**
     * Tests whether the object was correctly initialized.
     */
    public void testInit()
    {
        OverviewTable table = new OverviewTable();
        assertNotNull("No table", table.table);
        assertNotNull("No search button", table.btnSearch);
        assertNotNull("No refresh button", table.btnRefresh);
        assertNotNull("No text field", table.txtSearch);
        assertNotNull("No progress panel", table.pnlSearchProgress);
        assertNotNull("No result count label", table.labResultCount);
        assertNotNull("No error panel", table.pnlError);
        assertNotNull("No panel for multi handlers", table.pnlMultiHandlers);
    }

    /**
     * Tests whether the correct UI-related updates are performed when a search
     * request is initiated.
     */
    public void testHandleSearchClickUIUpdates()
    {
        OverviewTable table = new OverviewTable();
        table.table.setText(10, 10, "text");
        table.table.setVisible(false);
        table.pnlSearchProgress.setVisible(false);
        table.labResultCount.setVisible(true);
        table.pnlError.displayError(new RuntimeException());
        table.handleSearchClick(null);
        assertTrue("Table not visible", table.table.isVisible());
        assertEquals("Table not flushed", 0, table.table.getRowCount());
        assertFalse("Result label visible", table.labResultCount.isVisible());
        assertTrue("Progress panel not visible",
                table.pnlSearchProgress.isVisible());
        assertFalse("In error state", table.pnlError.isInErrorState());
        assertFalse("Search button enabled", table.btnSearch.isEnabled());
    }

    /**
     * Tests whether the search listener is called in reaction on a click to the
     * search button and that the correct search text is passed.
     */
    public void testHandleSearchClickListenerSearchText()
    {
        OverviewTable table = new OverviewTable();
        table.txtSearch.setText(SEARCH_TEXT);
        SearchListenerTestImpl listener = new SearchListenerTestImpl(table);
        table.setSearchListener(listener);
        table.handleSearchClick(null);
        MediaSearchParameters params = listener.nextParameters();
        assertEquals("Wrong search text", SEARCH_TEXT, params.getSearchText());
        assertSame("Wrong latest parameters", params,
                table.getLatestSearchParameters());
        listener.verify();
    }

    /**
     * Tests the search parameters object passed to the listener if no search
     * text was entered.
     */
    public void testHandleSearchClickListenerNoText()
    {
        OverviewTable table = new OverviewTable();
        SearchListenerTestImpl listener = new SearchListenerTestImpl(table);
        table.setSearchListener(listener);
        table.handleSearchClick(null);
        MediaSearchParameters params = listener.nextParameters();
        assertNull("Got a search text", params.getSearchText());
        listener.verify();
    }

    /**
     * Tests the default search parameters created by the table.
     */
    public void testCreateDefaultSearchParameters()
    {
        OverviewTable table = new OverviewTable();
        MediaSearchParameters params = table.createDefaultSearchParameters();
        assertNull("Got a search text", params.getSearchText());
        assertEquals("Got an offset", 0, params.getFirstResult());
    }

    /**
     * Helper method for testing a refresh operation. The boolean parameter
     * determines whether the refresh() method should be called directly or a
     * click on the refresh button should be simulated.
     *
     * @param button true for testing a button click, false for direct
     *        invocation
     */
    private void checkRefresh(boolean button)
    {
        OverviewTable table = new OverviewTable();
        table.txtSearch.setText(SEARCH_TEXT);
        table.handleSearchClick(null);
        SearchListenerTestImpl listener = new SearchListenerTestImpl(table);
        table.setSearchListener(listener);
        table.txtSearch.setText("a completely different text!");
        if (button)
        {
            table.handleRefreshClick(null);
        }
        else
        {
            table.refresh();
        }
        MediaSearchParameters params = listener.nextParameters();
        assertEquals("Wrong search text", SEARCH_TEXT, params.getSearchText());
        listener.verify();
    }

    /**
     * Tests the refresh() method.
     */
    public void testRefresh()
    {
        checkRefresh(false);
    }

    /**
     * Tests whether the refresh button is correctly handled.
     */
    public void testHandleRefreshClick()
    {
        checkRefresh(true);
    }

    /**
     * Tests whether default parameters are used if there are no latest search
     * parameters. (This can happen when the table is initialized.)
     */
    public void testHandleRefreshClickNoLatestParameters()
    {
        OverviewTable table = new OverviewTable();
        SearchListenerTestImpl listener = new SearchListenerTestImpl(table);
        table.setSearchListener(listener);
        table.handleRefreshClick(null);
        assertEquals("Wrong search parameters",
                table.createDefaultSearchParameters(),
                listener.nextParameters());
        listener.verify();
    }

    /**
     * Tests the content of the overview table.
     *
     * @param table the table to check
     * @param rowCount the expected number of rows
     */
    private void checkTableContent(OverviewTable table, int rowCount)
    {
        FlexTable ft = table.table;
        assertEquals("Wrong number of rows", rowCount + 1, ft.getRowCount());
        assertEquals("Wrong number of columns", COL_COUNT + 1,
                ft.getCellCount(0));
        assertEquals("Wrong header at 0", "", ft.getText(0, 0));
        for (int i = 0; i < COL_COUNT; i++)
        {
            assertEquals("Wrong header at " + i, COL_HEADER + i,
                    ft.getText(0, i + 1));
        }
        for (int i = 0; i < rowCount; i++)
        {
            assertEquals("Wrong ID at " + i, Long.valueOf(i),
                    table.getElementID(i + 1));
            for (int j = 0; j < COL_COUNT; j++)
            {
                assertEquals("Wrong cell data", generateCellData(i, j),
                        ft.getText(i + 1, j + 1));
            }
            Widget widget = ft.getWidget(i + 1, 0);
            assertEquals("Wrong checkbox widget", CheckBox.class,
                    widget.getClass());
        }
        assertEquals("Wrong header style", "overviewTableHeader", ft
                .getRowFormatter().getStyleName(0));
        assertTrue("Progress panel not visible",
                table.pnlSearchProgress.isVisible());
    }

    /**
     * Tests whether new search results can be added.
     */
    public void testAddSearchResults()
    {
        OverviewTable table = new OverviewTable();
        table.pnlSearchProgress.setVisible(true);
        final int rowCount = 10;
        ResultDataTestImpl data = new ResultDataTestImpl(rowCount, 0);
        table.addSearchResults(data, null);
        checkTableContent(table, rowCount);
    }

    /**
     * Tests whether search results can be added multiple times.
     */
    public void testAddSearchResultsMultiple()
    {
        OverviewTable table = new OverviewTable();
        table.pnlSearchProgress.setVisible(true);
        final int rowCount1 = 10;
        final int rowCount2 = 8;
        ResultDataTestImpl data1 = new ResultDataTestImpl(rowCount1, 0);
        table.addSearchResults(data1, null);
        ResultDataTestImpl data2 = new ResultDataTestImpl(rowCount2, rowCount1);
        table.addSearchResults(data2, null);
        checkTableContent(table, rowCount1 + rowCount2);
    }

    /**
     * Tests whether a new search requests clears all data in the table.
     */
    public void testAddSearchResultsAfterNewSearchRequest()
    {
        OverviewTable table = new OverviewTable();
        ResultDataTestImpl data = new ResultDataTestImpl(42, 0);
        table.addSearchResults(data, null);
        table.handleSearchClick(null);
        final int rowCount = 10;
        data = new ResultDataTestImpl(rowCount, 0);
        table.addSearchResults(data, null);
        checkTableContent(table, rowCount);
    }

    /**
     * Tries to access the ID of an element in an invalid row.
     */
    public void testGetElementIDInvalidIndex()
    {
        OverviewTable table = new OverviewTable();
        table.addSearchResults(new ResultDataTestImpl(10, 0), null);
        try
        {
            table.getElementID(11);
            fail("Invalid element ID not detected!");
        }
        catch (IndexOutOfBoundsException iobex)
        {
            // ok
        }
    }

    /**
     * Tests the ID returned for the header row.
     */
    public void testGetElementIDRow0()
    {
        OverviewTable table = new OverviewTable();
        table.addSearchResults(new ResultDataTestImpl(8, 0), null);
        assertNull("Got an ID for the header row", table.getElementID(0));
    }

    /**
     * Tests whether an error message can be displayed.
     */
    public void testOnFailure()
    {
        OverviewTable table = new OverviewTable();
        table.pnlSearchProgress.setVisible(true);
        table.table.setVisible(true);
        table.btnSearch.setEnabled(false);
        Throwable t = new RuntimeException();
        table.onFailure(t, null);
        assertTrue("Error panel not visible", table.pnlError.isVisible());
        assertFalse("Progress panel still visible",
                table.pnlSearchProgress.isVisible());
        assertFalse("Table still visible", table.table.isVisible());
        assertTrue("Not in error state", table.pnlError.isInErrorState());
        assertEquals("Wrong error", t, table.pnlError.getError());
        assertTrue("Search button not enabled", table.btnSearch.isEnabled());
    }

    /**
     * Tests whether the UI is updated correspondingly if search is complete.
     */
    public void testSearchComplete()
    {
        // TODO test paging
        OverviewTable table = new OverviewTable();
        table.pnlError.displayError(new RuntimeException());
        table.pnlSearchProgress.setVisible(true);
        table.btnSearch.setEnabled(false);
        table.searchComplete(null, null, false);
        assertFalse("In error state", table.pnlError.isInErrorState());
        assertFalse("Progress panel visible",
                table.pnlSearchProgress.isVisible());
        assertTrue("Search button not enabled", table.btnSearch.isEnabled());
    }

    /**
     * Tests whether the correct set of controls is disabled during a search
     * operation.
     */
    public void testEnableUIDuringSearch()
    {
        OverviewTable table = new OverviewTable();
        table.enableUIDuringSearch(false);
        assertFalse("Search button not disabled", table.btnSearch.isEnabled());
        assertFalse("Refresh button not disabled", table.btnRefresh.isEnabled());
    }

    /**
     * Helper method for firing a click event on a widget.
     *
     * @param btn the widget
     */
    private static void fireClickEvent(HasHandlers btn)
    {
        NativeEvent clickEvent =
                Document.get().createClickEvent(0, 0, 0, 0, 0, false, false,
                        false, false);
        DomEvent.fireNativeEvent(clickEvent, btn);
    }

    /**
     * Tests whether a single element handler can be added and is served
     * correctly.
     */
    public void testAddSingleElementHandler()
    {
        OverviewTable table = new OverviewTable();
        ImageResources res = GWT.create(ImageResources.class);
        SingleElementHandlerTestImpl h = new SingleElementHandlerTestImpl();
        table.addSingleElementHandler(res.viewDetails(), h);
        ResultDataTestImpl data = new ResultDataTestImpl(1, 0);
        table.addSearchResults(data, null);
        FlexTable ft = table.table;
        assertEquals("Wrong number of columns", COL_COUNT + 2,
                ft.getCellCount(1));
        HasWidgets widgets = (HasWidgets) ft.getWidget(1, COL_COUNT + 1);
        Iterator<Widget> widgetIt = widgets.iterator();
        CustomButton btn = (CustomButton) widgetIt.next();
        assertFalse("Too many widgets", widgetIt.hasNext());
        fireClickEvent(btn);
        assertEquals("Wrong ID passed to handler", Long.valueOf(0),
                h.getElementID());
    }

    /**
     * Helper method for adding some single element handlers to the table.
     *
     * @param table the test table
     * @return an array with the handlers that have been added
     */
    private SingleElementHandlerTestImpl[] addSingleElementHandlers(
            OverviewTable table)
    {
        ImageResources res = GWT.create(ImageResources.class);
        SingleElementHandlerTestImpl[] handlers =
                new SingleElementHandlerTestImpl[HANDLER_COUNT];
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            handlers[i] = new SingleElementHandlerTestImpl();
            table.addSingleElementHandler(res.viewDetails(), handlers[i]);
        }
        return handlers;
    }

    /**
     * Tests whether multiple single element handlers can be added and whether
     * they are stored in the expected order.
     */
    public void testAddSingleElementHandlerMultiple()
    {
        OverviewTable table = new OverviewTable();
        SingleElementHandlerTestImpl[] handlers =
                addSingleElementHandlers(table);
        ResultDataTestImpl data = new ResultDataTestImpl(HANDLER_COUNT + 1, 0);
        table.addSearchResults(data, null);
        FlexTable ft = table.table;
        assertEquals("Wrong number of columns", COL_COUNT + 2,
                ft.getCellCount(1));
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            HasWidgets widgets =
                    (HasWidgets) ft.getWidget(i + 1, COL_COUNT + 1);
            Iterator<Widget> widgetIt = widgets.iterator();
            for (int j = 0; j < i; j++)
            {
                widgetIt.next();
            }
            CustomButton btn = (CustomButton) widgetIt.next();
            fireClickEvent(btn);
        }
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            assertEquals("Wrong ID passed to handler", Long.valueOf(i),
                    handlers[i].getElementID());
        }
    }

    /**
     * Tests whether single element handlers can be queried.
     */
    public void testGetSingleElementHandlers()
    {
        OverviewTable table = new OverviewTable();
        SingleElementHandlerTestImpl[] handlers =
                addSingleElementHandlers(table);
        SingleElementHandler[] regHandlers = table.getSingleElementHandlers();
        assertTrue("Wrong handlers", Arrays.equals(handlers, regHandlers));
    }

    /**
     * Tests whether single element handlers can be queried if there are none.
     */
    public void testGetSingleElementHandlersEmpty()
    {
        OverviewTable table = new OverviewTable();
        SingleElementHandler[] regHandlers = table.getSingleElementHandlers();
        assertEquals("Got handlers", 0, regHandlers.length);
    }

    /**
     * Tests whether multiple element handlers can be queried if there are none.
     */
    public void testGetMultiElementHandlersEmpty()
    {
        OverviewTable table = new OverviewTable();
        assertEquals("Got multi handlers", 0,
                table.getMultiElementHandlers().length);
    }

    /**
     * Adds a number of test handlers for multiple elements.
     *
     * @param table the target table
     * @return an array with the handlers that have been added
     */
    private MultiElementHandlerTestImpl[] addMultiElementHandlers(
            OverviewTable table)
    {
        MultiElementHandlerTestImpl[] result =
                new MultiElementHandlerTestImpl[HANDLER_COUNT];
        ImageResources res = GWT.create(ImageResources.class);
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            result[i] = new MultiElementHandlerTestImpl();
            table.addMultiElementHandler(res.removeItem(), LABEL + i, result[i]);
        }
        return result;
    }

    /**
     * Retrieves the button for the multiple element handler with the given
     * index.
     *
     * @param table the table
     * @param i the index
     * @return the button associated with this multiple element handler
     */
    private PushButton fetchMultiHandlerButton(OverviewTable table, int i)
    {
        return (PushButton) table.pnlMultiHandlers.getWidget(i);
    }

    /**
     * Tests whether multiple element handlers can be added.
     */
    public void testAddMultiElementHandler()
    {
        OverviewTable table = new OverviewTable();
        addMultiElementHandlers(table);
        assertEquals("Wrong number of buttons", HANDLER_COUNT,
                table.pnlMultiHandlers.getWidgetCount());
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            PushButton btn = fetchMultiHandlerButton(table, i);
            assertEquals("Wrong label", LABEL + i, btn.getText());
            assertFalse("Enabled", btn.isEnabled());
        }
    }

    /**
     * Helper method for checking the enabled state of the table's multiple
     * element handlers.
     *
     * @param table the overview table
     * @param expFlag the expected enabled flag
     */
    private void checkMultiHandlerEnabled(OverviewTable table, boolean expFlag)
    {
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            assertEquals("Wrong enabled flag at " + i, expFlag,
                    fetchMultiHandlerButton(table, i).isEnabled());
        }
    }

    /**
     * Simulates a click on the check box for the specified row.
     *
     * @param table the overview table
     * @param row the row of the check box to be clicked
     */
    private void clickCheckBox(OverviewTable table, int row)
    {
        CheckBox cb = (CheckBox) table.table.getWidget(row + 1, 0);
        cb.setValue(!cb.getValue().booleanValue());
        fireClickEvent(cb);
    }

    /**
     * Simulates clicks on multiple check boxes.
     *
     * @param table the overview table
     * @param rows an array with the row indices whose check boxes are to be
     *        clicked
     */
    private void clickCheckBoxes(OverviewTable table, int... rows)
    {
        for (int r : rows)
        {
            clickCheckBox(table, r);
        }
    }

    /**
     * Tests whether the buttons for multiple element handlers are enabled based
     * on the selection.
     */
    public void testMultiElementHandlerEnabled()
    {
        OverviewTable table = new OverviewTable();
        addMultiElementHandlers(table);
        ResultDataTestImpl data = new ResultDataTestImpl(16, 0);
        table.addSearchResults(data, null);
        checkMultiHandlerEnabled(table, false);
        clickCheckBoxes(table, 1, 5, 7);
        checkMultiHandlerEnabled(table, true);
        clickCheckBoxes(table, 1, 5, 7);
        checkMultiHandlerEnabled(table, false);
    }

    /**
     * Tests whether the handlers for multiple elements are disabled when the
     * table is refreshed or new data is loaded.
     */
    public void testMultiElementHandlerDisabledOnRefresh()
    {
        OverviewTable table = new OverviewTable();
        addMultiElementHandlers(table);
        ResultDataTestImpl data = new ResultDataTestImpl(16, 0);
        table.addSearchResults(data, null);
        clickCheckBoxes(table, 1, 5, 7);
        table.refresh();
        checkMultiHandlerEnabled(table, false);
    }

    /**
     * Tests whether a handler for multiple elements can be executed.
     */
    public void testMultiElementHandlerExecute()
    {
        OverviewTable table = new OverviewTable();
        MultiElementHandlerTestImpl[] handlers = addMultiElementHandlers(table);
        ResultDataTestImpl data = new ResultDataTestImpl(16, 0);
        table.addSearchResults(data, null);
        int[] indices = new int[] {
                0, 2, 4, 6
        };
        clickCheckBoxes(table, indices);
        fireClickEvent(fetchMultiHandlerButton(table, 0));
        assertEquals("Wrong number of IDs", indices.length, handlers[0]
                .getElementIDs().size());
        for (int idx : indices)
        {
            assertTrue("Element ID not found: " + idx, handlers[0]
                    .getElementIDs().contains(Long.valueOf(idx)));
        }
    }

    /**
     * A test implementation of a search listener for testing whether the
     * expected search events are received.
     */
    private static class SearchListenerTestImpl implements SearchListener
    {
        /** Stores the received search parameters. */
        private final List<MediaSearchParameters> searchParameters;

        /** Stores the expected source of the search request. */
        private final OverviewTable expectedSource;

        /**
         * Creates a new instance of {@code SearchListenerTestImpl} and sets the
         * expected overview table acting as source of search requests.
         *
         * @param src the source table
         */
        public SearchListenerTestImpl(OverviewTable src)
        {
            expectedSource = src;
            searchParameters = new LinkedList<MediaSearchParameters>();
        }

        /**
         * Returns the next parameter objects received by this listener.
         *
         * @return the next search parameters object
         */
        public MediaSearchParameters nextParameters()
        {
            assertFalse("Too few search paameters", searchParameters.isEmpty());
            return searchParameters.remove(0);
        }

        /**
         * Verifies that all search events have been processed.
         */
        public void verify()
        {
            assertTrue("Too many search parameters", searchParameters.isEmpty());
        }

        /**
         * Records this invocation.
         */
        @Override
        public void searchRequest(OverviewTable source,
                MediaSearchParameters params)
        {
            assertEquals("Wrong source", expectedSource, source);
            searchParameters.add(params);
        }
    }

    /**
     * A test implementation of ResultData which can be used for testing whether
     * the table is correctly populated. The object is initialized with the
     * number of table rows and a start index. Based on this information it
     * generates the content dynamically.
     */
    private static class ResultDataTestImpl implements ResultData
    {
        /** The size of this model. */
        private final int size;

        /** The initial offset. */
        private final int offset;

        /**
         * Creates a new instance of {@code ResultDataTestImpl}.
         *
         * @param rowCount the number of rows in the model
         * @param startIdx the start index of the first row
         */
        public ResultDataTestImpl(int rowCount, int startIdx)
        {
            size = rowCount;
            offset = startIdx;
        }

        @Override
        public int getColumnCount()
        {
            return COL_COUNT;
        }

        @Override
        public int getRowCount()
        {
            return size;
        }

        @Override
        public String getColumnName(int idx)
        {
            checkColumnIndex(idx);
            return COL_HEADER + idx;
        }

        @Override
        public String getValueAt(int row, int col)
        {
            checkRowIndex(row);
            checkColumnIndex(col);
            return generateCellData(row + offset, col);
        }

        /**
         * Returns a generated ID. The ID is simply the row index as Long
         * object.
         */
        @Override
        public Object getID(int row)
        {
            checkRowIndex(row);
            return Long.valueOf(row + offset);
        }

        /**
         * Tests whether a valid column index was provided.
         *
         * @param idx the index to check
         */
        private void checkColumnIndex(int idx)
        {
            assertTrue("Invalid column index: " + idx, idx >= 0
                    && idx < getColumnCount());
        }

        /**
         * Tests whether a valid row index was provided.
         *
         * @param idx the index to check
         */
        private void checkRowIndex(int idx)
        {
            assertTrue("Invalid row index: " + idx, idx >= 0
                    && idx < getRowCount());
        }
    }

    /**
     * A test implementation of a single element handler for testing whether the
     * handler is called with the expected ID.
     */
    private static class SingleElementHandlerTestImpl implements
            SingleElementHandler
    {
        /** Stores the ID passed to the handler. */
        private Object elementID;

        /**
         * Returns the element ID passed to this object.
         *
         * @return the element ID
         */
        public Object getElementID()
        {
            return elementID;
        }

        /**
         * Records this invocation and stores the ID.
         */
        @Override
        public void handleElement(Object elemID)
        {
            elementID = elemID;
        }
    }

    /**
     * A tests implementation of a multiple element handler which is used to
     * check whether the expected IDs are passed.
     */
    private static class MultiElementHandlerTestImpl implements
            MultiElementHandler
    {
        /** Stores the passed in element IDs. */
        private Set<Object> ids;

        /**
         * Returns the set with the IDs that was passed to this handler.
         *
         * @return the set with element IDs
         */
        public Set<Object> getElementIDs()
        {
            return ids;
        }

        /**
         * Records this invocation and stores the IDs.
         */
        @Override
        public void handleElements(Set<Object> elemIDs)
        {
            ids = elemIDs;
        }
    }
}
