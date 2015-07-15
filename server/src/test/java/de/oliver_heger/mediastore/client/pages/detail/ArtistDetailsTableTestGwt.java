package de.oliver_heger.mediastore.client.pages.detail;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.ColumnSortEvent;
import com.google.gwt.view.client.ListDataProvider;

import de.oliver_heger.mediastore.client.LinkColumn;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.shared.model.ArtistComparators;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;

/**
 * Test class for {@code ArtistDetailsTable}. This class also tests
 * functionality of the base class.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistDetailsTableTestGwt extends AbstractTestDetailsTable
{
    /** Constant for the name of a test artist. */
    private static final String ARTIST_NAME = "TestArtist";

    /** Constant for the test artist ID. */
    private static final long ARTIST_ID = 20111209210524L;

    /** Stores the mock page manager passed to the test instance. */
    private MockPageManager pageManager;

    /**
     * Creates a test artist. Based on the given index unique property values
     * are generated.
     *
     * @param idx the index
     * @return the info object for the test artist
     */
    private static ArtistInfo createArtist(int idx)
    {
        ArtistInfo info = new ArtistInfo();
        info.setArtistID(Long.valueOf(ARTIST_ID + idx));
        info.setName(ARTIST_NAME + idx);
        info.setCreationDate(new Date(info.getArtistID()));
        return info;
    }

    /**
     * Creates a test table instance and initializes it with the mock page
     * manager.
     *
     * @return the test table
     */
    private ArtistDetailsTable createInitializedTable()
    {
        pageManager = new MockPageManager();
        ArtistDetailsTable table = new ArtistDetailsTable();
        table.initialize(pageManager);
        return table;
    }

    /**
     * Tests whether a default list data provider is created.
     */
    public void testDefaultListDataProvider()
    {
        ArtistDetailsTable table = createInitializedTable();
        ListDataProvider<ArtistInfo> provider = table.getDataProvider();
        assertNotNull("No data provider", provider);
        assertTrue("Already got data", provider.getList().isEmpty());
        assertTrue("Table not registered",
                provider.getDataDisplays().contains(table.cellTable));
    }

    /**
     * Tests whether the column for the artist name is correctly initialized.
     */
    public void testArtistColumn()
    {
        ArtistDetailsTable table = createInitializedTable();
        Column<ArtistInfo, ?> column = table.cellTable.getColumn(0);
        assertTrue("Wrong column type: " + column, column instanceof LinkColumn);
        @SuppressWarnings("unchecked")
        LinkColumn<ArtistInfo> lcol = (LinkColumn<ArtistInfo>) column;
        ArtistInfo info = createArtist(0);
        assertEquals("Wrong value", info.getName(), lcol.getValue(info));
        assertEquals("Wrong ID", info.getArtistID(), lcol.getID(info));
    }

    /**
     * Tests whether a correct key provider is set.
     */
    public void testKeyProvider()
    {
        ArtistDetailsTable table = createInitializedTable();
        ArtistInfo info = createArtist(1);
        assertEquals("Wrong key", info.getArtistID(), table.cellTable
                .getKeyProvider().getKey(info));
    }

    /**
     * Tests whether data can be set for the table.
     */
    public void testSetData()
    {
        final int count = 32;
        List<ArtistInfo> artists = new ArrayList<ArtistInfo>(count);
        for (int i = 0; i < count; i++)
        {
            artists.add(createArtist(i));
        }
        ListDataProviderTestImpl provider = new ListDataProviderTestImpl();
        ArtistDetailsTable table = new ArtistDetailsTable(provider);
        table.setData(artists);
        assertEquals("Wrong number of items", count, provider.getList().size());
        assertTrue("Wrong content: " + provider.getList(), provider.getList()
                .containsAll(artists));
    }

    /**
     * Tests whether a null list can be passed to setData().
     */
    public void testSetDataNull()
    {
        ListDataProviderTestImpl provider = new ListDataProviderTestImpl();
        ArtistDetailsTable table = new ArtistDetailsTable(provider);
        table.setData(null);
        assertTrue("List not cleared", provider.getList().isEmpty());
    }

    /**
     * Tests whether a correct list sort handler is created.
     */
    public void testCreateSortHandler()
    {
        ListDataProviderTestImpl provider = new ListDataProviderTestImpl();
        ArtistDetailsTable table = new ArtistDetailsTable(provider);
        ColumnSortEvent.ListHandler<ArtistInfo> handler =
                table.createSortHandler();
        assertSame("Wrong list for sort handler", provider.getList(),
                handler.getList());
    }

    /**
     * Tests whether the correct comparators have been configured.
     */
    public void testColumnComparatorMapping()
    {
        ArtistDetailsTable table = new ArtistDetailsTable();
        checkComparatorMapping(table, ArtistComparators.NAME_COMPARATOR);
    }

    /**
     * A list data provider implementation with some mocking facilities.
     */
    private static class ListDataProviderTestImpl extends
            ListDataProvider<ArtistInfo>
    {
        /** The list managed by this provider. */
        private final List<ArtistInfo> list;

        public ListDataProviderTestImpl()
        {
            list = new ArrayList<ArtistInfo>();
            list.add(createArtist(1));
            list.add(createArtist(28));
            list.add(createArtist(63));
        }

        @Override
        public List<ArtistInfo> getList()
        {
            return list;
        }
    }
}
