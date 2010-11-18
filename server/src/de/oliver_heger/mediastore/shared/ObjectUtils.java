package de.oliver_heger.mediastore.shared;

import java.util.Locale;

/**
 * <p>
 * An utility class providing common functionality for implementing default
 * methods inherited from {@code Object}.
 * </p>
 * <p>
 * This class provides helper methods, e.g. for comparing objects which can be
 * <b>null</b>, for calculating hash codes, or for generating a string
 * representation.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public final class ObjectUtils
{
    /** Constant for the seed value of hash code functions. */
    public static final int HASH_SEED = 17;

    /** Constant for the factor used within hash code functions. */
    public static final int HASH_FACTOR = 31;

    /** Constant for the prefix of the data for a toString() implementation. */
    public static final String TOSTR_DATA_PREFIX = "[";

    /** Constant for the suffix of the data for a toString() implementation. */
    public static final String TOSTR_DATA_SUFFIX = " ]";

    /** Constant for the field value separator. */
    private static final String VALUE_SEPARATOR = " = ";

    /** Constant for the initial string buffer size. */
    private static final int BUF_SIZE = 128;

    /** Constant for the identity separator in generated strings. */
    private static final char ID_SEPARATOR = '@';

    /** Constant for the package separator. */
    private static final char PACKAGE_SEPARATOR = '.';

    /** Constant for a space character. */
    private static final char SPACE = ' ';

    /**
     * Tests whether two objects are equal. The objects can be <b>null</b>. In
     * this case result is <b>true</b> only if both objects are <b>null</b>.
     *
     * @param o1 object 1
     * @param o2 object 2
     * @return a flag whether these objects are equal
     */
    public static boolean equals(Object o1, Object o2)
    {
        return (o1 == null) ? o2 == null : o1.equals(o2);
    }

    /**
     * Tests whether two strings are equal ignoring case. The strings can be
     * <b>null</b>. In this case result is <b>true</b> only if both strings are
     * <b>null</b>.
     *
     * @param s1 string 1
     * @param s2 string 2
     * @return a flag whether these strings are equal ignoring case
     */
    public static boolean equalsIgnoreCase(String s1, String s2)
    {
        return (s1 == null) ? s2 == null : s1.equalsIgnoreCase(s2);
    }

    /**
     * Updates the result of a hash function for the specified object. This
     * method is passed in the current result of the hash function. If the
     * object is <b>null</b>, it is returned without changes. Otherwise, the
     * result is multiplied with the hash factor, and the hash value of the
     * object is added.
     *
     * @param o the object to be hashed
     * @param currentResult the current result of the hash function
     * @return the updated result of the hash function
     */
    public static int hash(Object o, int currentResult)
    {
        int result = currentResult;
        if (o != null)
        {
            result = HASH_FACTOR * result + o.hashCode();
        }
        return result;
    }

    /**
     * Updates the result of a hash function for the specified string ignoring
     * case. This method works like {@link #hash(Object, int)}, but the string
     * is transformed to lower case first (if it is not <b>null</b>).
     *
     * @param s the string to be hashed
     * @param currentResult the current result of the hash function
     * @return the updated result of the hash function
     */
    public static int hashIgnoreCase(String s, int currentResult)
    {
        return (s == null) ? currentResult : hash(s.toLowerCase(),
                currentResult);
    }

    /**
     * Creates and initializes a string buffer for a {@code toString()}
     * implementation for the specified object. This buffer already contains the
     * class name and the system identity of the object.
     *
     * @param obj the object (must not be <b>null</b>)
     * @return the string buffer
     */
    public static StringBuilder prepareToStringBuffer(Object obj)
    {
        StringBuilder buf = new StringBuilder(BUF_SIZE);
        String clsName = obj.getClass().getName();
        int pos = clsName.lastIndexOf(PACKAGE_SEPARATOR);
        buf.append(clsName.substring(pos + 1));
        buf.append(ID_SEPARATOR);
        buf.append(System.identityHashCode(obj));
        buf.append(TOSTR_DATA_PREFIX);
        return buf;
    }

    /**
     * Helper method for appending a member field to the generated string
     * representation of an object. This method generates a typical
     * {@code field = value} statement. If the value is <b>null</b>, a statement
     * is only generated if the {@code force} parameter is <b>true</b>.
     *
     * @param buf the target buffer
     * @param name the name of the member field
     * @param value the value of the field
     * @param force a flag whether <b>null</b> values should be added, too
     */
    public static void appendToStringField(StringBuilder buf, String name,
            Object value, boolean force)
    {
        if (value != null || force)
        {
            buf.append(SPACE);
            buf.append(name).append(VALUE_SEPARATOR).append(value);
        }
    }

    /**
     * Returns the search string for the specified string. If the passed in
     * string is not <b>null</b>, it is converted to upper case. This is
     * necessary because the {@code upper()} function in JPA queries obviously
     * does not seem to work. Therefore some entity classes which require case
     * independent search functionality store the corresponding fields
     * redundantly in upper case. This helper method can be used to generate
     * such redundant field values.
     *
     * @param s the string to be searched for
     * @return the modified search string
     */
    public static String generateSearchString(String s)
    {
        return (s == null) ? null : s.toUpperCase(Locale.ENGLISH);
    }
}
