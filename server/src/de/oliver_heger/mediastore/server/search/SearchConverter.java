package de.oliver_heger.mediastore.server.search;

/**
 * <p>
 * Definition of an interface for converting entity objects to data objects.
 * </p>
 * <p>
 * In most cases, the entity objects dealt with on the server cannot be
 * transformed directly to the client (for instance due to restrictions in the
 * classes used on the server for keys or because the client has a specific, use
 * case-driven view on the data). Then a data conversion is necessary. This
 * interface defines a generic mechanism for doing such a conversion. It
 * contains a method which expects an object of the source entity class and
 * returns an object of the target data object class.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <E> the type of the entity objects used as input for this converter
 * @param <D> the type of the data objects used as output for this converter
 */
public interface SearchConverter<E, D>
{
    /**
     * Converts a source entity object to a target data object.
     *
     * @param e the entity object (must not be <b>null</b>)
     * @return the corresponding converted data object
     */
    D convert(E e);
}
