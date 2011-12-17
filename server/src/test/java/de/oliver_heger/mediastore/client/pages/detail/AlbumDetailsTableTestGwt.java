package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.LinkColumn;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;

/**
 * Test class for {@code AlbumDetailsTable}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumDetailsTableTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates a test instance of the album table.
     *
     * @return the test instance
     */
    private AlbumDetailsTable createInitializedTable()
    {
        MockPageManager pm = new MockPageManager();
        AlbumDetailsTable table = new AlbumDetailsTable();
        table.initialize(pm);
        return table;
    }

    /**
     * Creates a test album info object.
     *
     * @return the info object
     */
    private static AlbumInfo createAlbumInfo()
    {
        AlbumInfo info = new AlbumInfo();
        info.setName("Full Moon, Dirty Hearts");
        info.setAlbumID(20111217213938L);
        info.setDuration(60 * 60L);
        info.setInceptionYear(1993);
        info.setNumberOfSongs(10);
        return info;
    }

    /**
     * Tests the key provider.
     */
    public void testKeyProvider()
    {
        AlbumDetailsTable table = createInitializedTable();
        ProvidesKey<AlbumInfo> keyProvider = table.cellTable.getKeyProvider();
        AlbumInfo info = createAlbumInfo();
        assertEquals("Wrong key", info.getAlbumID(), keyProvider.getKey(info));
    }

    /**
     * Tests whether the album name column has been set correctly.
     */
    public void testNameColumn()
    {
        AlbumDetailsTable table = createInitializedTable();
        Column<AlbumInfo, ?> col = table.cellTable.getColumn(0);
        AlbumInfo info = createAlbumInfo();
        assertEquals("Wrong value", info.getName(), col.getValue(info));
        @SuppressWarnings("unchecked")
        LinkColumn<AlbumInfo> linkCol = (LinkColumn<AlbumInfo>) col;
        assertEquals("Wrong ID", info.getAlbumID(), linkCol.getID(info));
        assertEquals("Wrong target page", Pages.ALBUMDETAILS, linkCol.getPage());
    }

    /**
     * Tests whether the column for the duration is added correctly.
     */
    public void testDurationColumn()
    {
        AlbumDetailsTable table = createInitializedTable();
        Column<AlbumInfo, ?> col = table.cellTable.getColumn(1);
        AlbumInfo info = createAlbumInfo();
        assertEquals("Wrong duration", info.getFormattedDuration(),
                col.getValue(info));
    }

    /**
     * Tests whether the column for the number of songs is added correctly.
     */
    public void testNumberOfSongsColumn()
    {
        AlbumDetailsTable table = createInitializedTable();
        Column<AlbumInfo, ?> col = table.cellTable.getColumn(2);
        AlbumInfo info = createAlbumInfo();
        assertEquals("Wrong number of songs",
                String.valueOf(info.getNumberOfSongs()), col.getValue(info));
    }

    /**
     * Tests whether the column for the inception year is added correctly.
     */
    public void testInceptionYearColumn()
    {
        AlbumDetailsTable table = createInitializedTable();
        Column<AlbumInfo, ?> col = table.cellTable.getColumn(3);
        AlbumInfo info = createAlbumInfo();
        assertEquals("Wrong inception year",
                String.valueOf(info.getInceptionYear()), col.getValue(info));
        info.setInceptionYear(null);
        assertEquals("Wrong null inception year", "", col.getValue(info));
    }
}
