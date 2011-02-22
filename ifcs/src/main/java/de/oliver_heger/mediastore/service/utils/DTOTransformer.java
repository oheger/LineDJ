package de.oliver_heger.mediastore.service.utils;

import java.lang.reflect.InvocationTargetException;
import java.util.Date;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.beanutils.converters.DateConverter;

/**
 * <p>
 * An utility class supporting the transformation of various entity and transfer
 * objects.
 * </p>
 * <p>
 * It is often required to transfer objects between different application
 * layers. For instance, an entity object has to be converted to a DTO to be
 * passed from the server to the (web) client. This class supports such
 * transformations provided that the objects involved have a similar structure.
 * It copies properties with matching names from the source object to the
 * destination object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public final class DTOTransformer
{
    /** Constant for a default numeric value with the meaning "undefined". */
    public static final int UNDEFINED = 0;

    /**
     * Private constructor so that no instances can be created.
     */
    private DTOTransformer()
    {
    }

    /**
     * Copies properties from the source object to the destination object. All
     * properties with matching names are copied, other ones are ignored.
     *
     * @param source the source object
     * @param dest the destination object
     * @throws IllegalArgumentException if one of the objects is <b>null</b> or
     *         the transformation fails
     */
    public static void transform(Object source, Object dest)
    {
        try
        {
            BeanUtils.copyProperties(dest, source);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalArgumentException(e);
        }
        catch (InvocationTargetException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Transforms the given {@code Integer} object to a primitive value. This
     * method is <b>null</b>-safe; if the parameter is <b>null</b>, a default
     * value representing an undefined value is returned.
     *
     * @param v the value to be converted
     * @return the converted primitive value
     * @see #UNDEFINED
     */
    public static int toPrimitive(Integer v)
    {
        return (v != null) ? v.intValue() : UNDEFINED;
    }

    /**
     * Transforms the given {@code Long} object to a primitive value. An
     * overloaded version for {@code Long} objects.
     *
     * @param v the value to be converted
     * @return the converted primitive value
     * @see #UNDEFINED
     */
    public static long toPrimitive(Long v)
    {
        return (v != null) ? v.longValue() : UNDEFINED;
    }

    /**
     * Transforms the given numeric value to an {@code Integer} object. If the
     * value equals the {@link #UNDEFINED} constant, <b>null</b> is returned.
     * This method is the counterpart to {@link #toPrimitive(Integer)}.
     *
     * @param v the value to be converted
     * @return the corresponding wrapper object
     */
    public static Integer toWrapper(int v)
    {
        return (v != UNDEFINED) ? Integer.valueOf(v) : null;
    }

    /**
     * Transforms the given numeric value to a {@code Long} object. This is an
     * overloaded version for <b>long</b> values.
     *
     * @param v the value to be converted
     * @return the corresponding wrapper object
     */
    public static Long toWrapper(long v)
    {
        return (v != UNDEFINED) ? Long.valueOf(v) : null;
    }

    /**
     * Handles the initialization of default converters. For instance, a
     * specialized date converter has to be installed that can deal with
     * <b>null</b> values.
     */
    private static void initDefaultConverters()
    {
        ConvertUtils.register(new DateConverter(null), Date.class);
    }

    static
    {
        initDefaultConverters();
    }
}
