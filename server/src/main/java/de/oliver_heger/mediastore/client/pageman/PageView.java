package de.oliver_heger.mediastore.client.pageman;

import com.google.gwt.user.client.ui.Widget;

/**
 * <p>
 * Definition of an interface for displaying different pages of an application.
 * </p>
 * <p>
 * This interface allows setting an arbitrary widget as the new main page of an
 * application. It is used by the {@code PageManager} implementation to switch
 * to another page.
 * </p>
 * <p>
 * Concrete implementations will probably define a kind of templating mechanism:
 * Parts of the page will remain stable (e.g. the header and the footer) while
 * the main content area is replaced by the widget representing the new page.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface PageView
{
    /**
     * Displays the specified main page. This method is called when the main
     * content area of an application is to be changed. A concrete
     * implementation should ensure that the specified widget becomes visible at
     * a prominent area of the main application window. The current main page
     * should be replaced by the new one.
     *
     * @param widget the widget representing the new main page
     */
    void showPage(Widget widget);
}
