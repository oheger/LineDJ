package de.oliver_heger.mediastore.server.model;

import java.util.Locale;

/**
 * <p>A class with helper methods for entities.</p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public final class EntityUtils
{
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
