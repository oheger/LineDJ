package de.oliver_heger.mediastore.client;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import com.google.gwt.user.client.ui.Grid;

/**
 * <p>
 * A specialized base class of grid table models that provides an implementation
 * of the {@code fetchComparator()} method based on a map.
 * </p>
 * <p>
 * Instances of this class are passed a map with property names and associated
 * comparator objects when they are constructed. When a comparator for a
 * property is to be fetched this map is consulted.
 * </p>
 * <p>
 * Often enumeration classes are used to define the comparators for a given
 * object type. This class defines a helper method which transforms such an
 * enumeration class into a map compatible with the algorithm for retrieving
 * comparators.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects stored in this model
 */
public abstract class ComparatorMapGridTableModel<T> extends GridTableModel<T>
{
    /** Constant for the comparator suffix. */
    private static final String COMPARATOR_SUFFIX = "_COMPARATOR";

    /** The map with the comparators. */
    private final Map<String, Comparator<T>> comparators;

    /**
     * Creates a new instance of {@code ComparatorMapGridTableModel} and
     * initializes it with the grid to be managed and the map of comparators.
     *
     * @param grid the underlying grid widget
     * @param compMap the map with the comparators
     */
    protected ComparatorMapGridTableModel(Grid grid,
            Map<String, Comparator<T>> compMap)
    {
        super(grid);

        if (compMap == null)
        {
            comparators = Collections.emptyMap();
        }
        else
        {
            Map<String, Comparator<T>> temp =
                    new HashMap<String, Comparator<T>>(compMap);
            comparators = Collections.unmodifiableMap(temp);
        }
    }

    /**
     * Transforms the given comparators/enumeration constants into a map with
     * comparators. This method expects that the enumeration class implements
     * the {@code Comparator} interface. It creates a map that uses the
     * enumeration names as keys and the objects as values.
     *
     * @param <E> the type of the enumeration
     * @param <T> the type of the comparator
     * @param values the enumeration constants
     * @return a map with comparators
     */
    public static <T> Map<String, Comparator<T>> comparatorMapForEnum(
            Comparator<T>... values)
    {
        Map<String, Comparator<T>> map = new HashMap<String, Comparator<T>>();
        for (Comparator<T> v : values)
        {
            map.put(((Enum<?>) v).name(), v);
        }

        return map;
    }

    /**
     * Returns an unmodifiable map with the comparators supported by this
     * instance.
     *
     * @return the map with the comparators
     */
    public Map<String, Comparator<T>> getComparatorMap()
    {
        return comparators;
    }

    /**
     * Returns the comparator for the specified property. This implementation
     * just looks up the map with comparators that was passed to the
     * constructor. If no comparator is found for the specified property name,
     * the method checks whether there is a comparator with the property name
     * transformed to upper case and the suffix <em>_COMPARATOR</em>.
     *
     * @param property the name of the property in question
     * @return the comparator for this property (can be <b>null</b> if the
     *         property is not known)
     */
    @Override
    protected Comparator<T> fetchComparator(String property)
    {
        Comparator<T> result = getComparatorMap().get(property);

        if (result == null && property != null)
        {
            result = getComparatorMap().get(deriveComparatorName(property));
        }

        return result;
    }

    /**
     * Tries to derive the name of a comparator from a property name.
     *
     * @param property the name of the property
     * @return the corresponding comparator name
     */
    private static String deriveComparatorName(String property)
    {
        return property.toUpperCase() + COMPARATOR_SUFFIX;
    }
}
