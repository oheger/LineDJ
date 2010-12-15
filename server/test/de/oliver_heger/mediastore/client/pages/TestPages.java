package de.oliver_heger.mediastore.client.pages;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.DockLayoutPanel;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pageman.impl.DockLayoutPageView;
import de.oliver_heger.mediastore.client.pageman.impl.PageManagerImpl;
import de.oliver_heger.mediastore.client.pages.detail.ArtistDetailsPage;
import de.oliver_heger.mediastore.client.pages.overview.OverviewPage;

/**
 * Test class for the {@code Pages} enumeration. We test here whether all pages
 * can be created.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPages extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates a test instance of page manager.
     *
     * @return the test page manager
     */
    private static PageManager createPageManager()
    {
        return new PageManagerImpl(new DockLayoutPageView(new DockLayoutPanel(
                Unit.CM)));
    }

    /**
     * Tests the creation of the overview page.
     */
    public void testPageOverview()
    {
        PageManager pm = createPageManager();
        OverviewPage page = (OverviewPage) Pages.OVERVIEW.getPageWidget(pm);
        assertSame("Wrong page manager", pm, page.getPageManager());
    }

    /**
     * Tests the creation of the artist details page.
     */
    public void testPageArtistDetails()
    {
        PageManager pm = createPageManager();
        ArtistDetailsPage page =
                (ArtistDetailsPage) Pages.ARTISTDETAILS.getPageWidget(pm);
        assertSame("Wrong page manager", pm, page.getPageManager());
    }
}
