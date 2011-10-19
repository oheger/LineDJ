package de.oliver_heger.mediastore.shared.search;

import java.io.Serializable;

import de.oliver_heger.mediastore.shared.ObjectUtils;

/**
 * <p>
 * A simple class for representing an order definition for a database query.
 * </p>
 * <p>
 * Objects of this class can be added to a {@link MediaSearchParameters} object
 * to define the order in which query results should be returned. Thus it is
 * possible to sort results produced by the media search service.
 * </p>
 * <p>
 * Implementation note: This is just a simple bean class. It is not thread safe.
 * It is intended to be used for client-server communication only.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OrderDef implements Serializable
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20111019L;

    /** Constant for the descending keyword. */
    private static final String DESCENDING = " DESC";

    /** The name of the field which should be sorted. */
    private String fieldName;

    /** A flag for descending sort order. */
    private boolean descending;

    /**
     * Returns the name of the field affected by this {@code OrderDef} instance.
     * The generated {@code ORDER BY} clause will reference this property.
     *
     * @return the name of the field to be sorted
     */
    public String getFieldName()
    {
        return fieldName;
    }

    /**
     * Sets the name of the field affected by this {@code OrderDef} instance.
     * This must be a valid property name of the entity the current query is
     * about.
     *
     * @param fieldName the name of the field to be sorted
     */
    public void setFieldName(String fieldName)
    {
        this.fieldName = fieldName;
    }

    /**
     * Returns a flag whether the sort order is descending.
     *
     * @return <b>true</b> for descending sort order, <b>false</b> for ascending
     *         sort order
     */
    public boolean isDescending()
    {
        return descending;
    }

    /**
     * Sets the descending sort order flag. Per default, sort order is
     * ascending. Setting this property to <b>true</b>, sort order can be
     * changed to descending.
     *
     * @param descending the descending sort order flag
     */
    public void setDescending(boolean descending)
    {
        this.descending = descending;
    }

    /**
     * Appends a string representation of this order definition to the given
     * string buffer. This method can be used to integrate this order definition
     * into a database query. To the buffer the field name and a keyword for the
     * descending flag are appended.
     *
     * @param buf the target buffer
     */
    public void appendOrderDefinition(StringBuilder buf)
    {
        buf.append(getFieldName());
        if (isDescending())
        {
            buf.append(DESCENDING);
        }
    }

    /**
     * Compares this object with another one. Two instances of {@code OrderDef}
     * are considered equal if they have the same field name and descending
     * flag.
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
        if (!(obj instanceof OrderDef))
        {
            return false;
        }

        OrderDef c = (OrderDef) obj;
        return ObjectUtils.equals(getFieldName(), c.getFieldName())
                && isDescending() == c.isDescending();
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
        result = ObjectUtils.hash(getFieldName(), result);
        result = ObjectUtils.HASH_FACTOR * result + (isDescending() ? 1 : 0);
        return result;
    }

    /**
     * Returns a string representation for this object. This string contains the
     * field name and, if the descending flag is set, the {@code DESC} keyword.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        StringBuilder buf = ObjectUtils.prepareToStringBuffer(this);
        appendOrderDefinition(buf);
        buf.append(ObjectUtils.TOSTR_DATA_SUFFIX);
        return buf.toString();
    }
}
