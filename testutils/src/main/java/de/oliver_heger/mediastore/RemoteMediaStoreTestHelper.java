package de.oliver_heger.mediastore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Comparator;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A helper class defining some utility methods for testing basic functionality
 * of objects.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class RemoteMediaStoreTestHelper
{
    /** Constant for the default range for current date checks. */
    private static final long CURRENT_DATE_RANGE = 2000;

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
     * Compares two dates and checks whether the difference lies in the
     * specified range.
     *
     * @param expected the expected date
     * @param actual the actual date
     * @param range the range in milliseconds
     */
    public static void checkDate(Date expected, Date actual, long range)
    {
        long delta = Math.abs(expected.getTime() - actual.getTime());
        assertTrue("Date difference too big: " + delta, delta <= range);
    }

    /**
     * Tests whether the specified date lies in a certain interval around the
     * current date. This method can be used to check whether a date was
     * correctly set to the current date.
     *
     * @param dt the date to be checked
     */
    public static void checkCurrentDate(Date dt)
    {
        checkDate(new Date(), dt, CURRENT_DATE_RANGE);
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

    /**
     * Helper method for testing a typical string representation of an object.
     * This method obtains the string representation for the object passed in.
     * It checks whether it looks as follows:
     * {@code <SimpleClassName>@xxxx[ <expectedAttributes> ]}.
     *
     * @param obj the object to be checked
     * @param expectedAttributes a string for the expected attributes of the
     *        object
     */
    public static void checkToString(Object obj, String expectedAttributes)
    {
        StringBuilder regex = new StringBuilder();
        regex.append(obj.getClass().getSimpleName());
        regex.append('@');
        regex.append("[0-9]+");
        regex.append(Pattern.quote("[ " + expectedAttributes + " ]"));
        Pattern expr = Pattern.compile(regex.toString());
        String s = obj.toString();
        Matcher m = expr.matcher(s);
        assertTrue("Invalid string representation: " + s, m.matches());
    }

    /**
     * Helper method for checking a comparator. The first object passed in is
     * expected to be less than the second. The method performs some checks
     * related to the ordering of the objects.
     *
     * @param <T> the type of the comparator
     * @param o1 the first object to be compared
     * @param o2 the 2nd object to be compared
     * @param comp the comparator
     */
    public static <T> void checkComparator(T o1, T o2, Comparator<T> comp)
    {
        assertTrue("Wrong order", comp.compare(o1, o2) < 0);
        assertTrue("Wrong symmetric order", comp.compare(o2, o1) > 0);
        assertEquals("Wrong reflexive order", 0, comp.compare(o1, o1));
    }
}
