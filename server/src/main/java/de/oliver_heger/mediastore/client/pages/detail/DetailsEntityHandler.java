package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaService;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;

/**
 * <p>
 * Definition of an interface for objects that are responsible for the
 * interaction between a details page and the {@link BasicMediaService}.
 * </p>
 * <p>
 * This interface is used by implementations of detail pages in order to
 * retrieve detail data from the server and to perform certain manipulations on
 * the current entity. The abstract base class of these pages provides a
 * framework which ensures that the service is called at the correct points of
 * times and that the page is populated with the details data. A concrete
 * implementation of this interface has to be provided by a subclass for a
 * concrete entity type which calls the correct service methods.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects used by this handler
 */
public interface DetailsEntityHandler<T>
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

    /**
     * Calls the {@link BasicMediaService} in order to update the synonyms of
     * the current element. This method is called when the user has invoked the
     * synonym editor. A concrete implementation has to call the appropriate
     * service method.
     *
     * @param mediaService the service to be called
     * @param elemID the ID of the current element
     * @param upData the object with information about the manipulated synonyms
     * @param callback the callback for the service call
     */
    void updateSynonyms(BasicMediaServiceAsync mediaService, String elemID,
            SynonymUpdateData upData, AsyncCallback<Void> callback);
}
