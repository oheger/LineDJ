package de.oliver_heger.mediastore.server.sync;

import java.io.Serializable;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A default implementation of the {@link SyncResult} interface.
 * </p>
 * <p>
 * This implementation just stores the data required by the interface in member
 * fields. It is immutable and thus instances can be shared between multiple
 * threads.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of the keys used for this result object
 */
class SyncResultImpl<T> implements SyncResult<T>, Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20101120L;

    /** The key. */
    private final T key;

    /** The imported flag. */
    private final boolean imported;

    /**
     * Creates a new instance of {@code SyncResultImpl} and initializes it.
     *
     * @param k the key of the affected object
     * @param imp the imported flag
     */
    public SyncResultImpl(T k, boolean imp)
    {
        key = k;
        imported = imp;
    }

    /**
     * Returns the key of the object affected by the synchronization operation.
     *
     * @return the key
     */
    @Override
    public T getKey()
    {
        return key;
    }

    /**
     * Returns a flag whether an object was imported during the sync operation.
     *
     * @return the imported flag
     */
    @Override
    public boolean imported()
    {
        return imported;
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
        result = ObjectUtils.hash(getKey(), result);
        result = result * ObjectUtils.HASH_FACTOR + (imported() ? 1 : 0);
        return result;
    }

    /**
     * Compares this object with another one. Two instances of
     * {@code SyncResultImpl} are considered equal if all of their properties
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
        if (!(obj instanceof SyncResultImpl))
        {
            return false;
        }

        SyncResultImpl<?> c = (SyncResultImpl<?>) obj;
        return ObjectUtils.equals(getKey(), c.getKey())
                && imported() == c.imported();
    }

    /**
     * Returns a string representation of this object. This string contains the
     * values of the properties.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        ObjectUtils.appendToStringField(buf, "key", getKey(), true);
        ObjectUtils.appendToStringField(buf, "imported",
                Boolean.valueOf(imported()), false);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
