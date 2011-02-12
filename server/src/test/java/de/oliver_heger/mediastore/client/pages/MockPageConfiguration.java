package de.oliver_heger.mediastore.client.pages;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import junit.framework.Assert;
import de.oliver_heger.mediastore.client.pageman.PageConfiguration;

/**
 * <p>
 * A mock implementation of the {@link PageConfiguration} interface.
 * </p>
 * <p>
 * An instance of this class is initialized with a page name and a map of
 * parameters. The methods for accessing parameters directly return the values
 * of the map without any type conversions. So it can be tested whether
 * parameters are accessed as expected. Access to an unknown parameters causes
 * an exception to be thrown. The default parameter is mapped to the <b>null</b>
 * key.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MockPageConfiguration implements PageConfiguration
{
    /** The map with the parameters. */
    private final Map<String, Object> parameters;

    /** The name of the page. */
    private final String pageName;

    /**
     * Creates a new instance of {@code MockPageConfiguration}.
     *
     * @param name the page name
     * @param params the map with the parameters
     */
    public MockPageConfiguration(String name,
            Map<String, ? extends Object> params)
    {
        parameters = new HashMap<String, Object>(params);
        pageName = name;
    }

    @Override
    public String getPageName()
    {
        return pageName;
    }

    @Override
    public String getStringParameter()
    {
        return getStringParameter(null);
    }

    @Override
    public long getLongParameter()
    {
        return getLongParameter(null);
    }

    @Override
    public String getStringParameter(String name)
    {
        checkParameter(name);
        return (String) parameters.get(name);
    }

    @Override
    public long getLongParameter(String name)
    {
        checkParameter(name);
        return (Long) parameters.get(name);
    }

    @Override
    public Set<String> getParameterNames()
    {
        return Collections.unmodifiableSet(parameters.keySet());
    }

    /**
     * Helper method for testing whether the parameter with the given name
     * exists.
     *
     * @param name the name of the parameter
     */
    private void checkParameter(String name)
    {
        Assert.assertTrue("Unknown parameter: " + name,
                parameters.containsKey(name));
    }
}
