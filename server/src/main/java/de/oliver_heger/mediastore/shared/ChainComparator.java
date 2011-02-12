package de.oliver_heger.mediastore.shared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;

/**
 * <p>
 * A specialized {@code Comparator} implementation which delegates to a number
 * of child comparators.
 * </p>
 * <p>
 * When constructing an instance of this class an arbitrary number of child
 * comparators can be provided. In its {@link #compare(Object, Object)}
 * implementation this class delegates to the first child comparator. If it
 * returns a result different from 0, this result is returned. Otherwise, the
 * next child comparator is queried, and so on until either no more child
 * comparators are available or a non 0 result is retrieved.
 * </p>
 * <p>
 * This {@code Comparator} implementation allows constructing more complex order
 * conditions on top of existing simple {@code Comparator} objects. For
 * instance, comparators can be implemented for specific properties of a class.
 * Then an arbitrary complex sort order can be constructed by combining such
 * property comparators.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of objects that can be compared
 */
public class ChainComparator<T> implements Comparator<T>
{
    /** The list with the child comparators. */
    private final Collection<Comparator<T>> childComparators;

    /**
     * Creates a new instance of {@code ChainComparator} and initializes it with
     * the given child comparators.
     *
     * @param children a collection with the child comparators (must not be
     *        <b>null</b>)
     * @throws NullPointerException if the collection is <b>null</b> or contains
     *         <b>null</b> elements
     */
    public ChainComparator(Collection<? extends Comparator<T>> children)
    {
        childComparators = new ArrayList<Comparator<T>>(children);

        for (Comparator<T> c : childComparators)
        {
            if (c == null)
            {
                throw new NullPointerException(
                        "Child comparator must not be null!");
            }
        }
    }

    /**
     * Compares the specified objects. As described in the class comment, the
     * actual comparison is done by delegating to the child comparators.
     *
     * @param o1 the first object
     * @param o2 the second object
     * @return the result of the comparison
     */
    @Override
    public int compare(T o1, T o2)
    {
        int result = 0;

        if (o1 != o2)
        {
            for (Iterator<Comparator<T>> it = childComparators.iterator(); it
                    .hasNext() && result == 0;)
            {
                Comparator<T> comp = it.next();
                result = comp.compare(o1, o2);
            }
        }

        return result;
    }
}
