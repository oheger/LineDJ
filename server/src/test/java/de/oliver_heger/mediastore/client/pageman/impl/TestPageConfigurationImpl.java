package de.oliver_heger.mediastore.client.pageman.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;
import java.util.Set;

import org.junit.Test;

import de.oliver_heger.mediastore.client.pageman.PageConfiguration;

/**
 * Test class for {@code PageConfigurationImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPageConfigurationImpl
{
    /** Constant for the name of a test page. */
    private static final String PAGE_NAME = "testPage";

    /** Constant for a parameter prefix. */
    private static final String PARAM_PREFIX = "param";

    /** Constant for an encoded parameter prefix. */
    private static final String PARAM_ENC_PREFIX = "p_" + PARAM_PREFIX;

    /** Constant for a parameter value prefix. */
    private static final String VALUE_PREFIX = "val";

    /** Constant for the number of test parameters. */
    private static final int PARAM_COUNT = 4;

    /**
     * Appends a parameter to the specified buffer.
     *
     * @param buf the target buffer
     * @param name the parameter name
     * @param value the parameter value
     */
    private static void appendParameter(StringBuilder buf, String name,
            String value)
    {
        buf.append(';');
        buf.append(name);
        buf.append('=');
        buf.append(value);
    }

    /**
     * Generates a string with parameters.
     *
     * @param buf the target buffer
     * @param prefix the prefix of the parameters
     * @param value the value prefix of the parameters
     */
    private static void generateParameters(StringBuilder buf, String prefix,
            String value)
    {
        for (int i = 0; i < PARAM_COUNT; i++)
        {
            appendParameter(buf, prefix + i, value + i);
        }
    }

    /**
     * Tries to parse a null token.
     */
    @Test(expected = NullPointerException.class)
    public void testParseNull()
    {
        PageConfigurationImpl.parse(null);
    }

    /**
     * Tests whether a token without parameters can be parsed.
     */
    @Test
    public void testParseNoParams()
    {
        PageConfiguration config = PageConfigurationImpl.parse(PAGE_NAME);
        assertEquals("Wrong page name", PAGE_NAME, config.getPageName());
        assertTrue("Got parameters", config.getParameterNames().isEmpty());
    }

    /**
     * Tests whether the configuration object contains the expected parameters.
     *
     * @param config the configuration to check
     * @param value the value prefix
     */
    private static void checkParameters(PageConfiguration config, String value)
    {
        for (int i = 0; i < PARAM_COUNT; i++)
        {
            assertEquals("Wrong value", value + i,
                    config.getStringParameter(PARAM_PREFIX + i));
        }
    }

    /**
     * Helper method for testing whether parameters can be parsed correctly.
     *
     * @param prefix the parameter prefix
     */
    private void checkParseParams(String prefix)
    {
        StringBuilder buf = new StringBuilder();
        buf.append(PAGE_NAME);
        generateParameters(buf, prefix, VALUE_PREFIX);
        PageConfiguration config = PageConfigurationImpl.parse(buf.toString());
        checkParameters(config, VALUE_PREFIX);
        Set<String> paramNames = config.getParameterNames();
        assertEquals("Wrong number of parameters", PARAM_COUNT,
                paramNames.size());
        for (int i = 0; i < PARAM_COUNT; i++)
        {
            assertTrue("Parameter not found: " + i,
                    paramNames.contains(PARAM_PREFIX + i));
        }
        assertEquals("Wrong page name", PAGE_NAME, config.getPageName());
    }

    /**
     * Tests whether named parameters which have been encoded can be parsed
     * correctly.
     */
    @Test
    public void testParseParamsEncoded()
    {
        checkParseParams(PARAM_ENC_PREFIX);
    }

    /**
     * Tests whether named parameters which have not been encoded can be parsed
     * correctly.
     */
    @Test
    public void testParseParamsNotEncoded()
    {
        checkParseParams(PARAM_PREFIX);
    }

    /**
     * Tests whether a default parameter is handled correctly.
     */
    @Test
    public void testParseDefaultParam()
    {
        String token = PAGE_NAME + ";default=" + VALUE_PREFIX;
        PageConfiguration config = PageConfigurationImpl.parse(token);
        assertEquals("Wrong value", VALUE_PREFIX, config.getStringParameter());
        assertEquals("Wrong value (null)", VALUE_PREFIX,
                config.getStringParameter(null));
        assertEquals("Wrong number of parameters", 1, config
                .getParameterNames().size());
        assertTrue("Null param not found",
                config.getParameterNames().contains(null));
    }

    /**
     * Tests whether a token can be parsed which contains both named and a
     * default parameter.
     */
    @Test
    public void testParseParamsAndDefault()
    {
        StringBuilder buf = new StringBuilder();
        buf.append(PAGE_NAME);
        appendParameter(buf, "default", VALUE_PREFIX);
        generateParameters(buf, PARAM_ENC_PREFIX, VALUE_PREFIX);
        PageConfiguration config = PageConfigurationImpl.parse(buf.toString());
        checkParameters(config, VALUE_PREFIX);
        assertEquals("Wrong value", VALUE_PREFIX, config.getStringParameter());
        Set<String> paramNames = config.getParameterNames();
        assertEquals("Wrong number of parameters", PARAM_COUNT + 1,
                paramNames.size());
        assertTrue("Null parameter not found", paramNames.contains(null));
    }

    /**
     * Tests whether a parameter can be converted to a long.
     */
    @Test
    public void testGetLongParameter()
    {
        StringBuilder buf = new StringBuilder();
        buf.append(PAGE_NAME);
        generateParameters(buf, PARAM_ENC_PREFIX, "");
        PageConfiguration config = PageConfigurationImpl.parse(buf.toString());
        for (int i = 0; i < PARAM_COUNT; i++)
        {
            assertEquals("Wrong value: " + i, i,
                    config.getLongParameter(PARAM_PREFIX + i));
        }
    }

    /**
     * Tests whether the default parameter can be converted to a long.
     */
    @Test
    public void testGetLongParameterDefault()
    {
        final long value = 20101206223018L;
        StringBuilder buf = new StringBuilder();
        buf.append(PAGE_NAME);
        generateParameters(buf, PARAM_ENC_PREFIX, "");
        appendParameter(buf, "default", String.valueOf(value));
        PageConfiguration config = PageConfigurationImpl.parse(buf.toString());
        assertEquals("Wrong default value", value, config.getLongParameter());
    }

    /**
     * Tries to access a long parameter if parsing is not possible.
     */
    @Test(expected = NumberFormatException.class)
    public void testGetLongParameterParseError()
    {
        StringBuilder buf = new StringBuilder();
        buf.append(PAGE_NAME);
        appendParameter(buf, PARAM_ENC_PREFIX, VALUE_PREFIX);
        PageConfiguration config = PageConfigurationImpl.parse(buf.toString());
        config.getLongParameter(PARAM_PREFIX);
    }

    /**
     * Tries to access a non-existing parameter.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testGetParameterUnknown()
    {
        PageConfiguration config = PageConfigurationImpl.parse(PAGE_NAME);
        config.getStringParameter();
    }

    /**
     * Tests that the set with parameter names cannot be modified.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testGetParameterNamesModify()
    {
        StringBuilder buf = new StringBuilder();
        buf.append(PAGE_NAME);
        generateParameters(buf, PARAM_ENC_PREFIX, VALUE_PREFIX);
        PageConfiguration config = PageConfigurationImpl.parse(buf.toString());
        Iterator<String> it = config.getParameterNames().iterator();
        it.next();
        it.remove();
    }

    /**
     * Tests parse() if the parameters declaration is invalid.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidParameters()
    {
        PageConfigurationImpl.parse(PAGE_NAME + ";test");
    }

    /**
     * Tests whether encoded parameter values can be parsed correctly.
     */
    @Test
    public void testParseEncodedParameters()
    {
        StringBuilder buf = new StringBuilder();
        buf.append(PAGE_NAME);
        appendParameter(buf, "default", "c#x #e a + b");
        appendParameter(buf, PARAM_ENC_PREFIX, "a#sb#sc#s");
        PageConfiguration config = PageConfigurationImpl.parse(buf.toString());
        assertEquals("Wrong default parameter", "c# = a + b",
                config.getStringParameter());
        assertEquals("Wrong named parameter", "a;b;c;",
                config.getStringParameter(PARAM_PREFIX));
    }
}
