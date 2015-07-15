package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.TabLayoutPanel;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pageman.PageManager;

/**
 * <p>
 * A specialized component representing the main overview page.
 * </p>
 * <p>
 * This page mainly consists of a tab panel. The different tabs contain overview
 * tables for the different media types.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OverviewPage extends Composite
{
    /** The binder used for building this component. */
    private static MyUiBinder binder = GWT.create(MyUiBinder.class);

    /** The tab panel. */
    @UiField
    TabLayoutPanel tabPanel;

    /** Holds a reference to the page manager. */
    private PageManager pageManager;

    /**
     * Creates a new instance of {@code OverviewPage}.
     */
    public OverviewPage()
    {
        initWidget(binder.createAndBindUi(this));
    }

    /**
     * Returns a reference to the {@link RMSPageManager}. Using this component
     * it is possible to navigate to other pages.
     *
     * @return the page manager
     */
    public PageManager getPageManager()
    {
        return pageManager;
    }

    /**
     * Initializes this component.
     *
     * @param pm a reference to the page manager
     */
    public void initialize(PageManager pm)
    {
        pageManager = pm;
        initializeOverviewTables(pm);
    }

    /**
     * Initializes the single overview tables displayed as tabs of this page.
     *
     * @param pm the current {@code PageManager}
     */
    private void initializeOverviewTables(PageManager pm)
    {
        for (int i = 0; i < tabPanel.getWidgetCount(); i++)
        {
            ((AbstractOverviewTable<?>) getTableWidget(i)).initialize(pm);
        }
    }

    /**
     * Returns the widget of the tab pane with the specified index.
     *
     * @param index the index in the tab pane
     * @return the widget at this index
     */
    Widget getTableWidget(int index)
    {
        return tabPanel.getWidget(index);
    }

    /**
     * The specific UI binder interface for this page component.
     */
    interface MyUiBinder extends UiBinder<Widget, OverviewPage>
    {
    }
}
