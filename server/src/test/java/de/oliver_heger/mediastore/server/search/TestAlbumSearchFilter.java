package de.oliver_heger.mediastore.server.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.server.model.AlbumEntity;

/**
 * Test class for {@code AlbumSearchFilter}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumSearchFilter
{
    /** Constant for the search text. */
    private static final String SEARCH_TEXT = "TestAlbumSeachText";

    /** The filter to be tested. */
    private AlbumSearchFilter filter;

    @Before
    public void setUp() throws Exception
    {
        filter = new AlbumSearchFilter(SEARCH_TEXT);
    }

    /**
     * Tests accept() for a null entity.
     */
    @Test
    public void testAcceptNullEntity()
    {
        assertFalse("Accepted null entity", filter.accepts(null));
    }

    /**
     * Tests accept() if the expected result is false.
     */
    @Test
    public void testAcceptFalse()
    {
        AlbumEntity album = new AlbumEntity();
        assertFalse("Accepted (1)", filter.accepts(album));
        album.setName("A completely different name!");
        assertFalse("Accepted (2)", filter.accepts(album));
    }

    /**
     * Tests accept() if the expected result is true.
     */
    @Test
    public void testAcceptTrue()
    {
        AlbumEntity album = new AlbumEntity();
        album.setName(SEARCH_TEXT);
        assertTrue("Exact", filter.accepts(album));
        album.setName("Contained" + SEARCH_TEXT);
        assertTrue("Contains", filter.accepts(album));
        album.setName("Contains case "
                + SEARCH_TEXT.toLowerCase(Locale.ENGLISH) + " and some more");
        assertTrue("Case", filter.accepts(album));
    }
}
