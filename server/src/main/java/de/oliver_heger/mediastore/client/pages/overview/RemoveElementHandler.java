package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Collections;
import java.util.Set;

/**
 * <p>
 * A specialized element handler implementation for removing elements.
 * </p>
 * <p>
 * This implementation delegates to a {@link RemoveController} for performing
 * the remove operation. Because the remove controller generally operates on a
 * collection of elements this class can implement both the
 * {@code SingleElementHandler} and {@code MultiElementHandler} interfaces -
 * both cases have to be treated in the same way.
 * </p>
 * <p>
 * Implementation note: This class is used internally only, therefore no
 * sophisticated parameter checking is done.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class RemoveElementHandler implements SingleElementHandler, MultiElementHandler
{
    /** The service handler. */
    private final RemoveServiceHandler serviceHandler;

    /** The associated overview table. */
    private final OverviewTable overviewTable;

    /**
     * Creates a new instance of {@code RemoveElementHandler} and initializes it
     * with the service handler and the associated overview table.
     *
     * @param svcHandler the service handler
     * @param tab the overview table
     */
    public RemoveElementHandler(RemoveServiceHandler svcHandler,
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
        doHandleElements(Collections.singleton(elemID));
    }

    /**
     * Handles the specified set of element IDs. This implementation creates a
     * new remove controller and passes it the set of element IDs to be handled.
     *
     * @param elemIDs the set of element IDs to be handled
     */
    @Override
    public void handleElements(Set<Object> elemIDs)
    {
        doHandleElements(elemIDs);
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

    /**
     * Actually processes the element IDs. This method creates and invokes the
     * remove controller.
     *
     * @param elemIDs the set with element IDs to be handled
     */
    private void doHandleElements(Set<Object> elemIDs)
    {
        RemoveController controller = createRemoveController();
        controller.performRemove(getServiceHandler(), getOverviewTable(),
                elemIDs);
    }
}
