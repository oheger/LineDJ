package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.LinkColumn;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.SongComparators;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * Test class for {@code SongDetailsTable}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongDetailsTableTestGwt extends AbstractTestDetailsTable
{
    /**
     * Creates a song info object with test data.
     *
     * @return the song info object
     */
    private static SongInfo createSongInfo()
    {
        SongInfo info = new SongInfo();
        info.setSongID("20111220214709");
        info.setDuration(20111220214736L);
        info.setPlayCount(12);
        info.setName("Smoke on the Water");
        return info;
    }

    /**
     * Tests whether the key provider is set.
     */
    public void testKeyProvider()
    {
        SongDetailsTable table = new SongDetailsTable();
        ProvidesKey<SongInfo> keyProvider = table.cellTable.getKeyProvider();
        SongInfo info = createSongInfo();
        assertEquals("Wrong song ID", info.getSongID(),
                keyProvider.getKey(info));
    }

    /**
     * Helper method for creating a table and initializing it with a mock page
     * manager.
     *
     * @return the new table
     */
    private static SongDetailsTable createInitializedTable()
    {
        SongDetailsTable table = new SongDetailsTable();
        table.initialize(new MockPageManager());
        return table;
    }

    /**
     * Prepares a test for a column. Creates a test instance of the table and
     * obtains the column with the given index.
     *
     * @param idx the index of the desired column
     * @return the column
     */
    private static Column<SongInfo, ?> prepareColumnTest(int idx)
    {
        SongDetailsTable table = createInitializedTable();
        Column<SongInfo, ?> colName = table.cellTable.getColumn(idx);
        return colName;
    }

    /**
     * Tests the column for the song name.
     */
    public void testNameColumn()
    {
        Column<SongInfo, ?> colName = prepareColumnTest(0);
        SongInfo info = createSongInfo();
        assertEquals("Wrong name", info.getName(), colName.getValue(info));
        @SuppressWarnings("unchecked")
        LinkColumn<SongInfo> lnkCol = (LinkColumn<SongInfo>) colName;
        assertEquals("Wrong ID", info.getSongID(), lnkCol.getID(info));
        assertEquals("Wrong target page", Pages.SONGDETAILS, lnkCol.getPage());
    }

    /**
     * Tests the column for the duration.
     */
    public void testDurationColumn()
    {
        Column<SongInfo, ?> colDur = prepareColumnTest(1);
        SongInfo info = createSongInfo();
        assertEquals("Wrong duration", info.getFormattedDuration(),
                colDur.getValue(info));
    }

    /**
     * Tests the column for the play count.
     */
    public void testPlayColumn()
    {
        Column<SongInfo, ?> colPlay = prepareColumnTest(2);
        SongInfo info = createSongInfo();
        assertEquals("Wrong play count", String.valueOf(info.getPlayCount()),
                colPlay.getValue(info));
    }

    /**
     * Tests whether the correct comparators are added.
     */
    public void testColumnComparatorMapping()
    {
        checkComparatorMapping(new SongDetailsTable(),
                SongComparators.NAME_COMPARATOR,
                SongComparators.DURATION_PROPERTY_COMPARATOR,
                SongComparators.PLAYCOUNT_PROPERTY_COMPARATOR);
    }
}
