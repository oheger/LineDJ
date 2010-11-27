package de.oliver_heger.mediastore.client;

import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A specialized query handler for processing search queries for artists.
 * </p>
 * <p>
 * This class queries the media search service for artists and returns a
 * corresponding {@link ResultData} object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class ArtistQueryHandler extends AbstractOverviewQueryHandler<ArtistInfo>
{
    /** An array with the columns of the artist overview table. */
    private static final String[] COLUMNS = {
        "Name"
    };

    /**
     * Creates a new instance of {@code ArtistQueryHandler}.
     *
     * @param v the result view object
     */
    public ArtistQueryHandler(SearchResultView v)
    {
        super(v);
    }

    /**
     * Creates a result object for the specified server search result.
     *
     * @param result the object with results of the current query
     * @return a tabular result object
     */
    @Override
    protected ResultData createResult(SearchResult<ArtistInfo> result)
    {
        return new ArtistResultData(result.getResults());
    }

    /**
     * Calls the appropriate service method. This implementation queries for
     * artists.
     *
     * @param service the search service
     * @param searchParams the parameters for the search
     * @param searchIterator the search iterator
     * @param callback the callback object
     */
    @Override
    protected void callService(MediaSearchServiceAsync service,
            MediaSearchParameters searchParams, SearchIterator searchIterator,
            AsyncCallback<SearchResult<ArtistInfo>> callback)
    {
        service.searchArtists(searchParams, searchIterator, callback);
    }

    /**
     * A specialized result handler for artists.
     */
    private static class ArtistResultData extends AbstractResultData<ArtistInfo>
    {
        /**
         * Standard constructor. Needed for serialization.
         */
        @SuppressWarnings("unused")
        private ArtistResultData()
        {
        }

        /**
         * Creates a new instance of {@code ArtistResultData} and initializes
         * it.
         *
         * @param data the list with the result data
         */
        public ArtistResultData(List<ArtistInfo> data)
        {
            super(COLUMNS, data);
        }

        /**
         * Returns the value for the specified column index.
         *
         * @param data the current artist object
         * @param col the column index
         * @return the corresponding property
         */
        @Override
        protected Object getPropertyForColumn(ArtistInfo data, int col)
        {
            return data.getName();
        }
    }
}
