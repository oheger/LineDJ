package de.oliver_heger.mediastore.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.Widget;

/**
 * <p>
 * A class for managing the different pages of this application.
 * </p>
 * <p>
 * This class controls the main content area of the central application layout.
 * It provides methods to place components into this area. From the user's point
 * of view this looks like as if the main page has switched.
 * </p>
 * <p>
 * In addition to the option to install an arbitrary UI component as new main
 * page, there is a number of predefined pages controlled by an enumeration
 * class. By passing in a constant of this enumeration the corresponding page is
 * displayed. It is created on first access and then cached - so only a single
 * instance of this component exists during the life-time of the application.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PageManager
{
    /** The main layout panel. */
    private final DockLayoutPanel mainPanel;

    /** A list with the templates. */
    private final List<TemplateData> templates;

    /** A map with the pages created so far. */
    private final Map<Pages, Widget> pages;

    /**
     * Creates a new instance of {@code PageManager} and initializes it with the
     * specified main panel.
     *
     * @param pnl the main layout panel
     */
    public PageManager(DockLayoutPanel pnl)
    {
        mainPanel = pnl;
        templates = new ArrayList<TemplateData>(LayoutPart.values().length);
        pages = new EnumMap<Pages, Widget>(Pages.class);
    }

    /**
     * Returns the main layout panel.
     *
     * @return the main layout panel
     */
    public DockLayoutPanel getMainPanel()
    {
        return mainPanel;
    }

    /**
     * Initializes a template part of the global layout. It is possible to
     * define widgets for supported layout areas which do not change when the
     * layout switches to another main page.
     *
     * @param part the part of the layout to be defined
     * @param w the widget to add to this part
     * @param size the size of this part
     */
    public void initTemplatePart(LayoutPart part, Widget w, double size)
    {
        templates.add(new TemplateData(part, w, size));
    }

    /**
     * Makes the specified widget to the new main page of the application. It is
     * put into the center of the main layout panel.
     *
     * @param comp the component to become the new main page
     */
    public void showPage(Widget comp)
    {
        getMainPanel().clear();
        for (TemplateData td : templates)
        {
            td.add(getMainPanel());
        }
        getMainPanel().add(comp);
    }

    /**
     * Makes the specified default page to the new main page of the application.
     * This method combines the methods {@link #getPage(Pages)} and
     * {@link #showPage(Widget)}.
     *
     * @param page the standard page to be displayed
     */
    public void showPage(Pages page)
    {
        showPage(getPage(page));
    }

    /**
     * Returns the widget representing the specified predefined page. Using this
     * method one of the standard pages of this application can be obtained. For
     * each constant of the {@link Pages} class always the same component is
     * returned.
     *
     * @param page determines the standard page to be returned
     * @return the component representing the specified page
     */
    public Widget getPage(Pages page)
    {
        Widget w = pages.get(page);

        if (w == null)
        {
            w = page.createPageWidget(this);
            pages.put(page, w);
        }

        return w;
    }

    /**
     * An enumeration class describing the supported areas of the layout
     * supported by this class. Constants of this class can be used by the
     * {@code initTemplatePart()} method in order to specify parts of the total
     * layout which remain constant.
     */
    public static enum LayoutPart
    {
        /** Constant for the northern part. */
        NORTH
        {
            @Override
            public void add(DockLayoutPanel pnl, Widget widget, double size)
            {
                pnl.addNorth(widget, size);
            }
        },

        /** Constant for the western part. */
        WEST
        {
            @Override
            public void add(DockLayoutPanel pnl, Widget widget, double size)
            {
                pnl.addWest(widget, size);
            }
        },

        /** Constant for the southern part. */
        SOUTH
        {
            @Override
            public void add(DockLayoutPanel pnl, Widget widget, double size)
            {
                pnl.addSouth(widget, size);
            }
        },

        /** Constant for the eastern part. */
        EAST
        {
            @Override
            public void add(DockLayoutPanel pnl, Widget widget, double size)
            {
                pnl.addEast(widget, size);
            }
        };

        /**
         * Adds the specified widget to the corresponding layout section of the
         * given dock layout panel.
         *
         * @param pnl the dock layout panel
         * @param widget the widget to be added
         * @param size the size of this section
         */
        public abstract void add(DockLayoutPanel pnl, Widget widget, double size);
    }

    /**
     * A simple data class for storing information about layout templates.
     */
    private static class TemplateData
    {
        /** The layout part. */
        private final LayoutPart part;

        /** The widget. */
        private final Widget widget;

        /** The size. */
        private final double size;

        /**
         * Creates a new instance of {@code TemplateData} and initializes it
         * with the template part to be managed.
         *
         * @param lp the layout part
         * @param w the widget to be added
         * @param sz the size of this area
         */
        public TemplateData(LayoutPart lp, Widget w, double sz)
        {
            part = lp;
            widget = w;
            size = sz;
        }

        /**
         * Adds the template component managed by this object to the specified
         * layout panel.
         *
         * @param pnl the layout panel
         */
        public void add(DockLayoutPanel pnl)
        {
            part.add(pnl, widget, size);
        }
    }
}
