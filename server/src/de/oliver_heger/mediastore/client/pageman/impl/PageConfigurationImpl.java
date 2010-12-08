package de.oliver_heger.mediastore.client.pageman.impl;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gwt.dev.util.collect.HashMap;

import de.oliver_heger.mediastore.client.pageman.PageConfiguration;

/**
 * <p>
 * A default implementation of the {@link PageConfiguration} interface.
 * </p>
 * <p>
 * Instances of this class are created by parsing a token obtained from the
 * history manager. The results of the parsing operation are stored in internal,
 * immutable member fields. The methods defined by the {@link PageConfiguration}
 * interface simply access these fields.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class PageConfigurationImpl implements PageConfiguration
{
    /**
     * Constant for a regular expression to extract the parameters from the page
     * token.
     */
    private static final String SEPARATORS = "["
            + ParametersHelper.PARAM_SEPARATOR
            + ParametersHelper.VALUE_SEPARATOR + "]";

    /** Stores the name of the associated page. */
    private final String pageName;

    /** A map with the parameters of the associated page. */
    private final Map<String, String> parameters;

    /** A set with the (decoded) names of the parameters available. */
    private final Set<String> parameterNames;

    /**
     * Creates a new instance of {@code PageConfigurationImpl} and initializes
     * it with the name of the page and the map with parameters.
     *
     * @param name the page name
     * @param params a map with the parameters
     */
    private PageConfigurationImpl(String name, Map<String, String> params)
    {
        pageName = name;
        parameters = params;
        parameterNames = initParameterNames(params);
    }

    /**
     * Creates a {@code PageConfiguration} instance from a history token. The
     * token must conform to the format used by the page manager component to
     * encode page names and parameters.
     *
     * @param token the token to be parsed (must not be <b>null</b>)
     * @return the configuration object with the data extracted from the token
     * @throws NullPointerException if not token is passed in
     * @throws IllegalArgumentException if the parameters cannot be parsed
     */
    public static PageConfiguration parse(String token)
    {
        int pos = token.indexOf(ParametersHelper.PARAM_SEPARATOR);
        String pageName;
        Map<String, String> params = new HashMap<String, String>();

        if (pos >= 0)
        {
            pageName = token.substring(0, pos);
            parseParameters(params, token.substring(pos + 1));
        }
        else
        {
            pageName = token;
        }

        return new PageConfigurationImpl(pageName, params);
    }

    /**
     * Returns the name of the page associated with this configuration.
     *
     * @return the page name
     */
    @Override
    public String getPageName()
    {
        return pageName;
    }

    /**
     * Returns the default string parameter.
     *
     * @return the default string parameter
     * @throws IllegalArgumentException if the parameter cannot be resolved
     */
    @Override
    public String getStringParameter()
    {
        return getStringParameter(null);
    }

    /**
     * Returns the long value of the default parameter.
     *
     * @return the value of the default parameter converted to a long
     * @throws IllegalArgumentException if the parameter is unknown
     * @throws NumberFormatException if the parameter cannot be converted to a
     *         long
     */
    @Override
    public long getLongParameter()
    {
        return getLongParameter(null);
    }

    /**
     * Returns the string value of the specified named parameter.
     *
     * @param name the parameter name
     * @return the string value of this parameter
     * @throws IllegalArgumentException if the parameter is unknown
     */
    @Override
    public String getStringParameter(String name)
    {
        String result = parameters.get(ParametersHelper.encode(name));
        if (result == null)
        {
            throw new IllegalArgumentException("Unknown parameter: " + name);
        }
        return result;
    }

    /**
     * Returns the long value of the specified named parameter.
     *
     * @param name the name of the parameter
     * @return the value of this parameter parsed as a long
     * @throws IllegalArgumentException if the parameter is unknown
     * @throws NumberFormatException if the parameter cannot be converted to a
     *         long
     */
    @Override
    public long getLongParameter(String name)
    {
        return Long.valueOf(getStringParameter(name));
    }

    /**
     * Returns a set with the names of all existing parameters.
     *
     * @return a set with the parameter names
     */
    @Override
    public Set<String> getParameterNames()
    {
        return parameterNames;
    }

    /**
     * Generates an unmodifiable set with the names of the parameters available.
     *
     * @param params the map with all parameters
     * @return a set with the decoded names of the parameters available
     */
    private Set<String> initParameterNames(Map<String, String> params)
    {
        Set<String> names = new HashSet<String>();
        for (String n : params.keySet())
        {
            names.add(ParametersHelper.decode(n));
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Parses the part of a page token which defines the parameters.
     *
     * @param map the map for storing the results
     * @param params the string with the parameters declaration
     * @throws IllegalArgumentException if the parameters cannot be parsed
     */
    private static void parseParameters(Map<String, String> map, String params)
    {
        String[] parts = params.split(SEPARATORS);
        if (parts.length % 2 != 0)
        {
            throw new IllegalArgumentException(
                    "Invalid parameters declaration: " + params);
        }

        for (int i = 0; i < parts.length; i += 2)
        {
            map.put(ParametersHelper.normalize(parts[i]),
                    ParametersHelper.decodeValue(parts[i + 1]));
        }
    }
}
