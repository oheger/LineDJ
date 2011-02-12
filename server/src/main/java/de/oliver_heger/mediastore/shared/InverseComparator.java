package de.oliver_heger.mediastore.shared;

import java.util.Comparator;

/**
 * <p>
 * A specialized {@code Comparator} implementation which negates the result of a
 * wrapped comparator.
 * </p>
 * <p>
 * This comparator can be used to reverse the order applied by an existing
 * comparator. An instance is initialized with a {@code Comparator} to be
 * wrapped. In its {@link #compare(Object, Object)} implementation it first
 * delegates to the wrapped comparator. Then it negates the result.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of objects handled by this instance
 */
public class InverseComparator<T> implements Comparator<T>
{
    /** The wrapped comparator. */
    private final Comparator<T> wrappedComparator;

    /**
     * Creates a new instance of {@code InverseComparator} and initializes it
     * with the specified comparator to be wrapped.
     *
     * @param wrapped the wrapped {@code Comparator} (must not be <b>null</b>)
     * @throws NullPointerException if the wrapped comparator is <b>null</b>
     */
    public InverseComparator(Comparator<T> wrapped)
    {
        if (wrapped == null)
        {
            throw new NullPointerException(
                    "Wrapped comparator must not be null!");
        }
        wrappedComparator = wrapped;
    }

    /**
     * Returns the comparator wrapped by this instance.
     *
     * @return the wrapped {@code Comparator}
     */
    public Comparator<T> getWrappedComparator()
    {
        return wrappedComparator;
    }

    /**
     * Compares the specified objects. Result is
     * <ul>
     * <li>-1 if the wrapped comparator returns a result &gt; 0,</li>
     * <li>+1 if the wrapped comparator returns a result &lt; 0,</li>
     * <li>0 otherwise.</li>
     * </ul>
     *
     * @param o1 the first object
     * @param o2 the second object
     * @return the result of the comparison
     */
    @Override
    public int compare(T o1, T o2)
    {
        int c = getWrappedComparator().compare(o1, o2);
        return (c < 0) ? 1 : (c > 0) ? -1 : 0;
    }

}
