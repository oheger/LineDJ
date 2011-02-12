package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Comparator;
import java.util.Map;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Hyperlink;

import de.oliver_heger.mediastore.client.ComparatorMapGridTableModel;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.SongComparators;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * Test class for {@code SongGridTableModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestSongGridTableModel extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether the map with comparators has been correctly initialized.
     */
    public void testInitComparatorMap()
    {
        SongGridTableModel model =
                new SongGridTableModel(new Grid(), new MockPageManager());
        Map<String, Comparator<SongInfo>> map =
                ComparatorMapGridTableModel
                        .comparatorMapForEnum(SongComparators.values());
        Map<String, Comparator<SongInfo>> compMap = model.getComparatorMap();
        assertEquals("Wrong number of comparators", map.size(), compMap.size());
        for (Map.Entry<String, Comparator<SongInfo>> e : map.entrySet())
        {
            assertEquals("Wrong comparator for " + e.getKey(), e.getValue(),
                    compMap.get(e.getKey()));
        }
    }

    /**
     * Tests whether the grid is correctly initialized.
     */
    public void testInitGrid()
    {
        Grid grid = new Grid();
        SongGridTableModel model =
                new SongGridTableModel(grid, new MockPageManager());
        assertSame("Wrong grid", grid, model.getGrid());
    }

    /**
     * Tests whether a cell for a song name is correctly written.
     */
    public void testWriteCellSongName()
    {
        Grid grid = new Grid(1, 1);
        MockPageManager pm = new MockPageManager();
        SongGridTableModel model = new SongGridTableModel(grid, pm);
        SongInfo si = new SongInfo();
        si.setSongID(String.valueOf(20110116192028L));
        si.setName("Thunderstrike");
        pm.expectCreatePageSpecification(Pages.SONGDETAILS, null)
                .withParameter(si.getSongID()).toToken();
        model.writeCell(0, 0, "name", si);
        Hyperlink link = (Hyperlink) grid.getWidget(0, 0);
        assertEquals("Wrong link text", si.getName(), link.getText());
        assertEquals("Wrong target",
                MockPageManager.defaultToken(Pages.SONGDETAILS),
                link.getTargetHistoryToken());
        pm.verify();
    }

    /**
     * Tests whether a cell for a song duration is correctly written.
     */
    public void testWriteCellSongDuration()
    {
        Grid grid = new Grid(1, 1);
        SongGridTableModel model =
                new SongGridTableModel(grid, new MockPageManager());
        SongInfo si = new SongInfo();
        si.setDuration((5 * 60 + 35) * 1000L);
        model.writeCell(0, 0, "duration", si);
        assertEquals("Wrong cell content", si.getFormattedDuration(),
                grid.getText(0, 0));
    }

    /**
     * Tests whether a cell for a song play count is correctly written.
     */
    public void testWriteCellSongPlayCount()
    {
        Grid grid = new Grid(1, 1);
        SongGridTableModel model =
                new SongGridTableModel(grid, new MockPageManager());
        SongInfo si = new SongInfo();
        si.setPlayCount(1);
        model.writeCell(0, 0, "playCount", si);
        assertEquals("Wrong cell content", "1", grid.getText(0, 0));
    }

    /**
     * Tests the behavior of writeCell() if the property is unknown.
     */
    public void testWriteCellUnknownProperty()
    {
        Grid grid = new Grid(1, 1);
        SongGridTableModel model =
                new SongGridTableModel(grid, new MockPageManager());
        SongInfo si = new SongInfo();
        model.writeCell(0, 0, "unknown property?", si);
        assertTrue("Wrong content",
                grid.getText(0, 0).indexOf("Unknown property:") >= 0);
    }
}
