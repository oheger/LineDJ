package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Set;

/**
 * <p>
 * Definition of an interface for objects that can process multiple elements
 * displayed in an {@link OverviewTable} component.
 * </p>
 * <p>
 * An {@code OverviewTable} supports an arbitrary number of objects implementing
 * this interface. Each row of the table contains a checkbox which can be used
 * to select the row. If at least one row is selected, the
 * {@code MultiElementHandler} objects are enabled (i.e. the buttons associated
 * with them). If then a handler is triggered, it is passed the indices of all
 * selected elements and can perform the operation it was designed for. Thus the
 * {@code MultiElementHandler} interface provides a convenient mechanism for
 * extending an overview table with additional functionality.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface MultiElementHandler
{
    /**
     * Notifies this handler that the user clicked the button associated with
     * it. This method is passed the set with the IDs of all selected elements.
     * The set is guaranteed to be non-empty. A concrete implementation can use
     * the IDs to perform specific operations on the selected elements.
     *
     * @param elemIDs the set with the IDs of all elements which are currently
     *        selected
     */
    void handleElements(Set<Object> elemIDs);
}
