package de.oliver_heger.mediastore.client.pages.overview;

import java.util.List;

import com.google.gwt.view.client.HasData;

/**
 * <p>
 * Definition of an interface for objects which can consume results of a media
 * search.
 * </p>
 * <p>
 * This interface is used in communication between the callback object for a
 * parameter search and the data provider for the cell widget on the overview
 * page. Whenever a chunk with data from the server is received, it is passed to
 * an implementation object through the main method defined here. An
 * implementation can decide how to process the newly arrived data.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects processed by this consumer
 */
public interface SearchResultConsumer<T>
{
    /**
     * Notifies this object that new search results have been retrieved from the
     * server. A concrete implementation will store the data and update the
     * widget. The parameter object can be used to find out whether the results
     * belong to the current search operation (in case the user starts multiple
     * search operations).
     *
     * @param results a list with the results retrieved from the server
     * @param widget the cell widget to be filled
     * @param param the current parameter object
     */
    void searchResultsReceived(List<T> results, HasData<T> widget, Object param);
}
