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
}
