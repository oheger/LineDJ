package de.oliver_heger.mediastore.client.pageman;

import com.google.gwt.user.client.ui.Widget;

/**
 * <p>
 * Definition of an interface for objects that can create the widgets for pages.
 * </p>
 * <p>
 * The page manager framework uses objects implementing this interface for
 * creating widgets representing pages. For this purpose, an arbitrary number of
 * factory objects is registered at the page manager (at the registration a
 * unique page name has to be provided). When a page is to be displayed the
 * manager obtains the factory object registered for the page name. Then it asks
 * the factory for the widget representing the page.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface PageFactory
{
    /**
     * Returns the widget implementing the page managed by this factory. An
     * implementation is free how this widget is constructed. A reference to the
     * {@link PageManager} object is passed to this method. It can be saved by
     * the page if it needs to navigate to other pages.
     *
     * @param pageManager a reference to the {@link PageManager}
     * @return the widget implementing the page managed by this factory
     */
    Widget getPageWidget(PageManager pageManager);
}
