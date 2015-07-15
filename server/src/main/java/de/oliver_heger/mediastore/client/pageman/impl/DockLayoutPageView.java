package de.oliver_heger.mediastore.client.pageman.impl;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pageman.PageView;

/**
 * <p>
 * An implementation of the {@link PageView} interface that is based on a
 * {@code DockLayoutPanel}.
 * </p>
 * <p>
 * This class is initialized with a {@code DockLayoutPanel} which defines the
 * global layout of the application. It implements the {@link #showPage(Widget)}
 * method by adding the specified page widget to the central area of this layout
 * panel.
 * </p>
 * <p>
 * In addition, widgets for the other areas of the {@code DockLayoutPanel} can
 * be specified, too. When switching to another main page, these widgets will
 * remain constant. Using this mechanism, a simple template system can be
 * defined: Sections which do not change during the life time of the application
 * are added as so-called <em>template parts</em> to this object at application
 * startup. They are not affected by a change of the main page.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class DockLayoutPageView implements PageView
{
    /** The main layout panel. */
    private final DockLayoutPanel layoutPanel;

    /** A list with the templates. */
    private final List<TemplateData> templates;

    /**
     * Creates a new instance of {@code DockLayoutPageView} and initializes it
     * with the {@code DockLayoutPanel} used as underlying layout panel.
     *
     * @param pnl the {@code DockLayoutPanel} associated with this object (must
     *        not be <b>null</b>)
     * @throws NullPointerException if the passed in panel is <b>null</b>
     */
    public DockLayoutPageView(DockLayoutPanel pnl)
    {
        if (pnl == null)
        {
            throw new NullPointerException("DockLayoutPanel must not be null!");
        }

        layoutPanel = pnl;
        templates = new ArrayList<TemplateData>(LayoutPart.values().length);
    }

    /**
     * Returns the underlying {@code DockLayoutPanel}.
     *
     * @return the {@code DockLayoutPanel} used by this object
     */
    public DockLayoutPanel getLayoutPanel()
    {
        return layoutPanel;
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
     * Displays the page represented by the given widget. This implementation
     * puts the widget into the central area of the underlying
     * {@code DockLayoutPanel}. The other areas of the layout panel are
     * initialized with the template parts specified for this object.
     *
     * @param widget the widget to become the new main page
     */
    @Override
    public void showPage(Widget widget)
    {
        getLayoutPanel().clear();
        for (TemplateData td : templates)
        {
            td.add(getLayoutPanel());
        }
        getLayoutPanel().add(widget);
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
