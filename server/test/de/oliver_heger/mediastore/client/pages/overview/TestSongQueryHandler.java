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
import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code SongQueryHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongQueryHandler
{
    /** Constant for the prefix of a key for a song object. */
    private static final String SONG_KEY = "songKey";

    /** The prefix for formatted dates. */
    private static final String FMT_DATE_PREFIX = "formattedDate:";

    /** Constant for the number of test search results. */
    private static final int RESULT_COUNT = 12;

    /** The view object. */
    private SearchResultView view;

    /** The handler to be tested. */
    private SongQueryHandler handler;

    @Before
    public void setUp() throws Exception
    {
        view = EasyMock.createMock(SearchResultView.class);
        handler = new SongQueryHandlerTestImpl(view);
    }

    /**
     * Creates a song info object with the specified index.
     *
     * @param idx the index
     * @return the info object
     */
    private static SongInfo createSongInfo(int idx)
    {
        SongInfo info = new SongInfo();
        info.setCreationDate(new Date());
        info.setSongID(SONG_KEY + idx);
        info.setArtistName("Artist" + idx);
        info.setDuration(Long.valueOf(idx));
        info.setInceptionYear(1970 + idx);
        info.setName("Test Song " + idx);
        info.setPlayCount(idx);
        info.setTrackNo(idx);
        return info;
    }

    /**
     * Returns a number of song info objects.
     *
     * @param count the number of objects to create
     * @return a list with the test instances
     */
    private static List<SongInfo> createSongInfos(int count)
    {
        List<SongInfo> list = new ArrayList<SongInfo>(count);
        for (int i = 0; i < count; i++)
        {
            list.add(createSongInfo(i));
        }
        return list;
    }

    /**
     * Creates a mock result object with the given number of info objects.
     *
     * @param count the number of info objects
     * @return the mock result object (already replayed)
     */
    private static SearchResult<SongInfo> createResultMock(int count)
    {
        @SuppressWarnings("unchecked")
        SearchResult<SongInfo> result = EasyMock.createMock(SearchResult.class);
        EasyMock.expect(result.getResults()).andReturn(createSongInfos(count))
                .anyTimes();
        EasyMock.replay(result);
        return result;
    }

    /**
     * Tests whether the view object is passed to the super class.
     */
    @Test
    public void testGetView()
    {
        assertSame("Wrong view", view, handler.getView());
    }

    /**
     * Tests whether the service is called correctly.
     */
    @Test
    public void testCallService()
    {
        MediaSearchServiceAsync service =
                EasyMock.createMock(MediaSearchServiceAsync.class);
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        @SuppressWarnings("unchecked")
        AsyncCallback<SearchResult<SongInfo>> callback =
                EasyMock.createMock(AsyncCallback.class);
        MediaSearchParameters params = new MediaSearchParameters();
        service.searchSongs(params, sit, callback);
        EasyMock.replay(service, sit, callback);
        handler.callService(service, params, sit, callback);
        EasyMock.verify(service, sit, callback);
    }

    /**
     * Tests whether the result object created by the handler contains the
     * expected data.
     */
    @Test
    public void testCreateResultData()
    {
        SearchResult<SongInfo> result = createResultMock(RESULT_COUNT);
        ResultData data = handler.createResult(result);
        assertEquals("Wrong number of rows", RESULT_COUNT, data.getRowCount());
        for (int row = 0; row < RESULT_COUNT; row++)
        {
            assertEquals("Wrong ID", SONG_KEY + row, data.getID(row));
        }
    }

    /**
     * Tests whether the result data object contains the expected columns.
     */
    @Test
    public void testCreateResultColumns()
    {
        SearchResult<SongInfo> result = createResultMock(RESULT_COUNT);
        ResultData data = handler.createResult(result);
        final String[] expColumns =
                {
                        "Name", "Artist", "Duration", "Year", "Track",
                        "Played", "Created at"
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
     * Tests whether the properties of song info objects are correctly returned.
     */
    @Test
    public void testGetPropertyForColumn()
    {
        SearchResult<SongInfo> result = createResultMock(RESULT_COUNT);
        @SuppressWarnings("unchecked")
        AbstractResultData<SongInfo> data =
                (AbstractResultData<SongInfo>) handler.createResult(result);
        SongInfo info = createSongInfo(0);
        assertEquals("Wrong name", info.getName(),
                data.getPropertyForColumn(info, 0));
        assertEquals("Wrong artist", info.getArtistName(),
                data.getPropertyForColumn(info, 1));
        assertEquals("Wrong duration", info.getFormattedDuration(),
                data.getPropertyForColumn(info, 2));
        assertEquals("Wrong year", info.getInceptionYear(),
                data.getPropertyForColumn(info, 3));
        assertEquals("Wrong track", info.getTrackNo(),
                data.getPropertyForColumn(info, 4));
        assertEquals("Wrong play count", Integer.valueOf(info.getPlayCount()),
                data.getPropertyForColumn(info, 5));
        assertEquals("Wrong date", FMT_DATE_PREFIX + info.getCreationDate(),
                data.getPropertyForColumn(info, 6));
    }

    /**
     * Tests getPropertyForColumn() if an invalid column index is passed in.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testGetPropertyForColumnInvalid()
    {
        SearchResult<SongInfo> result = createResultMock(RESULT_COUNT);
        @SuppressWarnings("unchecked")
        AbstractResultData<SongInfo> data =
                (AbstractResultData<SongInfo>) handler.createResult(result);
        SongInfo info = createSongInfo(0);
        data.getPropertyForColumn(info, 100);
    }

    /**
     * A test implementation of SongQueryHandler with additional mocking
     * support.
     */
    private static class SongQueryHandlerTestImpl extends SongQueryHandler
    {
        public SongQueryHandlerTestImpl(SearchResultView v)
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
