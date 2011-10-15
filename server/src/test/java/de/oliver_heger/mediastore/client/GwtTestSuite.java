package de.oliver_heger.mediastore.client;

import junit.framework.Test;
import junit.framework.TestCase;

import com.google.gwt.junit.tools.GWTTestSuite;

import de.oliver_heger.mediastore.client.pageman.impl.DockLayoutPageViewTestGwt;
import de.oliver_heger.mediastore.client.pageman.impl.PageManagerImplTestGwt;
import de.oliver_heger.mediastore.client.pages.PagesTestGwt;
import de.oliver_heger.mediastore.client.pages.detail.AlbumDetailsPageTestGwt;
import de.oliver_heger.mediastore.client.pages.detail.AlbumGridTableModelTestGwt;
import de.oliver_heger.mediastore.client.pages.detail.ArtistDetailsPageTestGwt;
import de.oliver_heger.mediastore.client.pages.detail.ArtistGridTableModelTestGwt;
import de.oliver_heger.mediastore.client.pages.detail.SongDetailsPageTestGwt;
import de.oliver_heger.mediastore.client.pages.detail.SongGridTableModelTestGwt;
import de.oliver_heger.mediastore.client.pages.detail.SynonymEditorTestGwt;
import de.oliver_heger.mediastore.client.pages.overview.ArtistOverviewPageTestGwt;
import de.oliver_heger.mediastore.client.pages.overview.OpenPageSingleElementHandlerTestGwt;
import de.oliver_heger.mediastore.client.pages.overview.OverviewPageTestGwt;
import de.oliver_heger.mediastore.client.pages.overview.OverviewTableTestGwt;
import de.oliver_heger.mediastore.client.pages.overview.RemoveControllerDlgTestGwt;

/**
 * <p>The main test suite class for all GWT tests.</p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestSuite extends TestCase
{
    public static Test suite()
    {
        GWTTestSuite suite = new GWTTestSuite("Main Test suite of RemoteMediaStore project");

        suite.addTestSuite(DisplayErrorPanelTestGwt.class);
        suite.addTestSuite(ComparatorMapGridTableModelTestGwt.class);
        suite.addTestSuite(GridTableModelTestGwt.class);
        suite.addTestSuite(I18NFormatterTestGwt.class);
        suite.addTestSuite(SortableTableHeaderTestGwt.class);
        suite.addTestSuite(DockLayoutPageViewTestGwt.class);
        suite.addTestSuite(PageManagerImplTestGwt.class);
        suite.addTestSuite(PagesTestGwt.class);
        suite.addTestSuite(AlbumDetailsPageTestGwt.class);
        suite.addTestSuite(AlbumGridTableModelTestGwt.class);
        suite.addTestSuite(ArtistDetailsPageTestGwt.class);
        suite.addTestSuite(ArtistGridTableModelTestGwt.class);
        suite.addTestSuite(SongDetailsPageTestGwt.class);
        suite.addTestSuite(SongGridTableModelTestGwt.class);
        suite.addTestSuite(SynonymEditorTestGwt.class);
        suite.addTestSuite(ArtistOverviewPageTestGwt.class);
        suite.addTestSuite(OpenPageSingleElementHandlerTestGwt.class);
        suite.addTestSuite(OverviewPageTestGwt.class);
        suite.addTestSuite(OverviewTableTestGwt.class);
        suite.addTestSuite(RemoveControllerDlgTestGwt.class);

        return suite;
    }
}
