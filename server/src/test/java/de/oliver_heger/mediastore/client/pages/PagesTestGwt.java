package de.oliver_heger.mediastore.client.pages;

import com.google.gwt.junit.client.GWTTestCase;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.detail.AlbumDetailsPage;
import de.oliver_heger.mediastore.client.pages.detail.ArtistDetailsPage;
import de.oliver_heger.mediastore.client.pages.detail.SongDetailsPage;
import de.oliver_heger.mediastore.client.pages.overview.OverviewPage;

/**
 * Test class for the {@code Pages} enumeration. We test here whether all pages
 * can be created.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PagesTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests the creation of the overview page.
     */
    public void testPageOverview()
    {
        PageManager pm = new MockPageManager();
        OverviewPage page = (OverviewPage) Pages.OVERVIEW.getPageWidget(pm);
        assertSame("Wrong page manager", pm, page.getPageManager());
    }

    /**
     * Creates a mock page manager for initializing a details page. When a
     * details page is initialized the page manager is used to construct some
     * tokens for links. This method prepares the mock page manager
     * correspondingly.
     *
     * @return the mock page manager
     */
    private MockPageManager createPMForDetailPage()
    {
        MockPageManager pm = new MockPageManager();
        pm.expectCreatePageSpecification(Pages.OVERVIEW, null).toToken();
        return pm;
    }

    /**
     * Tests the creation of the artist details page.
     */
    public void testPageArtistDetails()
    {
        PageManager pm = createPMForDetailPage();
        ArtistDetailsPage page =
                (ArtistDetailsPage) Pages.ARTISTDETAILS.getPageWidget(pm);
        assertSame("Wrong page manager", pm, page.getPageManager());
    }

    /**
     * Tests the creation of the song details page.
     */
    public void testPageSongDetails()
    {
        PageManager pm = createPMForDetailPage();
        SongDetailsPage page =
                (SongDetailsPage) Pages.SONGDETAILS.getPageWidget(pm);
        assertSame("Wrong page manager", pm, page.getPageManager());
    }

    /**
     * Tests the creation of the album details page.
     */
    public void testPageAlbumDetails()
    {
        MockPageManager pm = createPMForDetailPage();
        AlbumDetailsPage page =
                (AlbumDetailsPage) Pages.ALBUMDETAILS.getPageWidget(pm);
        assertSame("Wrong page manager", pm, page.getPageManager());
        pm.verify();
    }
}
