package de.oliver_heger.mediastore.client.pages.overview;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;

/**
 * <p>
 * A specialized {@link SingleElementHandler} implementation which opens a
 * specific page.
 * </p>
 * <p>
 * This handler implementation can be used when clicking on an icon should
 * navigate to another page, e.g. to the details page of the element selected.
 * The handler directs the current {@link PageManager} to a configurable page
 * passing in the element ID as default parameter.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OpenPageSingleElementHandler implements SingleElementHandler
{
    /** Stores the page manager reference. */
    private final PageManager pageManager;

    /** The page to be opened. */
    private final Pages page;

    /**
     * Creates a new instance of {@code OpenPageSingleElementHandler} and
     * initializes it.
     *
     * @param pm the {@link PageManager}
     * @param pg the page to be opened when the handler is triggered
     */
    public OpenPageSingleElementHandler(PageManager pm, Pages pg)
    {
        pageManager = pm;
        page = pg;
    }

    /**
     * An element was selected. This implementation opens a page and passes the
     * ID of the element in question as default parameter.
     *
     * @param elemID the ID of the element that was clicked
     */
    @Override
    public void handleElement(Object elemID)
    {
        pageManager.createPageSpecification(page).withParameter(elemID).open();
    }
}
