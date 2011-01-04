package de.oliver_heger.mediastore.client.pages.overview;

import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A specialized query handler implementation for songs.
 * </p>
 * <p>
 * This class calls the {@code searchSongs()} method of the
 * {@link MediaSearchServiceAsync} interface. The results are transformed in a
 * corresponding data object which can be displayed on an overview page for
 * songs.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class SongQueryHandler extends AbstractOverviewQueryHandler<SongInfo>
{
    /** An array with the columns of the song overview table. */
    private static final String[] COLUMNS = {
            "Name", "Artist", "Duration", "Year", "Track", "Played",
            "Created at"
    };

    /**
     * Creates a new instance of {@code SongQueryHandler} and initializes it
     * with the view.
     *
     * @param v the view
     */
    public SongQueryHandler(SearchResultView v)
    {
        super(v);
    }

    /**
     * {@inheritDoc} This implementation calls the method for searching songs.
     */
    @Override
    protected void callService(MediaSearchServiceAsync service,
            MediaSearchParameters searchParams, SearchIterator searchIterator,
            AsyncCallback<SearchResult<SongInfo>> callback)
    {
        service.searchSongs(searchParams, searchIterator, callback);
    }

    /**
     * {@inheritDoc} This implementation creates a specialized
     * {@link ResultData} object based on a list of {@link SongInfo} objects
     * returned by the search service.
     */
    @Override
    protected ResultData createResult(SearchResult<SongInfo> result)
    {
        return new SongResultData(result.getResults());
    }

    /**
     * A specialized result data implementation which is based on a list of
     * {@link SongInfo} objects.
     */
    private class SongResultData extends AbstractResultData<SongInfo>
    {
        /**
         * Standard constructor. Needed for serialization.
         */
        @SuppressWarnings("unused")
        private SongResultData()
        {
        }

        /**
         * Creates a new instance of {@code SongResultData} and initializes it
         * with the list of data.
         *
         * @param data the underlying data
         */
        public SongResultData(List<SongInfo> data)
        {
            super(COLUMNS, data);
        }

        /**
         * {@inheritDoc} This implementation maps the properties of a
         * {@link SongInfo} object to column indexes.
         */
        @Override
        protected Object getPropertyForColumn(SongInfo data, int col)
        {
            switch (col)
            {
            case 0:
                return data.getName();
            case 1:
                return data.getArtistName();
            case 2:
                return data.getFormattedDuration();
            case 3:
                return data.getInceptionYear();
            case 4:
                return data.getTrackNo();
            case 5:
                return data.getPlayCount();
            case 6:
                return getFormatter().formatDate(data.getCreationDate());
            default:
                throw new IllegalArgumentException("Invalid column index: "
                        + col);
            }
        }

        /**
         * {@inheritDoc} This implementation extracts the ID of the specified
         * {@link SongInfo} object.
         */
        @Override
        protected Object getIDOfObject(SongInfo data)
        {
            return data.getSongID();
        }
    }
}
