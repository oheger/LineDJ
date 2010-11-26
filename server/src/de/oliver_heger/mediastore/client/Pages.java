package de.oliver_heger.mediastore.client;

import com.google.gwt.user.client.ui.Widget;

/**
 * <p>
 * An enumeration class with the predefined pages supported by this application.
 * </p>
 * <p>
 * This enumeration class is used together with {@link PageManager}. By passing
 * a constant of this class to the manager the single instance of the
 * corresponding page can be obtained and selected.
 * </p>
 * <p>
 * When a page is shown for the first time it is created. For this purpose this
 * class defines a generic method for the creation of the page component.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public enum Pages
{
    OVERVIEW
    {
        @Override
        public Widget createPageWidget(PageManager pm)
        {
            OverviewPage page = new OverviewPage();
            return page;
        }
    };

    /**
     * Creates the widget representing this page. This method is called by the
     * page manager when a page is to be displayed for the first time. The page
     * manager instance is passed, so that it can be stored by the page widget;
     * this may be needed if this page has to navigate to other pages.
     *
     * @param pm the page manager
     * @return the widget implementing this page
     */
    public abstract Widget createPageWidget(PageManager pm);
}
