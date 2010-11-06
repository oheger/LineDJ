package de.oliver_heger.mediastore.shared.search;

/**
 * <p>
 * Definition of an interface that can be used for search queries spanning
 * multiple server invocations.
 * </p>
 * <p>
 * Some search queries can be too expensive to search all existing entities at
 * once. In this case an object implementing this interface can be used to store
 * all information about the current search position. The idea is that the
 * server creates a {@code SearchIterator} object and returns it to the client
 * together with the results retrieved so far. Based on the methods defined in
 * this interface the client can find out whether the search is complete or
 * there are still remaining elements to be searched. In the latter case the
 * client can invoke the search method another time passing in the same iterator
 * object. From the data stored in the iterator internally the server is able to
 * continue the search at the very same position. This communication can happen
 * in a loop until search is complete.
 * </p>
 * <p>
 * The basic motivation is that search results already drop in while the search
 * is still in progress. So the user gets feedback soon, and the application
 * appears to be more responsive.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SearchIterator
{
    /**
     * Returns a flag if more data is available on the server. A return value of
     * <b>true</b> indicates that the client can call the search method again on
     * the server, potentially retrieving more results. A value of <b>false</b>
     * means that the search is complete.
     *
     * @return a flag if more data is available
     */
    boolean hasNext();

    /**
     * Returns the current search position. Together with the total number of
     * records available this information can be used to calculate the progress
     * of the search. Note that the position returned by this method is not
     * necessarily exact. Rather, it is a rough estimation where in the current
     * search set the cursor stands. This should be sufficient for giving
     * feedback about the progress to the user.
     *
     * @return the current position in the search result set
     */
    long getCurrentPosition();

    /**
     * Returns the total number of records that have to be searched. Together
     * with the current position the progress of the search can be determined.
     * The number of records is determined when search starts. Updates while
     * search is going on are not reflected.
     *
     * @return the total number of records to search
     */
    long getRecordCount();

    /**
     * Returns the index of the current page. If a result set is pretty large,
     * it has to be divided into multiple pages. A view on the client displays a
     * single page and allows the user to navigate in the pages available.
     * However, depending on the current search information about pages may not
     * be available, e.g. if the search is not complete yet and therefore the
     * total number of results is unknown. In this case this method can return
     * <b>null</b>. The view on the client can then only know whether there is a
     * next and a previous page. This method is typically used together with
     * {@link #getPageCount()} to implement paging functionality. An
     * implementation of this interface must either return non-<b>null</b>
     * values from both methods or <b>null</b> - so paging is either supported
     * fully or not at all.
     *
     * @return the index of the current page in the result set or <b>null</b> if
     *         this information is not available
     */
    Integer getCurrentPage();

    /**
     * Returns the total number of pages available (provided this information is
     * known).
     *
     * @return the total number of pages in the result set or <b>null</b>
     * @see #getCurrentPage()
     */
    Integer getPageCount();
}
