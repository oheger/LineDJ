package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.junit.client.GWTTestCase;

/**
 * Test class for {@code ArtistDetailsPage}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistDetailsPage extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether a page can be created correctly.
     */
    public void testInit()
    {
        ArtistDetailsPage page = new ArtistDetailsPage();
        assertNotNull("No name span", page.spanArtistName);
        assertNotNull("No creation date span", page.spanCreationDate);
        assertNotNull("No synonyms span", page.spanSynonyms);
    }
}
