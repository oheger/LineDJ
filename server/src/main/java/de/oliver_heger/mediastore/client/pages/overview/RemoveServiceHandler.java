package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;

/**
 * <p>
 * Definition of an interface that handles the removal of an element from an
 * overview page.
 * </p>
 * <p>
 * Removing of elements can be done in a generic way. A generic component is
 * initialized with the IDs of the elements to be removed. It then passes the
 * IDs one by one to a concrete implementation of this interface. The interface
 * has to call the correct method of the {@code BasicMediaService}.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface RemoveServiceHandler
{
    /**
     * Invokes the correct removal method of the media service to remove the
     * element with the specified ID.
     *
     * @param service the service to be called
     * @param elemID the ID of the element to be removed
     * @param callback the callback to be passed to the service
     */
    void removeElement(BasicMediaServiceAsync service, Object elemID,
            AsyncCallback<Boolean> callback);
}
