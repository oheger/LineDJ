package de.oliver_heger.mediastore.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.ui.Grid;

import de.oliver_heger.mediastore.client.SortableTableHeader.SortDirection;
import de.oliver_heger.mediastore.shared.model.SongComparators;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * Test class for {@code GridTableModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestGridTableModel extends GWTTestCase
{
    /** An array with the property names for the test columns. */
    private static final String[] PROPERTIES = {
            "songName", "playCount"
    };

    /** Constant for a song name prefix. */
    private static final String SONG_NAME = "You are my number ";

    /** An array with indices of test songs in random order. */
    private static final int[] SONG_INDICES = {
            4, 1, 3, 7, 5, 0, 2, 6
    };

    /** An array with the header components. */
    private SortableTableHeader[] headers;

    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates a list with test songs in the order specified by the indices.
     *
     * @param indices the song indices
     * @return the test songs
     */
    private static List<SongInfo> createTestSongs(int[] indices)
    {
        List<SongInfo> songs = new ArrayList<SongInfo>(indices.length);
        for (int i : indices)
        {
            SongInfo info = new SongInfo();
            info.setName(SONG_NAME + i);
            info.setPlayCount(i);
            songs.add(info);
        }
        return songs;
    }

    /**
     * Creates a list with test songs in random order.
     *
     * @return the list with test songs
     */
    private static List<SongInfo> createRandomTestSongs()
    {
        return createTestSongs(SONG_INDICES);
    }

    /**
     * Creates an ordered list with test songs.
     *
     * @param descending flag for descending order
     * @return the list with test songs
     */
    private static List<SongInfo> createOrderedTestSongs(boolean descending)
    {
        int[] indices = new int[SONG_INDICES.length];
        for (int i = 0; i < indices.length; i++)
        {
            int idx = descending ? indices.length - i - 1 : i;
            indices[i] = idx;
        }
        return createTestSongs(indices);
    }

    /**
     * Tests the content of a grid.
     *
     * @param grid the grid
     * @param infos the list with songs
     */
    private static void checkGrid(Grid grid, List<SongInfo> infos)
    {
        assertEquals("Wrong row count", infos.size() + 1, grid.getRowCount());
        for (int i = 0; i < infos.size(); i++)
        {
            SongInfo song = infos.get(i);
            int row = i + 1;
            assertEquals("Wrong name", song.getName(), grid.getText(row, 0));
            assertEquals("Wrong play count",
                    String.valueOf(song.getPlayCount()), grid.getText(row, 1));
        }
    }

    /**
     * Creates a grid with initialized header objects in the first row.
     *
     * @return the grid
     */
    private Grid setUpGrid()
    {
        headers = new SortableTableHeader[PROPERTIES.length];
        Grid grid = new Grid(1, PROPERTIES.length);
        for (int i = 0; i < PROPERTIES.length; i++)
        {
            SortableTableHeader header = new SortableTableHeader();
            header.setText(PROPERTIES[i]);
            header.setPropertyName(PROPERTIES[i]);
            grid.setWidget(0, i, header);
            headers[i] = header;
        }
        return grid;
    }

    /**
     * Tries to create an instance without a grid.
     */
    public void testInitNull()
    {
        try
        {
            new GridTableModelTestImpl(null);
            fail("Could create instance without a grid!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tries to initialize data with a null list.
     */
    public void testInitDataNull()
    {
        GridTableModelTestImpl model = new GridTableModelTestImpl(setUpGrid());
        try
        {
            model.initData(null);
            fail("Could set a null data list!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tests whether data can be set if no initial sort order is specified.
     */
    public void testInitDataNoInitialSort()
    {
        Grid grid = setUpGrid();
        GridTableModelTestImpl model = new GridTableModelTestImpl(grid);
        List<SongInfo> data = createRandomTestSongs();
        model.initData(data);
        checkGrid(grid, data);
    }

    /**
     * Helper method for checking the initialization of the grid if an initial
     * order is specified.
     *
     * @param descending flag if the order is descending
     */
    private void checkInitDataInitialSort(boolean descending)
    {
        Grid grid = setUpGrid();
        headers[0].setInitiallySorted(true);
        headers[0].setDefaultDescending(descending);
        GridTableModelTestImpl model = new GridTableModelTestImpl(grid);
        model.initData(createRandomTestSongs());
        List<SongInfo> songs = createOrderedTestSongs(descending);
        checkGrid(grid, songs);
        for (SortableTableHeader header : headers)
        {
            assertSame("Wrong table header listener", model,
                    header.getTableHeaderListener());
        }
    }

    /**
     * Tests initData() if an initial, ascending order is specified.
     */
    public void testInitDataInitialSortAscending()
    {
        checkInitDataInitialSort(false);
    }

    /**
     * Tests initData() if an initial, descending order is specified.
     */
    public void testInitDataInitialSortDescending()
    {
        checkInitDataInitialSort(true);
    }

    /**
     * Tests onSortableTableHeaderClick() if no data has been set. Normally this
     * cannot happen.
     */
    public void testOnSortableTableHeaderClickNoData()
    {
        GridTableModelTestImpl model = new GridTableModelTestImpl(setUpGrid());
        try
        {
            model.onSortableTableHeaderClick(headers[0]);
            fail("Missing data not detected!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * Tests onSortableTableHeaderClick() if no comparator is available for the
     * new column.
     */
    public void testOnSortableTableHeaderClickNoComparator()
    {
        Grid grid = setUpGrid();
        List<SongInfo> songs = createRandomTestSongs();
        headers[1].setPropertyName("unknown Property");
        GridTableModelTestImpl model = new GridTableModelTestImpl(grid);
        model.initData(songs);
        model.onSortableTableHeaderClick(headers[1]);
        checkGrid(grid, songs);
    }

    /**
     * Tests onSortableTableHeaderClick() if the data has to be resorted.
     */
    public void testOnSortableTableHeaderClickNewSort()
    {
        Grid grid = setUpGrid();
        headers[0].setSortDirection(SortDirection.SORT_ASCENDING);
        headers[1].setSortDirection(SortDirection.SORT_DESCENDING);
        GridTableModelTestImpl model = new GridTableModelTestImpl(grid);
        model.initData(createRandomTestSongs());
        model.onSortableTableHeaderClick(headers[0]);
        checkGrid(grid, createOrderedTestSongs(false));
        assertEquals("Wrong direction 1", SortDirection.SORT_ASCENDING,
                headers[0].getSortDirection());
        assertEquals("Wrong direction 2", SortDirection.SORT_NONE,
                headers[1].getSortDirection());
    }

    /**
     * A test implementation of the grid table model.
     */
    private static class GridTableModelTestImpl extends
            GridTableModel<SongInfo>
    {
        public GridTableModelTestImpl(Grid wrappedGrid)
        {
            super(wrappedGrid);
        }

        @Override
        protected void writeCell(int row, int col, SongInfo obj)
        {
            getGrid().setText(
                    row,
                    col,
                    (col == 0) ? obj.getName() : String.valueOf(obj
                            .getPlayCount()));
        }

        @Override
        protected Comparator<SongInfo> fetchComparator(String property)
        {
            if (PROPERTIES[0].equals(property))
            {
                return SongComparators.NAME_COMPARATOR;
            }
            if (PROPERTIES[1].equals(property))
            {
                return SongComparators.PLAYCOUNT_COMPARATOR;
            }
            return null;
        }
    }
}
