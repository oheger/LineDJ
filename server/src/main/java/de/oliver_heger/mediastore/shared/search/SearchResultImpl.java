package de.oliver_heger.mediastore.shared.search;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A default implementation of the {@link SearchResult} interface.
 * </p>
 * <p>
 * Objects of this class are returned by search methods of the default
 * {@link MediaSearchService} implementation. This is a straightforward
 * implementation which just stores the required properties in internal member
 * fields. No attempt has been made to make this class robust or thread-safe
 * because this might conflict with the serialization used by GWT.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects stored in this result
 */
public class SearchResultImpl<T> implements SearchResult<T>, Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101016L;

    /** Constant for the maximum result length. */
    private static final int MAX_RESULT_LENGTH = 16;

    /** Stores the list with the results. */
    private List<T> results;

    /** The search iterator. */
    private SearchIterator searchIterator;

    /** The search parameters. */
    private MediaSearchParameters searchParameters;

    /**
     * Default constructor needed for serialization.
     */
    private SearchResultImpl()
    {
    }

    /**
     * Creates a new instance of {@code SearchResultImpl} and initializes it.
     *
     * @param resultList the list with the actual result objects
     * @param searchIt the current search iterator
     * @param params the parameters for the current search
     */
    public SearchResultImpl(Collection<? extends T> resultList,
            SearchIterator searchIt, MediaSearchParameters params)
    {
        this();
        if (resultList == null)
        {
            results = Collections.emptyList();
        }
        else
        {
            results = new ArrayList<T>(resultList);
        }
        searchIterator = searchIt;
        searchParameters = params;
    }

    /**
     * Returns the list with the search results.
     *
     * @return the list with the search results
     */
    @Override
    public List<T> getResults()
    {
        return results;
    }

    /**
     * Returns the search iterator.
     *
     * @return the search iterator
     */
    @Override
    public SearchIterator getSearchIterator()
    {
        return searchIterator;
    }

    /**
     * Returns the search parameters.
     *
     * @return the search parameters
     */
    @Override
    public MediaSearchParameters getSearchParameters()
    {
        return searchParameters;
    }

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code
     */
    @Override
    public int hashCode()
    {
        int result = ObjectUtils.HASH_SEED;
        result = ObjectUtils.hash(results, result);
        result = ObjectUtils.hash(searchIterator, result);
        result = ObjectUtils.hash(searchParameters, result);
        return result;
    }

    /**
     * Compares this object with another one. Two instances of this class are
     * considered equal if all of their properties are equal.
     *
     * @param obj the object to compare
     * @return a flag whether these objects are equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof SearchResultImpl))
        {
            return false;
        }

        SearchResultImpl<?> c = (SearchResultImpl<?>) obj;
        return ObjectUtils.equals(results, c.results)
                && ObjectUtils.equals(searchIterator, c.searchIterator)
                && ObjectUtils.equals(searchParameters, c.searchParameters);
    }

    /**
     * Returns a string representation for this object. This string contains the
     * values of all properties. If the result list is too long (more than 16
     * elements), it is stripped.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        if (!results.isEmpty())
        {
            String resultsStr;
            if (results.size() <= MAX_RESULT_LENGTH)
            {
                resultsStr = results.toString();
            }
            else
            {
                StringBuilder buf2 = new StringBuilder();
                buf2.append(results.size());
                buf2.append(" elements starting with ");
                buf2.append(results.subList(0, MAX_RESULT_LENGTH).toString());
                resultsStr = buf2.toString();
            }
            ObjectUtils.appendToStringField(buf, "results", resultsStr, false);
        }
        ObjectUtils.appendToStringField(buf, "searchIterator", searchIterator,
                false);
        ObjectUtils.appendToStringField(buf, "searchParameters",
                searchParameters, false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
