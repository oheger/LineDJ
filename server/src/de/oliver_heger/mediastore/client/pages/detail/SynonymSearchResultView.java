package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Map;

import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;

/**
 * <p>
 * Definition of an interface to be implemented by a UI component which can
 * display the results of a search for synonyms.
 * </p>
 * <p>
 * The synonym editor allows searching for entities which might be synonyms of
 * the currently displayed object. Through the methods defined by this interface
 * the query component communicates with the editor.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SynonymSearchResultView
{
    /**
     * Tests whether the search results represented by the given parameters
     * object are accepted. It might the case that the user has started a new
     * search in the mean time. So when results arrive they may be no longer
     * up-to-date. Therefore the query component first calls this method to
     * check whether the results retrieved from the server are part of the
     * current query. If not, they are thrown away.
     *
     * @param params the parameters object of the search for which results have
     *        been received
     * @return a flag whether the results are accepted
     */
    boolean acceptResults(MediaSearchParameters params);

    /**
     * Adds results of a synonym search. This method is called if search results
     * are accepted by the component. The map passed to this method contains the
     * IDs of the detected elements as keys and their names as values.
     *
     * @param synResults a map with the results to be added
     * @param moreResults a flag whether more results may be available; a value
     *        of <b>false</b> means that the search is complete
     */
    void addResults(Map<Object, String> synResults, boolean moreResults);

    /**
     * An error was returned by a server call. A concrete implementation can
     * somehow visualize the error.
     *
     * @param ex the exception returned from the server
     */
    void onFailure(Throwable ex);
}
