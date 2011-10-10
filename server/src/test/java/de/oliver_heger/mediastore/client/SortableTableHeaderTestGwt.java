package de.oliver_heger.mediastore.client;

import com.google.gwt.junit.client.GWTTestCase;

import de.oliver_heger.mediastore.client.SortableTableHeader.SortDirection;

/**
 * Test class for {@code SortableTableHeader}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SortableTableHeaderTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests a newly created instance.
     */
    public void testInit()
    {
        SortableTableHeader header = new SortableTableHeader();
        assertNotNull("Got a text", header.getText());
        assertNotNull("No up image", header.imgSortUp);
        assertFalse("Up image visible", header.imgSortUp.isVisible());
        assertNotNull("No down image", header.imgSortDown);
        assertFalse("Down image visible", header.imgSortDown.isVisible());
        assertNotNull("No link", header.lnkHeader);
        assertEquals("Wrong sort direction",
                SortableTableHeader.SortDirection.SORT_NONE,
                header.getSortDirection());
        assertNull("Got a property", header.getPropertyName());
        assertFalse("Wrong default descending", header.isDefaultDescending());
        assertFalse("Wrong initially sorted", header.isInitiallySorted());
        assertNull("Got a listener", header.getTableHeaderListener());
    }

    /**
     * Tests whether the text of the header can be changed.
     */
    public void testSetText()
    {
        SortableTableHeader header = new SortableTableHeader();
        final String txt = "Test Header Title";
        header.setText(txt);
        assertEquals("Link text not updated", txt, header.lnkHeader.getText());
        header.lnkHeader.setText(txt + txt);
        assertEquals("Wrong text", txt + txt, header.getText());
    }

    /**
     * Helper method for checking whether the state of the component is updated
     * correctly if a sort direction is set. This method checks whether the
     * images indicating the sort direction are updated correspondingly.
     *
     * @param dir the sort direction
     * @param upVisible flag whether the up image should be visible
     * @param downVisible flag whether the down image should be visible
     */
    private void checkSetSortDirectionUpdateState(
            SortableTableHeader.SortDirection dir, boolean upVisible,
            boolean downVisible)
    {
        SortableTableHeader header = new SortableTableHeader();
        header.setSortDirection(dir);
        assertEquals("Wrong visible state for up image", upVisible,
                header.imgSortUp.isVisible());
        assertEquals("Wrong visible state for down image", downVisible,
                header.imgSortDown.isVisible());
    }

    /**
     * Tests whether the sort direction can be set to ascending.
     */
    public void testSetSortDirectionAscending()
    {
        checkSetSortDirectionUpdateState(
                SortableTableHeader.SortDirection.SORT_ASCENDING, true, false);
    }

    /**
     * Tests whether the sort direction can be set to descending.
     */
    public void testSetSortDirectionDescending()
    {
        checkSetSortDirectionUpdateState(
                SortableTableHeader.SortDirection.SORT_DESCENDING, false, true);
    }

    /**
     * Tests whether the sort direction can be set to none.
     */
    public void testSetSortDirectionNone()
    {
        checkSetSortDirectionUpdateState(
                SortableTableHeader.SortDirection.SORT_NONE, false, false);
    }

    /**
     * Helper method for testing the reaction on a click on the link.
     *
     * @param count the number of clicks
     * @param expDirection the expected sort direction
     */
    private void checkOnLinkClick(int count,
            SortableTableHeader.SortDirection expDirection)
    {
        SortableTableHeader header = new SortableTableHeader();
        for (int i = 0; i < count - 1; i++)
        {
            header.onLinkClick(null);
        }
        TableHeaderListenerTestImpl l =
                new TableHeaderListenerTestImpl(header, expDirection);
        header.setTableHeaderListener(l);
        header.onLinkClick(null);
        l.verify();
    }

    /**
     * Tests whether the header's state is updated correctly if the link is
     * clicked once.
     */
    public void testOnLinkClickOnce()
    {
        checkOnLinkClick(1, SortDirection.SORT_ASCENDING);
    }

    /**
     * Tests whether the header's state is updated correctly if the link is
     * clicked a 2nd time.
     */
    public void testOnLinkClickTwice()
    {
        checkOnLinkClick(2, SortDirection.SORT_DESCENDING);
    }

    /**
     * Tests whether the header's state is updated correctly if the link is
     * clicked 3 times.
     */
    public void testOnLinkClick3Times()
    {
        checkOnLinkClick(3, SortDirection.SORT_ASCENDING);
    }

    /**
     * Tests whether the default descending flag is taken into account when
     * clicking the link.
     */
    public void testOnLinkClickDefaultDescending()
    {
        SortableTableHeader header = new SortableTableHeader();
        header.setDefaultDescending(true);
        header.onLinkClick(null);
        assertEquals("Wrong direction 1",
                SortableTableHeader.SortDirection.SORT_DESCENDING,
                header.getSortDirection());
        header.onLinkClick(null);
        assertEquals("Wrong direction 2",
                SortableTableHeader.SortDirection.SORT_ASCENDING,
                header.getSortDirection());
    }

    /**
     * Tests whether an initial order can be specified.
     */
    public void testApplyInitialOrder()
    {
        SortableTableHeader header = new SortableTableHeader();
        header.setDefaultDescending(true);
        header.setInitiallySorted(true);
        assertTrue("Wrong result", header.applyInitialOrder());
        assertEquals("Wrong sort order",
                SortableTableHeader.SortDirection.SORT_DESCENDING,
                header.getSortDirection());
    }

    /**
     * Tests applyInitialOrder() if the header is not sorted initially.
     */
    public void testApplyInitialOrderNoInitialSort()
    {
        SortableTableHeader header = new SortableTableHeader();
        assertFalse("Applied default order", header.applyInitialOrder());
        assertEquals("Wrong sort order",
                SortableTableHeader.SortDirection.SORT_NONE,
                header.getSortDirection());
    }

    /**
     * Tests applyInitialOrder() if a sort direction has already been set.
     */
    public void testApplyInitialOrderSortDirection()
    {
        SortableTableHeader header = new SortableTableHeader();
        header.setInitiallySorted(true);
        header.setSortDirection(SortDirection.SORT_ASCENDING);
        assertFalse("Applied default order", header.applyInitialOrder());
        assertEquals("Wrong sort order",
                SortableTableHeader.SortDirection.SORT_ASCENDING,
                header.getSortDirection());
    }

    /**
     * A specialized listener implementation for testing whether listeners are
     * invoked correctly.
     */
    private static class TableHeaderListenerTestImpl implements
            SortableTableHeader.TableHeaderListener
    {
        /** The expected header component. */
        private final SortableTableHeader expHeader;

        /** The expected sort direction. */
        private final SortableTableHeader.SortDirection expDirection;

        /** A flag whether the listener was called. */
        private boolean called;

        /**
         * Creates a new instance of {@code TableHeaderListenerTestImpl} and
         * sets the expected header and sort direction.
         *
         * @param header the header
         * @param direction the sort direction
         */
        public TableHeaderListenerTestImpl(SortableTableHeader header,
                SortableTableHeader.SortDirection direction)
        {
            expHeader = header;
            expDirection = direction;
        }

        /**
         * Verifies that the listener was called.
         */
        public void verify()
        {
            assertTrue("Listener not called", called);
        }

        /**
         * Checks the parameters and records this invocation.
         */
        @Override
        public void onSortableTableHeaderClick(SortableTableHeader header)
        {
            assertSame("Wrong header", expHeader, header);
            assertEquals("Wrong direction", expDirection,
                    header.getSortDirection());
            called = true;
        }
    }
}
