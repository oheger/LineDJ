package de.oliver_heger.mediastore.client.pageman.impl;

/**
 * <p>
 * A helper class used internally by the classes in the implementation package
 * that provides some common functionality related to the handling of parameters
 * for pages.
 * </p>
 * <p>
 * This class defines some common constants for separator characters required
 * for the definition of multiple parameters. It handles encoding and decoding
 * of parameters. This implementation prefixes all parameters except for the
 * default parameter with a constant text. This avoids name clashes between
 * custom parameters and the default parameter.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class ParametersHelper
{
    /** Constant for the name of the default parameter. */
    public static final String PARAM_DEFAULT = "default";

    /** Constant for the standard parameter prefix. */
    public static final String PARAM_PREFIX = "p_";

    /**
     * Constant for the parameter separator. This character separates two
     * parameter declarations.
     */
    public static final char PARAM_SEPARATOR = ';';

    /**
     * Constant for the parameter value separator. This character separates the
     * parameter name from its value.
     */
    public static final char VALUE_SEPARATOR = '=';

    /** A string with reserved characters which need to be encoded. */
    private static final String SPECIAL_CHARS = ";=#";

    /** An array with the replacements for the special characters. */
    private static final String[] REPLACEMENTS = {
        "#s", "#e", "#x"
    };

    /** Constant for the escaping character. */
    private static final char ESCAPE = '#';

    /** Constant for a length offset for the creation of a string buffer. */
    private static final int BUF_SIZE_OFS = 16;

    /**
     * Normalizes the specified parameter. If the parameter is the default
     * parameter or already starts with the standard prefix, it is returned
     * unchanged. Otherwise, the standard prefix is added. This method provides
     * some robustness when parsing a page token because it should be able to
     * handle cases where the default encoding is not used.
     *
     * @param param the parameter name
     * @return the normalized parameter name
     */
    public static String normalize(String param)
    {
        if (PARAM_DEFAULT.equals(param) || param.startsWith(PARAM_PREFIX))
        {
            return param;
        }
        return PARAM_PREFIX + param;
    }

    /**
     * Encodes the specified parameter. This method adds the standard prefix to
     * the parameter name. A <b>null</b> parameter is mapped to the default
     * parameter name.
     *
     * @param param the parameter name
     * @return the encoded parameter name
     */
    public static String encode(String param)
    {
        return (param == null) ? PARAM_DEFAULT : PARAM_PREFIX + param;
    }

    /**
     * Decodes the specified parameter. This is the reverse operation of
     * {@link #encode(String)}: The standard prefix is removed from the
     * parameter. Other parameters are mapped to <b>null</b> which represents
     * the default parameter.
     *
     * @param param the parameter name
     * @return the decoded parameter name
     */
    public static String decode(String param)
    {
        if (param.startsWith(PARAM_PREFIX))
        {
            return param.substring(PARAM_PREFIX.length());
        }
        return null;
    }

    /**
     * Encodes a string parameter value. When parsing a page token some
     * characters are used for splitting parameters and their values. If a
     * parameter value contained some of these characters, the parsing process
     * would be confused, so they need to be encoded. This is done by this
     * method. It uses an easy proprietary encoding algorithm for eliminating
     * reserved characters.
     *
     * @param value the value to be encoded
     * @return the encoded value
     */
    public static String encodeValue(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            if (SPECIAL_CHARS.indexOf(value.charAt(i)) >= 0)
            {
                return doEncodeValue(value, i);
            }
        }

        // no special character found => return string unchanged
        return value;
    }

    /**
     * Decodes a string parameter value. This method performs the inverse
     * transformation on a string parameter value as
     * {@link #encodeValue(String)}. It ensures a parameter value that has been
     * encoded is restored to its original form.
     *
     * @param value the value to be decoded
     * @return the decoded value
     */
    public static String decodeValue(String value)
    {
        return (value.indexOf(ESCAPE) >= 0) ? doDecodeValue(value) : value;
    }

    /**
     * Helper method for encoding reserved characters in the value of a
     * parameter.
     *
     * @param value the string to be encoded
     * @param startIdx the start index where the first reserved character was
     *        found
     * @return the encoded value
     */
    private static String doEncodeValue(String value, int startIdx)
    {
        StringBuilder buf = new StringBuilder(value.length() + BUF_SIZE_OFS);
        buf.append(value.substring(0, startIdx));

        for (int i = startIdx; i < value.length(); i++)
        {
            char c = value.charAt(i);
            int pos = SPECIAL_CHARS.indexOf(c);
            if (pos >= 0)
            {
                buf.append(REPLACEMENTS[pos]);
            }
            else
            {
                buf.append(c);
            }
        }

        return buf.toString();
    }

    /**
     * Helper method for decoding a parameter value. This method simply replaces
     * all escape sequences by their original values.
     *
     * @param value the value to be decoded
     * @return the decoded value
     */
    private static String doDecodeValue(String value)
    {
        String repl = value;

        for (int i = 0; i < SPECIAL_CHARS.length(); i++)
        {
            repl =
                    repl.replace(REPLACEMENTS[i],
                            String.valueOf(SPECIAL_CHARS.charAt(i)));
        }

        return repl;
    }
}
