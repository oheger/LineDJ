package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Comparator;
import java.util.Map;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Hyperlink;

import de.oliver_heger.mediastore.client.ComparatorMapGridTableModel;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.AlbumComparators;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;

/**
 * Test class for {@code AlbumGridTableModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumGridTableModelTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether the correct grid is passed to the super class.
     */
    public void testInitGrid()
    {
        Grid grid = new Grid();
        AlbumGridTableModel model =
                new AlbumGridTableModel(grid, new MockPageManager());
        assertSame("Wrong grid", grid, model.getGrid());
    }

    /**
     * Tests whether the map with comparators is correctly initialized.
     */
    public void testInitComparatorMap()
    {
        AlbumGridTableModel model =
                new AlbumGridTableModel(new Grid(), new MockPageManager());
        Map<String, Comparator<AlbumInfo>> comparatorMap =
                model.getComparatorMap();
        Map<String, Comparator<AlbumInfo>> expMap =
                ComparatorMapGridTableModel
                        .comparatorMapForEnum(AlbumComparators.values());
        assertEquals("Wrong number of comparators", expMap.size(),
                comparatorMap.size());
        for (Map.Entry<String, Comparator<AlbumInfo>> e : expMap.entrySet())
        {
            assertEquals("Wrong comparator for " + e.getKey(), e.getValue(),
                    comparatorMap.get(e.getKey()));
        }
    }

    /**
     * Tests whether a cell for an album name is correctly written.
     */
    public void testWriteCellSongName()
    {
        Grid grid = new Grid(1, 1);
        MockPageManager pm = new MockPageManager();
        AlbumGridTableModel model = new AlbumGridTableModel(grid, pm);
        AlbumInfo ai = new AlbumInfo();
        ai.setAlbumID(20110129221157L);
        ai.setName("Fugazzi");
        pm.expectCreatePageSpecification(Pages.ALBUMDETAILS, null)
                .withParameter(ai.getAlbumID()).toToken();
        model.writeCell(0, 0, "name", ai);
        Hyperlink link = (Hyperlink) grid.getWidget(0, 0);
        assertEquals("Wrong link text", ai.getName(), link.getText());
        assertEquals("Wrong target",
                MockPageManager.defaultToken(Pages.ALBUMDETAILS),
                link.getTargetHistoryToken());
        pm.verify();
    }

    /**
     * Tests the behavior of writeCell() if the property is unknown.
     */
    public void testWriteCellUnknownProperty()
    {
        Grid grid = new Grid(1, 1);
        AlbumGridTableModel model =
                new AlbumGridTableModel(grid, new MockPageManager());
        AlbumInfo ai = new AlbumInfo();
        model.writeCell(0, 0, "unknown property?", ai);
        assertTrue("Wrong content",
                grid.getText(0, 0).indexOf("Unknown property:") >= 0);
    }
}
