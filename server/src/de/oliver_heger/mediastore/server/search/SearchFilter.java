package de.oliver_heger.mediastore.server.search;

import java.io.Serializable;

/**
 * <p>
 * Definition of an interface for filtering search results.
 * </p>
 * <p>
 * An implementation of this interface is used by the media search service for
 * sophisticated search queries. The basic workflow is as follows: The service
 * loads a chunk of data of a given entity class from the database. Then it
 * iterates over the result set, passing all entities to the filter. The filter
 * decides whether this entity matches the search criteria. If so, it returns a
 * result object (which can be the same as the original entity or a
 * corresponding data transfer object). If there is no match, it returns
 * <b>null</b>. The filter must also be able to extract a search key from an
 * entity object, so that the search can be continued at the very same point in
 * the next iteration.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <E> the entity class this filter operates on
 * @param <D> the type of data objects returned by the filter
 */
public interface SearchFilter<E, D>
{
    /**
     * Tests whether the passed in object matches the search criteria
     * implemented by the filter. If so, a corresponding data object is
     * returned. Otherwise, result is <b>null</b>.
     *
     * @param e the entity object to be evaluated
     * @return a corresponding data object or <b>null</b> if there is no match
     */
    D accepts(E e);

    /**
     * Extracts the search key of the specified entity object. The object
     * returned by this method is used when the search continues. It is then
     * passed as parameter to the search query.
     *
     * @param e the entity object
     * @return the search key of this entity object
     */
    Serializable extractSearchKey(E e);
}
