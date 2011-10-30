package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Date;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * Test class for {@code SongOverviewPage}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongOverviewPageTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests the key provider for songs.
     */
    public void testInitKeyProvider()
    {
        SongOverviewPage page = new SongOverviewPage();
        SongInfo song = new SongInfo();
        song.setSongID(String.valueOf(20111029205938L));
        ProvidesKey<SongInfo> provider = page.cellTable.getKeyProvider();
        assertEquals("Wrong ID", song.getSongID(), provider.getKey(song));
    }

    /**
     * Tests the query handler set by the page.
     */
    public void testInitQueryHandler()
    {
        SongOverviewPage page = new SongOverviewPage();
        OverviewQueryHandler<SongInfo> handler = page.getQueryHandler();
        assertTrue("Wrong query handler",
                handler instanceof SongOverviewQueryHandler);
    }

    /**
     * Tests whether the columns of the cell table are initialized.
     */
    public void testInitCellTableColumns()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        page.initCellTableColumns(page.cellTable);
        assertTrue("Too few columns", page.cellTable.getColumnCount() > 2);
    }

    /**
     * Tests the column for the song name.
     */
    public void testCreateNameColumn()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createNameColumn();
        assertTrue("Not a link column", col instanceof LinkColumn<?>);
        SongInfo song = new SongInfo();
        song.setSongID(String.valueOf(20111029211734L));
        song.setName("Hotel California");
        assertEquals("Wrong column value", song.getName(), col.getValue(song));
        LinkColumn<SongInfo> linkCol = (LinkColumn<SongInfo>) col;
        assertEquals("Wrong ID", song.getSongID(), linkCol.getID(song));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests the column for the artist.
     */
    public void testCreateArtistColumn()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createArtistColumn();
        SongInfo song = new SongInfo();
        song.setArtistID(20111029214144L);
        song.setArtistName("Nightwish");
        assertTrue("Not a link column", col instanceof LinkColumn<?>);
        assertEquals("Wrong artist name", song.getArtistName(),
                col.getValue(song));
        LinkColumn<SongInfo> linkCol = (LinkColumn<SongInfo>) col;
        assertEquals("Wrong artist ID", song.getArtistID(), linkCol.getID(song));
    }

    /**
     * Tests the column for the album.
     */
    public void testCreateAlbumColumn()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createAlbumColumn();
        SongInfo song = new SongInfo();
        song.setAlbumID(20111029214710L);
        song.setAlbumName("Emergency on planet earth");
        assertTrue("Not a link column", col instanceof LinkColumn<?>);
        assertEquals("Wrong album name", song.getAlbumName(),
                col.getValue(song));
        LinkColumn<SongInfo> linkCol = (LinkColumn<SongInfo>) col;
        assertEquals("Wrong album ID", song.getAlbumID(), linkCol.getID(song));
    }

    /**
     * Tests the column for the song duration.
     */
    public void testCreateDurationColumn()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createDurationColumn();
        SongInfo song = new SongInfo();
        song.setDuration(47587L);
        assertEquals("Wrong column value", song.getFormattedDuration(),
                col.getValue(song));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests the column for the inception year.
     */
    public void testCreateYearColumn()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createYearColumn();
        SongInfo song = new SongInfo();
        song.setInceptionYear(1986);
        assertEquals("Wrong inception year",
                String.valueOf(song.getInceptionYear()), col.getValue(song));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests the year column if the value is undefined.
     */
    public void testCreateYearColumnUndefined()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createYearColumn();
        SongInfo info = new SongInfo();
        assertEquals("Wrong column value", "", col.getValue(info));
    }

    /**
     * Tests the column for the track number.
     */
    public void testCreateTrackColumn()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createTrackColumn();
        SongInfo song = new SongInfo();
        song.setTrackNo(5);
        assertEquals("Wrong column value", String.valueOf(song.getTrackNo()),
                col.getValue(song));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests the column for the play count.
     */
    public void testCreatePlayCountColumn()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createPlayCountColumn();
        SongInfo song = new SongInfo();
        song.setPlayCount(11);
        assertEquals("Wrong column value", String.valueOf(song.getPlayCount()),
                col.getValue(song));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests the column for the creation date.
     */
    public void testCreateDateColumn()
    {
        SongOverviewPage page = new SongOverviewPage();
        Column<SongInfo, String> col = page.createDateColumn();
        SongInfo song = new SongInfo();
        song.setCreationDate(new Date(20111029221451L));
        assertEquals("Wrong column value",
                page.getFormatter().formatDate(song.getCreationDate()),
                col.getValue(song));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests whether the columns of the table are correctly initialized.
     */
    public void testInitTableColumns()
    {
        SongOverviewPage page = new SongOverviewPage();
        page.initialize(new MockPageManager());
        assertTrue("Too few columns", page.cellTable.getColumnCount() > 5);
    }

    /**
     * Tests whether a handler for removing elements has been installed.
     */
    public void testRemoveElementHandler()
    {
        SongOverviewPage page = new SongOverviewPage();
        page.initialize(new MockPageManager());
        RemoveElementHandler handler = null;
        for (MultiElementHandler meh : page.getMultiElementHandlers())
        {
            if (meh instanceof RemoveElementHandler)
            {
                assertNull("Got multiple remove handlers", handler);
                handler = (RemoveElementHandler) meh;
            }
        }
        assertNotNull("No remove handler found", handler);
        assertEquals("Wrong control", page, handler.getInitiatingControl());
        assertSame("Wrong remove controller", page.getRemoveController(),
                handler.getRemoveController());
        assertNotNull("No remove controller", handler.getRemoveController());
        assertEquals("Wrong service handler class", page
                .createRemoveSongHandler().getClass(), handler
                .getServiceHandler().getClass());
    }

    /**
     * Tests whether the service handler for removing songs works correctly.
     */
    public void testRemoveAlbumServiceHandler()
    {
        SongOverviewPage page = new SongOverviewPage();
        RemoveServiceHandler handler = page.createRemoveSongHandler();
        final String songID = String.valueOf(20111029225948L);
        RemoveMediaServiceMock service = new RemoveMediaServiceMock();
        handler.removeElement(service, songID,
                RemoveMediaServiceMock.getRemoveCallback());
        assertEquals("Wrong song ID", songID, service.getRemoveSongID());
    }
}
