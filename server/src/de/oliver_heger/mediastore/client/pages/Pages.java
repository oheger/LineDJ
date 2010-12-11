package de.oliver_heger.mediastore.client.pages;

import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pageman.PageFactory;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.overview.OverviewPage;

/**
 * <p>
 * An enumeration class with the predefined pages supported by this application.
 * </p>
 * <p>
 * This enumeration class implements the {@link PageFactory} interface. Therefore
 * it can be used together with {@link PageManager} to navigate to different
 * pages of this application.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public enum Pages implements PageFactory
{
    OVERVIEW
    {
        @Override
        public Widget getPageWidget(PageManager pageManager)
        {
            OverviewPage page = new OverviewPage();
            page.initialize(pageManager);
            return page;
        }
    };
}
