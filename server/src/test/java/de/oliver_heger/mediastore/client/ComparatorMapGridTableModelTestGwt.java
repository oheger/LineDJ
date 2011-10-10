package de.oliver_heger.mediastore.client;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.Grid;

import de.oliver_heger.mediastore.shared.model.SongComparators;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * Test class for {@code ComparatorMapGridTableModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ComparatorMapGridTableModelTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Checks whether the specified map contains all comparators for songs.
     *
     * @param map the map to be checked
     */
    private void checkSongComparatorsMap(Map<String, Comparator<SongInfo>> map)
    {
        assertEquals("Wrong number of map entries",
                SongComparators.values().length, map.size());
        for (SongComparators c : SongComparators.values())
        {
            assertEquals("Wrong entry for " + c, c, map.get(c.name()));
        }
    }

    /**
     * Tests whether enumeration constants can be transformed into a comparator
     * map.
     */
    public void testComparatorMapForEnum()
    {
        Map<String, Comparator<SongInfo>> map =
                ComparatorMapGridTableModel
                        .comparatorMapForEnum(SongComparators.values());
        checkSongComparatorsMap(map);
    }

    /**
     * Tests whether an instance can be created correctly.
     */
    public void testInit()
    {
        Grid grid = new Grid();
        ComparatorMapGridTableModelTestImpl model =
                new ComparatorMapGridTableModelTestImpl(grid,
                        ComparatorMapGridTableModel
                                .comparatorMapForEnum(SongComparators.values()));
        assertSame("Wrong grid", grid, model.getGrid());
        checkSongComparatorsMap(model.getComparatorMap());
    }

    /**
     * Tests that a defensive copy of the comparator map is created.
     */
    public void testInitMapModify()
    {
        Map<String, Comparator<SongInfo>> map =
                new HashMap<String, Comparator<SongInfo>>();
        map.put(SongComparators.NAME_COMPARATOR.name(),
                SongComparators.NAME_COMPARATOR);
        ComparatorMapGridTableModelTestImpl model =
                new ComparatorMapGridTableModelTestImpl(new Grid(), map);
        map.put(SongComparators.DURATION_COMPARATOR.name(),
                SongComparators.DURATION_COMPARATOR);
        Map<String, Comparator<SongInfo>> compMap = model.getComparatorMap();
        assertEquals("Wrong map size", 1, compMap.size());
        assertEquals("Wrong comparator", SongComparators.NAME_COMPARATOR,
                compMap.get(SongComparators.NAME_COMPARATOR.name()));
    }

    /**
     * Tests whether a null map passed to the constructor is handled correctly.
     */
    public void testInitMapNull()
    {
        ComparatorMapGridTableModelTestImpl model =
                new ComparatorMapGridTableModelTestImpl(new Grid(), null);
        assertTrue("Wrong map", model.getComparatorMap().isEmpty());
    }

    /**
     * Tests that the map with comparators cannot be modified.
     */
    public void testGetComparatorMapModify()
    {
        ComparatorMapGridTableModelTestImpl model =
                new ComparatorMapGridTableModelTestImpl(new Grid(),
                        ComparatorMapGridTableModel
                                .comparatorMapForEnum(SongComparators.values()));
        Map<String, Comparator<SongInfo>> compMap = model.getComparatorMap();
        try
        {
            compMap.clear();
            fail("Could modify map!");
        }
        catch (UnsupportedOperationException uex)
        {
            // ok
        }
    }

    /**
     * Tests whether the correct comparators are fetched for properties.
     */
    public void testFetchComparator()
    {
        Map<String, Comparator<SongInfo>> map =
                ComparatorMapGridTableModel
                        .comparatorMapForEnum(SongComparators.values());
        ComparatorMapGridTableModelTestImpl model =
                new ComparatorMapGridTableModelTestImpl(new Grid(), map);
        for (Map.Entry<String, Comparator<SongInfo>> e : map.entrySet())
        {
            assertEquals("Wrong comparator for " + e.getKey(), e.getValue(),
                    model.fetchComparator(e.getKey()));
        }
        assertNull("Wrong result for unknown property",
                model.fetchComparator("unknown property!"));
    }

    /**
     * Tests whether a comparator can be found whose name has to be derived from
     * the property name.
     */
    public void testFetchComparatorTransformName()
    {
        ComparatorMapGridTableModelTestImpl model =
                new ComparatorMapGridTableModelTestImpl(new Grid(),
                        ComparatorMapGridTableModel
                                .comparatorMapForEnum(SongComparators.values()));
        assertEquals("Wrong name comparator", SongComparators.NAME_COMPARATOR,
                model.fetchComparator("name"));
        assertEquals("Wrong play count comparator",
                SongComparators.PLAYCOUNT_COMPARATOR,
                model.fetchComparator("playCount"));
    }

    /**
     * Tries to obtain a comparator for a null property.
     */
    public void testFetchComparatorNullProperty()
    {
        ComparatorMapGridTableModelTestImpl model =
                new ComparatorMapGridTableModelTestImpl(new Grid(),
                        ComparatorMapGridTableModel
                                .comparatorMapForEnum(SongComparators.values()));
        assertNull("Got a comparator", model.fetchComparator(null));
    }

    /**
     * A test implementation of the abstract class under test.
     */
    private static class ComparatorMapGridTableModelTestImpl extends
            ComparatorMapGridTableModel<SongInfo>
    {
        public ComparatorMapGridTableModelTestImpl(Grid grid,
                Map<String, Comparator<SongInfo>> compMap)
        {
            super(grid, compMap);
        }

        @Override
        protected void writeCell(int row, int col, String property, SongInfo obj)
        {
        }
    }
}
