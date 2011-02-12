package de.oliver_heger.mediastore.client.pages.detail;

import java.io.Serializable;

/**
 * <p>
 * Definition of an interface for querying for synonyms.
 * </p>
 * <p>
 * This interface is used by the synonym editor component. When the user types
 * some text into the search field a search is initiated. An implementation of
 * this interface is responsible for invoking the correct search method on the
 * service.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SynonymQueryHandler
{
    /**
     * Searches for synonyms. An implementation has to call the server searching
     * for the specified search text. Then it has to ensure that the results of
     * the search are correctly passed to the view.
     *
     * @param view the view for displaying the results
     * @param searchText the text to be searched for
     * @param clientParam the client parameter for the search
     */
    void querySynonyms(SynonymSearchResultView view, String searchText,
            Serializable clientParam);
}
