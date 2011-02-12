package de.oliver_heger.mediastore.server.search;


/**
 * <p>
 * Definition of an interface for filtering search results.
 * </p>
 * <p>
 * An implementation of this interface is used by the media search service for
 * sophisticated search queries. The basic workflow is as follows: The service
 * loads a chunk of data of a given entity class from the database. Then it
 * iterates over the result set, passing all entities to the filter. The filter
 * decides whether this entity matches the search criteria.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <E> the entity class this filter operates on
 */
public interface SearchFilter<E>
{
    /**
     * Tests whether the passed in object matches the search criteria
     * implemented by the filter.
     *
     * @param e the entity object to be evaluated
     * @return a flag whether the passed in object is accepted by this filter
     */
    boolean accepts(E e);
}
