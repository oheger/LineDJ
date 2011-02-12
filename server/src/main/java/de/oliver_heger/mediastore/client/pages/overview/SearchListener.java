package de.oliver_heger.mediastore.client.pages.overview;

import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;

/**
 * <p>
 * Definition of an interface for search listeners at an {@link OverviewTable}.
 * </p>
 * <p>
 * A listener of this type can be set at an {@link OverviewTable} component. It
 * is notified whenever the user presses the search button.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SearchListener
{
    /**
     * Notifies this listener that a new search request has to be handled.
     *
     * @param source the table which triggers this search request
     * @param params the parameters for the search
     */
    void searchRequest(OverviewTable source, MediaSearchParameters params);
}
