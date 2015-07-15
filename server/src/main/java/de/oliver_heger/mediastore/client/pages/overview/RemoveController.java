package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Collection;

/**
 * <p>
 * Definition of an interface for a controller which handles a remove operation.
 * </p>
 * <p>
 * Using this interface it is possible to remove multiple elements in a single
 * operation. The controller is responsible for the whole operation including
 * user feedback about the progress. After the operation has been performed the
 * affected control is refreshed.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface RemoveController
{
    /**
     * Executes a remove operation. All the elements specified in the given set
     * are removed. After that the specified control is refreshed.
     *
     * @param handler the handler for removing elements (must not be
     *        <b>null</b>)
     * @param ctrl the control affected by the remove operation (must not be
     *        <b>null</b>)
     * @param elemIDs a collection with the IDs of the elements to be removed
     *        (must not be <b>null</b> or empty)
     * @throws NullPointerException if a required parameter is missing
     * @throws IllegalArgumentException if no IDs to be removed are provided
     */
    void performRemove(RemoveServiceHandler handler, Refreshable ctrl,
            Collection<Object> elemIDs);

}
