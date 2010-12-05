package de.oliver_heger.mediastore.client.pages;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pages.overview.OverviewPage;

/**
 * Test class for {@code RMSPageManager}. The enumeration constants for the
 * standard pages are tested, too.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestRMSPageManager extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether the main panel is managed correctly.
     */
    public void testGetMainPanel()
    {
        DockLayoutPanel pnl = new DockLayoutPanelTestImpl();
        RMSPageManager pm = new RMSPageManager(pnl);
        assertSame("Wrong panel", pnl, pm.getMainPanel());
    }

    /**
     * Tests whether a widget can be directly displayed.
     */
    public void testShowPage()
    {
        DockLayoutPanelTestImpl pnl = new DockLayoutPanelTestImpl();
        RMSPageManager pm = new RMSPageManager(pnl);
        Label labNorth = new Label("North");
        Label labWest = new Label("West");
        Label labSouth = new Label("South");
        Label labEast = new Label("LabEast");
        Label labCenter = new Label("Center");
        pm.initTemplatePart(RMSPageManager.LayoutPart.NORTH, labNorth, 1);
        pm.initTemplatePart(RMSPageManager.LayoutPart.WEST, labWest, 2);
        pm.initTemplatePart(RMSPageManager.LayoutPart.SOUTH, labSouth, 3);
        pm.initTemplatePart(RMSPageManager.LayoutPart.EAST, labEast, 4);
        pm.showPage(labCenter);
        pnl.checkClear();
        pnl.checkNorth(labNorth, 1);
        pnl.checkWest(labWest, 2);
        pnl.checkSouth(labSouth, 3);
        pnl.checkEast(labEast, 4);
        pnl.checkCenter(labCenter);
    }

    /**
     * Tests whether the overview page can be obtained.
     */
    public void testGetPageOverview()
    {
        RMSPageManager pm = new RMSPageManager(new DockLayoutPanelTestImpl());
        Widget w = pm.getPage(Pages.OVERVIEW);
        OverviewPage page = (OverviewPage) w;
        assertSame("Page manager not set", pm, page.getPageManager());
    }

    /**
     * Tests whether always the same page instance is returned.
     */
    public void testGetPageSingleton()
    {
        RMSPageManager pm = new RMSPageManager(new DockLayoutPanelTestImpl());
        Widget w = pm.getPage(Pages.OVERVIEW);
        assertSame("Multiple instances", w, pm.getPage(Pages.OVERVIEW));
    }

    /**
     * Tests whether one of the standard pages can be shown.
     */
    public void testShowPageStandard()
    {
        DockLayoutPanelTestImpl pnl = new DockLayoutPanelTestImpl();
        RMSPageManager pm = new RMSPageManager(pnl);
        pm.showPage(Pages.OVERVIEW);
        pnl.checkCenter(pm.getPage(Pages.OVERVIEW));
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
