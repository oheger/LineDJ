package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.event.shared.HasHandlers;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.CustomButton;
import com.google.gwt.user.client.ui.FlexTable;
import com.google.gwt.user.client.ui.HasWidgets;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.ImageResources;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;

/**
 * Test class for {@code OverviewTable}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestOverviewTable extends GWTTestCase
{
    /** Constant for a search text. */
    private static final String SEARCH_TEXT = "TextToSearch*";

    /** Constant for the number of columns in the test table. */
    private static final int COL_COUNT = 3;

    /** Constant for the column header prefix. */
    private static final String COL_HEADER = "Header";

    /** Constant for the cell data prefix. */
    private static final String CELL_DATA = "cell_data_";

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
        assertNotNull("No button", table.btnSearch);
        assertNotNull("No text field", table.txtSearch);
        assertNotNull("No progress panel", table.pnlSearchProgress);
        assertNotNull("No result count label", table.labResultCount);
        assertNotNull("No error panel", table.pnlError);
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
     * Tests the content of the overview table.
     *
     * @param table the table to check
     * @param rowCount the expected number of rows
     */
    private void checkTableContent(OverviewTable table, int rowCount)
    {
        FlexTable ft = table.table;
        assertEquals("Wrong number of rows", rowCount + 1, ft.getRowCount());
        assertEquals("Wrong number of columns", COL_COUNT, ft.getCellCount(0));
        for (int i = 0; i < COL_COUNT; i++)
        {
            assertEquals("Wrong header at " + i, COL_HEADER + i,
                    ft.getText(0, i));
        }
        for (int i = 0; i < rowCount; i++)
        {
            for (int j = 0; j < COL_COUNT; j++)
            {
                assertEquals("Wrong cell data", generateCellData(i, j),
                        ft.getText(i + 1, j));
            }
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
     * Tests whether an error message can be displayed.
     */
    public void testOnFailure()
    {
        OverviewTable table = new OverviewTable();
        table.pnlSearchProgress.setVisible(true);
        table.table.setVisible(true);
        Throwable t = new RuntimeException();
        table.onFailure(t, null);
        assertTrue("Error panel not visible", table.pnlError.isVisible());
        assertFalse("Progress panel still visible",
                table.pnlSearchProgress.isVisible());
        assertFalse("Table still visible", table.table.isVisible());
        assertTrue("Not in error state", table.pnlError.isInErrorState());
        assertEquals("Wrong error", t, table.pnlError.getError());
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
        table.searchComplete(null, null, false);
        assertFalse("In error state", table.pnlError.isInErrorState());
        assertFalse("Progress panel visible",
                table.pnlSearchProgress.isVisible());
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
        assertEquals("Wrong number of columns", COL_COUNT + 1,
                ft.getCellCount(1));
        HasWidgets widgets = (HasWidgets) ft.getWidget(1, COL_COUNT);
        Iterator<Widget> widgetIt = widgets.iterator();
        CustomButton btn = (CustomButton) widgetIt.next();
        assertFalse("Too many widgets", widgetIt.hasNext());
        fireClickEvent(btn);
        assertEquals("Wrong ID passed to handler", Long.valueOf(0),
                h.getElementID());
    }

    /**
     * Helper method for adding some single element handlers to the table.
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
        assertEquals("Wrong number of columns", COL_COUNT + 1,
                ft.getCellCount(1));
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            HasWidgets widgets = (HasWidgets) ft.getWidget(i + 1, COL_COUNT);
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
            return Long.valueOf(row);
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
}
