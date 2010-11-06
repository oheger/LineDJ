package de.oliver_heger.mediastore.shared.search;

import java.io.Serializable;

/**
 * <p>
 * A class for storing the parameters of a search operation.
 * </p>
 * <p>
 * Objects of this class can be passed to search operations defined by the
 * {@link MediaSearchService} interface. They contain all information required
 * by the server to perform the search.
 * </p>
 * <p>
 * Implementation note: This is just a simple bean class. It is not thread safe.
 * It is intended to be used for client-server communication only.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MediaSearchParameters implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20100913L;

    /** Stores the text to be searched for. */
    private String searchText;

    /**
     * An additional, client-specific parameter which can be passed to the
     * server.
     */
    private Serializable clientParameter;

    /** The index of the first result to be retrieved. */
    private int firstResult;

    /** The number of results to be retrieved. */
    private int maxResults;

    /**
     * Returns the text to be searched for.
     *
     * @return the search text
     */
    public String getSearchText()
    {
        return searchText;
    }

    /**
     * Returns the index of the first result to be retrieved.
     *
     * @return the index of the first result
     */
    public int getFirstResult()
    {
        return firstResult;
    }

    /**
     * Returns the maximum number of results to be retrieved.
     *
     * @return the maximum number of results
     */
    public int getMaxResults()
    {
        return maxResults;
    }

    /**
     * Returns the client parameter.
     *
     * @return the client parameter
     */
    public Serializable getClientParameter()
    {
        return clientParameter;
    }

    /**
     * Sets the client parameter. Here a client can provide an arbitrary
     * (serializable) object. This object is also available from the results
     * returned by the server. The idea is that it can be used to distinguish
     * between different search requests. For instance, it would be possible to
     * find out whether the results (coming in asynchronously) are still valid
     * or whether the user has started a new search.
     *
     * @param clientParameter the client-specific search parameter
     */
    public void setClientParameter(Serializable clientParameter)
    {
        this.clientParameter = clientParameter;
    }

    /**
     * Sets the text for the search.
     *
     * @param searchText the search text
     */
    public void setSearchText(String searchText)
    {
        this.searchText = searchText;
    }

    /**
     * Sets the index of the first result to be retrieved. This is useful if
     * results are to be paged. Note that not all search operations support
     * this.
     *
     * @param firstResult the index of the first search result
     * @throws IllegalArgumentException if the passed in index is negative
     */
    public void setFirstResult(int firstResult)
    {
        if (firstResult < 0)
        {
            throw new IllegalArgumentException(
                    "First result index must not be negative!");
        }
        this.firstResult = firstResult;
    }

    /**
     * Sets the maximum number of results to be retrieved from the server.
     * Values less than or equal 0 mean that there is no limit.
     *
     * @param maxResults the maximum number of results
     */
    public void setMaxResults(int maxResults)
    {
        this.maxResults = maxResults;
    }

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code
     */
    @Override
    public int hashCode()
    {
        final int factor = 31;
        final int seed = 17;

        int result = seed;
        if (getSearchText() != null)
        {
            result = factor * result + getSearchText().hashCode();
        }
        result = factor * result + getFirstResult();
        result = factor * result + getMaxResults();
        if (getClientParameter() != null)
        {
            result = factor * result + getClientParameter().hashCode();
        }
        return result;
    }

    /**
     * Compares this object with another one. Two instances of
     * {@code MediaSearchData} are considered equal if all their properties
     * match.
     *
     * @param obj the object to compare to
     * @return a flag whether these objects are equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof MediaSearchParameters))
        {
            return false;
        }

        MediaSearchParameters c = (MediaSearchParameters) obj;
        return ((getSearchText() == null) ? c.getSearchText() == null
                : getSearchText().equals(c.getSearchText()))
                && getFirstResult() == c.getFirstResult()
                && getMaxResults() == c.getMaxResults()
                && ((getClientParameter() == null) ? c.getClientParameter() == null
                        : getClientParameter().equals(c.getClientParameter()));
    }

    /**
     * Returns a string representation for this object. This string contains the
     * values of the properties defined for this instance.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder("MediaSearchParameters");
        buf.append(" [ searchText = ").append(getSearchText());
        buf.append(" firstResult = ").append(getFirstResult());
        if (getMaxResults() > 0)
        {
            buf.append(" maxResults = ").append(getMaxResults());
        }
        if (getClientParameter() != null)
        {
            buf.append(" clientParameter = ").append(getClientParameter());
        }
        buf.append(" ]");
        return buf.toString();
    }
}
