package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Collections;

/**
 * <p>
 * A specialized {@code SingleElementHandler} implementation for removing a
 * single element.
 * </p>
 * <p>
 * This implementation delegates to a {@link RemoveController} for performing
 * the remove operation.
 * </p>
 * <p>
 * Implementation note: This class is used internally only, therefore no
 * sophisticated parameter checking is done.
 * </p>
 * 
 * @author Oliver Heger
 * @version $Id: $
 */
class RemoveSingleElementHandler implements SingleElementHandler
{
    /** The service handler. */
    private final RemoveServiceHandler serviceHandler;

    /** The associated overview table. */
    private final OverviewTable overviewTable;

    /**
     * Creates a new instance of {@code RemoveSingleElementHandler} and
     * initializes it with the service handler and the associated overview
     * table.
     * 
     * @param svcHandler the service handler
     * @param tab the overview table
     */
    public RemoveSingleElementHandler(RemoveServiceHandler svcHandler,
            OverviewTable tab)
    {
        serviceHandler = svcHandler;
        overviewTable = tab;
    }

    /**
     * Returns the {@code RemoveServiceHandler} used by this handler.
     * 
     * @return the service handler
     */
    public RemoveServiceHandler getServiceHandler()
    {
        return serviceHandler;
    }

    /**
     * Returns the {@code OverviewTable} this handler operates on.
     * 
     * @return the overview table
     */
    public OverviewTable getOverviewTable()
    {
        return overviewTable;
    }

    /**
     * Handles the element with the specified ID. This implementation creates a
     * new remove controller and uses it to initiate the remove operation.
     * 
     * @param elemID the ID of the affected element
     */
    @Override
    public void handleElement(Object elemID)
    {
        RemoveController controller = createRemoveController();
        controller.performRemove(getServiceHandler(), getOverviewTable(),
                Collections.singleton(elemID));
    }

    /**
     * Creates a new {@code RemoveController} to be used for a remove operation.
     * This method is called each time this handler is invoked.
     * 
     * @return the new remove controller
     */
    RemoveController createRemoveController()
    {
        return new RemoveController();
    }
}
