package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Comparator;
import java.util.Map;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Hyperlink;

import de.oliver_heger.mediastore.client.ComparatorMapGridTableModel;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.ArtistComparators;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;

/**
 * Test class for {@code ArtistGridTableModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistGridTableModel extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether the grid is correctly initialized.
     */
    public void testInitGrid()
    {
        Grid grid = new Grid();
        ArtistGridTableModel model =
                new ArtistGridTableModel(grid, new MockPageManager());
        assertSame("Wrong grid", grid, model.getGrid());
    }

    /**
     * Tests whether the comparators are correctly initialized.
     */
    public void testInitComparatorMap()
    {
        ArtistGridTableModel model =
                new ArtistGridTableModel(new Grid(), new MockPageManager());
        Map<String, Comparator<ArtistInfo>> map = model.getComparatorMap();
        Map<String, Comparator<ArtistInfo>> expComparators =
                ComparatorMapGridTableModel
                        .comparatorMapForEnum(ArtistComparators.values());
        assertEquals("Wrong number of comparators", expComparators.size(),
                map.size());
        for (Map.Entry<String, Comparator<ArtistInfo>> e : expComparators
                .entrySet())
        {
            assertEquals("Wrong value for " + e.getKey(), e.getValue(),
                    map.get(e.getKey()));
        }
    }

    /**
     * Tests whether an artist name is correctly displayed.
     */
    public void testWriteCellArtistName()
    {
        Grid grid = new Grid(1, 1);
        MockPageManager pm = new MockPageManager();
        ArtistInfo info = new ArtistInfo();
        info.setArtistID(20110203081515L);
        info.setName("Shakira");
        pm.expectCreatePageSpecification(Pages.ARTISTDETAILS, null)
                .withParameter(info.getArtistID()).toToken();
        ArtistGridTableModel model = new ArtistGridTableModel(grid, pm);
        model.writeCell(0, 0, "name", info);
        Hyperlink link = (Hyperlink) grid.getWidget(0, 0);
        assertEquals("Wrong artist name", info.getName(), link.getText());
        assertEquals("Wrong link",
                MockPageManager.defaultToken(Pages.ARTISTDETAILS),
                link.getTargetHistoryToken());
        pm.verify();
    }

    /**
     * Tests the behavior of writeCell() if the property is unknown.
     */
    public void testWriteCellUnknownProperty()
    {
        Grid grid = new Grid(1, 1);
        ArtistGridTableModel model =
                new ArtistGridTableModel(grid, new MockPageManager());
        ArtistInfo info = new ArtistInfo();
        model.writeCell(0, 0, "unknown property?", info);
        assertTrue("Wrong content",
                grid.getText(0, 0).indexOf("Unknown property:") >= 0);
    }
}
