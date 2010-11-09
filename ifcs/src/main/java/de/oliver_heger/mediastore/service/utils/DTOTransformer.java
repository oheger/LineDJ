package de.oliver_heger.mediastore.service.utils;

import java.lang.reflect.InvocationTargetException;

import org.apache.commons.beanutils.BeanUtils;

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
}
