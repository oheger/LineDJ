package de.oliver_heger.mediastore.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * A helper class defining some utility methods for testing basic functionality
 * of objects.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class RemoteMediaStoreTestHelper
{
    /**
     * A helper method for checking equals() and hashCode().
     *
     * @param obj1 the first object to compare
     * @param obj2 the second object to compare (may be null)
     * @param expected the expected result
     */
    public static void checkEquals(Object obj1, Object obj2, boolean expected)
    {
        assertEquals("Wrong result of equals()", expected, obj1.equals(obj2));
        if (obj2 != null)
        {
            assertEquals("Not symmetric", expected, obj2.equals(obj1));
        }
        if (expected)
        {
            assertEquals("hashCode different", obj1.hashCode(), obj2.hashCode());
        }
    }

    /**
     * Tests equals() with other objects.
     *
     * @param obj the object to test with
     */
    public static void checkEqualsTrivial(Object obj)
    {
        checkEquals(obj, null, false);
        checkEquals(obj, RemoteMediaStoreTestHelper.class, false);
    }

    /**
     * Helper method for serializing an object. This method can be used for
     * serialization tests. The passed in object is serialized (to an in-memory
     * stream) and then read again. The copy is returned. Exceptions are thrown
     * if serialization fails.
     *
     * @param <T> the type of the object
     * @param obj the object to be serialized
     * @return the copy created through serialization
     * @throws IOException if an IO exception occurs (typically because the
     *         object cannot be serialized)
     */
    public static <T> T serialize(T obj) throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(obj);
        oos.close();
        ObjectInputStream ois =
                new ObjectInputStream(new ByteArrayInputStream(
                        bos.toByteArray()));
        try
        {
            @SuppressWarnings("unchecked")
            T result = (T) ois.readObject();
            return result;
        }
        catch (ClassNotFoundException cfex)
        {
            // this should not happen
            fail("Class not found on deserialization: " + cfex);
            return null;
        }
    }

    /**
     * Helper method for testing whether an object can be correctly serialized.
     * The object is serialized using {@link #serialize(Object)}. The result is
     * compared with the original object using equals().
     *
     * @param obj the object to test
     * @throws IOException if an IO exception occurs
     */
    public static void checkSerialization(Object obj) throws IOException
    {
        Object obj2 = serialize(obj);
        assertEquals("Serialized object not equals", obj, obj2);
    }

}
