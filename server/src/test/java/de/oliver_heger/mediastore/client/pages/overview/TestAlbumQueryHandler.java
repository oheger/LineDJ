package de.oliver_heger.mediastore.client.pages.overview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.client.I18NFormatter;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code AlbumQueryHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumQueryHandler
{
    /** Constant for the number of test albums. */
    private static final int ALBUM_COUNT = 16;

    /** The prefix for formatted dates. */
    private static final String FMT_DATE_PREFIX = "formattedDate:";

    /** The search view. */
    private SearchResultView view;

    /** The query handler to be tested. */
    private AlbumQueryHandler handler;

    @Before
    public void setUp() throws Exception
    {
        view = EasyMock.createNiceMock(SearchResultView.class);
        handler = new AlbumQueryHandlerTestImpl(view);
    }

    /**
     * Creates a test album info object.
     *
     * @param idx the index
     * @return the test object
     */
    private static AlbumInfo createAlbumInfo(int idx)
    {
        AlbumInfo info = new AlbumInfo();
        info.setAlbumID(Long.valueOf(idx));
        info.setDuration((60 + idx) * 1000L);
        info.setInceptionYear(1970 + idx);
        info.setName("TestAlbum" + idx);
        info.setNumberOfSongs(idx);
        return info;
    }

    /**
     * Creates a number of test albums.
     *
     * @return the list with the test albums
     */
    private static List<AlbumInfo> createTestAlbums()
    {
        List<AlbumInfo> result = new ArrayList<AlbumInfo>(ALBUM_COUNT);
        for (int i = 0; i < ALBUM_COUNT; i++)
        {
            result.add(createAlbumInfo(i));
        }
        return result;
    }

    /**
     * Creates a mock search result object with the test albums.
     *
     * @return the mock result
     */
    private static SearchResult<AlbumInfo> createMockResult()
    {
        @SuppressWarnings("unchecked")
        SearchResult<AlbumInfo> result =
                EasyMock.createMock(SearchResult.class);
        EasyMock.expect(result.getResults()).andReturn(createTestAlbums())
                .anyTimes();
        EasyMock.replay(result);
        return result;
    }

    /**
     * Tests whether the view is correctly initialized.
     */
    @Test
    public void testGetView()
    {
        assertSame("Wrong view", view, handler.getView());
    }

    /**
     * Tests whether the correct service method is called.
     */
    @Test
    public void testCallService()
    {
        MediaSearchServiceAsync service =
                EasyMock.createMock(MediaSearchServiceAsync.class);
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        @SuppressWarnings("unchecked")
        AsyncCallback<SearchResult<AlbumInfo>> callback =
                EasyMock.createMock(AsyncCallback.class);
        MediaSearchParameters params = new MediaSearchParameters();
        service.searchAlbums(params, sit, callback);
        EasyMock.replay(service, sit, callback);
        handler.callService(service, params, sit, callback);
        EasyMock.verify(service, sit, callback);
    }

    /**
     * Tests whether the result object contains the expected objects.
     */
    @Test
    public void testCreateResultData()
    {
        SearchResult<AlbumInfo> result = createMockResult();
        ResultData data = handler.createResult(result);
        List<AlbumInfo> infos = createTestAlbums();
        assertEquals("Wrong number of rows", infos.size(), data.getRowCount());
        for (int i = 0; i < infos.size(); i++)
        {
            assertEquals("Wrong ID at " + i, infos.get(i).getAlbumID(),
                    data.getID(i));
        }
        EasyMock.verify(result);
    }

    /**
     * Tests whether the result object has the correct columns.
     */
    @Test
    public void testCreateResultColumns()
    {
        SearchResult<AlbumInfo> result = createMockResult();
        ResultData data = handler.createResult(result);
        final String[] expColumns = {
                "Name", "Songs", "Duration", "Year", "Created at"
        };
        assertEquals("Wrong number of columns", expColumns.length,
                data.getColumnCount());
        for (int i = 0; i < expColumns.length; i++)
        {
            assertEquals("Wrong column at " + i, expColumns[i],
                    data.getColumnName(i));
        }
    }

    /**
     * Tests whether the correct properties of an info object are returned.
     */
    @Test
    public void testGetPropertyForColumn()
    {
        SearchResult<AlbumInfo> result = createMockResult();
        @SuppressWarnings("unchecked")
        AbstractResultData<AlbumInfo> data =
                (AbstractResultData<AlbumInfo>) handler.createResult(result);
        AlbumInfo info = createAlbumInfo(2);
        assertEquals("Wrong name", info.getName(),
                data.getPropertyForColumn(info, 0));
        assertEquals("Wrong song count",
                Integer.valueOf(info.getNumberOfSongs()),
                data.getPropertyForColumn(info, 1));
        assertEquals("Wrong duration", info.getFormattedDuration(),
                data.getPropertyForColumn(info, 2));
        assertEquals("Wrong year", info.getInceptionYear(),
                data.getPropertyForColumn(info, 3));
        assertEquals("Wrong date", FMT_DATE_PREFIX + info.getCreationDate(),
                data.getPropertyForColumn(info, 4));
    }

    /**
     * Tests getPropertyForColumn() if an invalid index is passed in.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testGetPropertyForColumnInvalid()
    {
        SearchResult<AlbumInfo> result = createMockResult();
        @SuppressWarnings("unchecked")
        AbstractResultData<AlbumInfo> data =
                (AbstractResultData<AlbumInfo>) handler.createResult(result);
        data.getPropertyForColumn(createAlbumInfo(0), -1);
    }

    /**
     * A test implementation of the handler which mocks the formatting of dates.
     */
    private static class AlbumQueryHandlerTestImpl extends AlbumQueryHandler
    {
        public AlbumQueryHandlerTestImpl(SearchResultView v)
        {
            super(v);
        }

        /**
         * Returns a mock formatter.
         */
        @Override
        protected I18NFormatter getFormatter()
        {
            return new I18NFormatter()
            {
                @Override
                public String formatDate(Date date)
                {
                    return FMT_DATE_PREFIX + date;
                }
            };
        }
    }
}
