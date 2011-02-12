package de.oliver_heger.mediastore.client.pageman.impl;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

/**
 * Test class for {@code DockLayoutPageView}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestDockLayoutPageView extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tries to create an instance without a panel.
     */
    public void testInitNull()
    {
        try
        {
            new DockLayoutPageView(null);
            fail("Null layout panel not detected!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tests whether the layout panel is managed correctly.
     */
    public void testGetLayoutPanel()
    {
        DockLayoutPanel pnl = new DockLayoutPanelTestImpl();
        DockLayoutPageView view = new DockLayoutPageView(pnl);
        assertSame("Wrong panel", pnl, view.getLayoutPanel());
    }

    /**
     * Tests whether a widget can be made to the new main page.
     */
    public void testShowPage()
    {
        DockLayoutPanelTestImpl pnl = new DockLayoutPanelTestImpl();
        DockLayoutPageView view = new DockLayoutPageView(pnl);
        Label labNorth = new Label("North");
        Label labWest = new Label("West");
        Label labSouth = new Label("South");
        Label labEast = new Label("LabEast");
        Label labCenter = new Label("Center");
        view.initTemplatePart(DockLayoutPageView.LayoutPart.NORTH, labNorth, 1);
        view.initTemplatePart(DockLayoutPageView.LayoutPart.WEST, labWest, 2);
        view.initTemplatePart(DockLayoutPageView.LayoutPart.SOUTH, labSouth, 3);
        view.initTemplatePart(DockLayoutPageView.LayoutPart.EAST, labEast, 4);
        view.showPage(labCenter);
        pnl.checkClear();
        pnl.checkNorth(labNorth, 1);
        pnl.checkWest(labWest, 2);
        pnl.checkSouth(labSouth, 3);
        pnl.checkEast(labEast, 4);
        pnl.checkCenter(labCenter);
    }

    /**
     * A test dock layout panel implementation which keeps track on the
     * components added to the panel.
     */
    private static class DockLayoutPanelTestImpl extends DockLayoutPanel
    {
        /** The north widget. */
        private Widget north;

        /** The west widget. */
        private Widget west;

        /** The south widget. */
        private Widget south;

        /** The east widget. */
        private Widget east;

        /** The center widget. */
        private Widget center;

        /** The size of the north widget. */
        private double northSize;

        /** The size of the west widget. */
        private double westSize;

        /** The size of the south widget. */
        private double southSize;

        /** The size of the east widget. */
        private double eastSize;

        /** A counter for the invocations of clear(). */
        private int clearCount;

        public DockLayoutPanelTestImpl()
        {
            super(Unit.CM);
        }

        /**
         * Checks the north widget.
         *
         * @param exp the expected widget
         * @param expSize the expected size
         */
        public void checkNorth(Widget exp, double expSize)
        {
            assertEquals("Wrong north widget", exp, north);
            checkSize(expSize, northSize);
        }

        /**
         * Checks the west widget.
         *
         * @param exp the expected widget
         * @param expSize the expected size
         */
        public void checkWest(Widget exp, double expSize)
        {
            assertEquals("Wrong west widget", exp, west);
            checkSize(expSize, westSize);
        }

        /**
         * Checks the south widget.
         *
         * @param exp the expected widget
         * @param expSize the expected size
         */
        public void checkSouth(Widget exp, double expSize)
        {
            assertEquals("Wrong south widget", exp, south);
            checkSize(expSize, southSize);
        }

        /**
         * Checks the east widget.
         *
         * @param exp the expected widget
         * @param expSize the expected size
         */
        public void checkEast(Widget exp, double expSize)
        {
            assertEquals("Wrong east widget", exp, east);
            checkSize(expSize, eastSize);
        }

        /**
         * Checks the center component.
         *
         * @param exp the expected widget
         */
        public void checkCenter(Widget exp)
        {
            assertEquals("Wrong center", exp, center);
        }

        /**
         * Checks whether clear() was called exactly once.
         */
        public void checkClear()
        {
            assertEquals("Wrong number of clear() calls", 1, clearCount);
        }

        @Override
        public void add(Widget widget)
        {
            center = widget;
        }

        @Override
        public void addEast(Widget widget, double size)
        {
            east = widget;
            eastSize = size;
        }

        @Override
        public void addNorth(Widget widget, double size)
        {
            north = widget;
            northSize = size;
        }

        @Override
        public void addSouth(Widget widget, double size)
        {
            south = widget;
            southSize = size;
        }

        @Override
        public void addWest(Widget widget, double size)
        {
            west = widget;
            westSize = size;
        }

        @Override
        public void clear()
        {
            clearCount++;
        }

        /**
         * Helper method for checking the size of a widget.
         *
         * @param exp the expected size
         * @param act the actual size
         */
        private void checkSize(double exp, double act)
        {
            assertEquals("Wrong widget size", exp, act, .005);
        }
    }
}
