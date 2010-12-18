package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaService;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;

/**
 * <p>
 * Definition of an interface for objects that can query the
 * {@link BasicMediaService} for details of a specific entity type.
 * </p>
 * <p>
 * This interface is used by implementations of detail pages. The abstract base
 * class for these pages provides a framework which ensures that the service is
 * called and that the page is populated with the result data. A concrete
 * implementation of this interface has to be provided by a concrete subclass
 * which calls the correct service method.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects used by this handler
 */
public interface DetailsQueryHandler<T>
{
    /**
     * Queries the {@link BasicMediaService} for a data object with the details
     * of the current element. This method is called when a details page is
     * opened. A concrete implementation has to invoke the correct method of the
     * service in order to retrieve the required data.
     *
     * @param mediaService the service to be called
     * @param elemID the ID of the current element
     * @param callback the callback for the service call
     */
    void fetchDetails(BasicMediaServiceAsync mediaService, String elemID,
            AsyncCallback<T> callback);
}
