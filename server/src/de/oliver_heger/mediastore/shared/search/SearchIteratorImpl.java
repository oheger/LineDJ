package de.oliver_heger.mediastore.shared.search;

import java.io.Serializable;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * An implementation of the {@link SearchIterator} interface used by
 * {@link MediaSearchService} to store all information about a currently running
 * search.
 * </p>
 * <p>
 * {@link MediaSearchService} expects that objects of this class are passed as
 * iterator objects. It performs a hard cast in order to retrieve the data
 * stored in this object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SearchIteratorImpl implements SearchIterator, Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101015L;

    /** Stores the total number of search records. */
    private long recordCount;

    /** Stores the current position in the result set. */
    private long currentPosition;

    /** Stores the index of the current page in the result set. */
    private Integer currentPage;

    /** Stores the total number of pages in the result set. */
    private Integer pageCount;

    /** Stores the search key of the last record processed. */
    private Serializable searchKey;

    /** A flag whether more results might be available. */
    private boolean hasNext;

    /**
     * Returns a flag whether more data is available. This implementation just
     * returns the flag set by the {@link #setHasNext(boolean)} method.
     *
     * @return a flag whether more data is available
     */
    @Override
    public boolean hasNext()
    {
        return hasNext;
    }

    /**
     * Returns the current position in the result set. This implementation just
     * returns an internal member field.
     *
     * @return the current position in the result set
     */
    @Override
    public long getCurrentPosition()
    {
        return currentPosition;
    }

    /**
     * Returns the total number of records to be searched. This implementation
     * just returns an internal member field.
     *
     * @return the total number of records
     */
    @Override
    public long getRecordCount()
    {
        return recordCount;
    }

    /**
     * Returns the search key of the last object processed by the previous
     * search.
     *
     * @return the last search key processed
     * @see #setSearchKey(Object)
     */
    public Serializable getSearchKey()
    {
        return searchKey;
    }

    /**
     * Sets the search of the last object processed by the current search
     * operation. This key is used for the next search step to construct a query
     * that starts immediately after the records already processed.
     *
     * @param searchKey the search key of the last object processed in the
     *        current search
     */
    public void setSearchKey(Serializable searchKey)
    {
        this.searchKey = searchKey;
    }

    /**
     * Sets the total number of available search records.
     *
     * @param recordCount the number of records to be searched
     */
    public void setRecordCount(long recordCount)
    {
        this.recordCount = recordCount;
    }

    /**
     * Sets the current position in the search result set.
     *
     * @param currentPosition the current position
     */
    public void setCurrentPosition(long currentPosition)
    {
        this.currentPosition = currentPosition;
    }

    /**
     * Sets a flag whether more data is available.
     *
     * @param hasNext the flag
     */
    public void setHasNext(boolean hasNext)
    {
        this.hasNext = hasNext;
    }

    /**
     * Returns the current page in the result set. This can be <b>null</b> if
     * the page is unknown.
     *
     * @return the index of the current page or <b>null</b>
     */
    public Integer getCurrentPage()
    {
        return currentPage;
    }

    /**
     * Returns the number of pages in the result set. This can be <b>null</b> if
     * the page count is unknown.
     *
     * @return the total number of pages in the result view
     */
    public Integer getPageCount()
    {
        return pageCount;
    }

    /**
     * Initializes the properties related to paging. With this method the index
     * of the current page and the total number of pages can be set. Either both
     * values must be non-<b>null</b> or <b>null</b>, otherwise an exception is
     * thrown.
     *
     * @param currentPage the index of the current page
     * @param pageCount the total number of pages
     * @throws IllegalArgumentException if one value is <b>null</b> and the
     *         other one is not <b>null</b>
     */
    public void initializePaging(Integer currentPage, Integer pageCount)
    {
        if ((currentPage == null) ^ (pageCount == null))
        {
            throw new IllegalArgumentException("Invalid paging information! "
                    + "Paging data must be either null completely or non-null!");
        }

        this.currentPage = currentPage;
        this.pageCount = pageCount;
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
        result =
                result
                        * ObjectUtils.HASH_FACTOR
                        + (int) ((getCurrentPosition() ^ (getCurrentPosition() >>> 32)));
        result =
                result
                        * ObjectUtils.HASH_FACTOR
                        + (int) ((getRecordCount() ^ (getRecordCount() >>> 32)));
        result = result * ObjectUtils.HASH_FACTOR + (hasNext() ? 1 : 0);
        result = ObjectUtils.hash(getSearchKey(), result);
        // paging information skipped
        return result;
    }

    /**
     * Compares this object with another one. Two instances of this class are
     * equal if all of their properties are equal.
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
        if (!(obj instanceof SearchIteratorImpl))
        {
            return false;
        }

        SearchIteratorImpl c = (SearchIteratorImpl) obj;
        return getCurrentPosition() == c.getCurrentPosition()
                && getRecordCount() == c.getRecordCount()
                && hasNext == c.hasNext()
                && ObjectUtils.equals(getSearchKey(), c.getSearchKey())
                && ObjectUtils.equals(getCurrentPage(), c.getCurrentPage())
                && ObjectUtils.equals(getPageCount(), c.getPageCount());
    }

    /**
     * Returns a string for this object. This string contains the values of the
     * most important properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "currentPosition",
                getCurrentPosition(), false);
        ObjectUtils.appendToStringField(buf, "recordCount", getRecordCount(),
                false);
        ObjectUtils.appendToStringField(buf, "hasNext", hasNext(), false);
        ObjectUtils.appendToStringField(buf, "currentPage", getCurrentPage(),
                false);
        ObjectUtils
                .appendToStringField(buf, "pageCount", getPageCount(), false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
