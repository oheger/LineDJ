package de.oliver_heger.mediastore.server.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.server.model.SongEntity;

/**
 * Test class for {@code SongSearchFilter}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongSearchFilter
{
    /** Constant for the search text. */
    private static final String SEARCH_TEXT = "search";

    /** The filter to be tested. */
    private SongSearchFilter filter;

    @Before
    public void setUp() throws Exception
    {
        filter = new SongSearchFilter(SEARCH_TEXT);
    }

    /**
     * Tests accept() if the entity does not have a name.
     */
    @Test
    public void testAcceptNullName()
    {
        assertFalse("Accepted null name", filter.accepts(new SongEntity()));
    }

    /**
     * Tests accept() if the expected result is true.
     */
    @Test
    public void testAcceptTrue()
    {
        SongEntity song = new SongEntity();
        song.setName(SEARCH_TEXT);
        assertTrue("Not accepted (1)", filter.accepts(song));
        song.setName("Contains " + SEARCH_TEXT + " and more!");
        assertTrue("Not accepted (2)", filter.accepts(song));
        song.setName("One more: " + SEARCH_TEXT.toUpperCase(Locale.ENGLISH));
        assertTrue("Not accepted (3)", filter.accepts(song));
    }

    /**
     * Tests accept() if the expected result is false.
     */
    @Test
    public void testAcceptFalse()
    {
        SongEntity song = new SongEntity();
        song.setName("??");
        assertFalse("Accepted", filter.accepts(song));
    }
}
