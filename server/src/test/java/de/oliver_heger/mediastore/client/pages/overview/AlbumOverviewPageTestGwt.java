package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Date;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.LinkColumn;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;

/**
 * Test class for {@code AlbumOverviewPage}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumOverviewPageTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether a correct key provider has been set.
     */
    public void testInitKeyProvider()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        ProvidesKey<AlbumInfo> keyProvider = page.cellTable.getKeyProvider();
        checkKeyProvider(keyProvider);
    }

    /**
     * Tests the key given provider for album objects.
     *
     * @param keyProvider the provider to check
     */
    private void checkKeyProvider(ProvidesKey<AlbumInfo> keyProvider)
    {
        AlbumInfo info = new AlbumInfo();
        info.setAlbumID(20111025205815L);
        assertEquals("Wrong key", info.getAlbumID(), keyProvider.getKey(info));
    }

    /**
     * Tests whether the correct query handler has been set.
     */
    public void testInitQueryHandler()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        assertTrue("Wrong query handler",
                page.getQueryHandler() instanceof AlbumOverviewQueryHandler);
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
     * Tests the column for the album name.
     */
    public void testCreateNameColumn()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        Column<AlbumInfo, String> col = page.createNameColumn();
        assertTrue("No LinkColumn: " + col, col instanceof LinkColumn);
        AlbumInfo album = new AlbumInfo();
        album.setAlbumID(20111025211341L);
        album.setName("Blow it out of your ass");
        LinkColumn<AlbumInfo> linkCol = (LinkColumn<AlbumInfo>) col;
        assertEquals("Wrong column value", album.getName(),
                linkCol.getValue(album));
        assertEquals("Wrong ID", album.getAlbumID(), linkCol.getID(album));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests the column for the number of songs.
     */
    public void testCreateSongCountColumn()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        Column<AlbumInfo, String> col = page.createSongCountColumn();
        AlbumInfo album = new AlbumInfo();
        album.setNumberOfSongs(42);
        assertEquals("Wrong column value",
                String.valueOf(album.getNumberOfSongs()), col.getValue(album));
    }

    /**
     * Tests the column for the album duration.
     */
    public void testCreateDurationColumn()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        Column<AlbumInfo, String> col = page.createDurationColumn();
        AlbumInfo album = new AlbumInfo();
        album.setDuration(60 * 60 + 30L);
        assertEquals("Wrong column value", album.getFormattedDuration(),
                col.getValue(album));
    }

    /**
     * Tests the column for the album inception year.
     */
    public void testCreateYearColumn()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        Column<AlbumInfo, String> col = page.createYearColumn();
        AlbumInfo album = new AlbumInfo();
        album.setInceptionYear(1973);
        assertEquals("Wrong column value", "1973", col.getValue(album));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests whether the column for years can handle undefined years.
     */
    public void testCreateYearColumnUndefined()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        Column<AlbumInfo, String> col = page.createYearColumn();
        AlbumInfo album = new AlbumInfo();
        assertEquals("Wrong column value", "", col.getValue(album));
    }

    /**
     * Tests the column for the creation date.
     */
    public void testCreateDateColumn()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        Column<AlbumInfo, String> col = page.createDateColumn();
        AlbumInfo info = new AlbumInfo();
        info.setCreationDate(new Date(20111028214211L));
        assertEquals("Wrong column value",
                page.getFormatter().formatDate(info.getCreationDate()),
                col.getValue(info));
        assertTrue("Not sortable", col.isSortable());
    }

    /**
     * Tests whether a handler for removing elements has been installed.
     */
    public void testRemoveElementHandler()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
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
                .createRemoveAlbumHandler().getClass(), handler
                .getServiceHandler().getClass());
    }

    /**
     * Tests whether the service handler for removing albums works correctly.
     */
    public void testRemoveAlbumServiceHandler()
    {
        AlbumOverviewPage page = new AlbumOverviewPage();
        RemoveServiceHandler handler = page.createRemoveAlbumHandler();
        final Long albumID = 20111028215235L;
        RemoveMediaServiceMock service = new RemoveMediaServiceMock();
        handler.removeElement(service, albumID,
                RemoveMediaServiceMock.getRemoveCallback());
        assertEquals("Wrong album ID", albumID, service.getRemoveAlbumID());
    }
}
