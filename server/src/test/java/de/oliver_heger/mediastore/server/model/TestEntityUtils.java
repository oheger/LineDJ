package de.oliver_heger.mediastore.server.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Test class for {@code EntityUtils}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestEntityUtils
{
    /**
     * Tests whether a search string can be generated.
     */
    @Test
    public void testGenerateSearchString()
    {
        assertEquals("Wrong search string", "A SEARCH STRING",
                EntityUtils.generateSearchString("A seaRCh StrINg"));
    }

    /**
     * Tests whether generateSearchString() can handle null input.
     */
    @Test
    public void testGenerateSearchStringNull()
    {
        assertNull("Wrong result", EntityUtils.generateSearchString(null));
    }
}
