package de.oliver_heger.mediastore.client.pages.overview;

import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A specialized query handler for {@link AlbumInfo} objects.
 * </p>
 * <p>
 * This class calls the method of the search service for searching for albums.
 * The results are transformed into a corresponding {@link ResultData} object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class AlbumQueryHandler extends AbstractOverviewQueryHandler<AlbumInfo>
{
    /** An array with the columns of the album overview table. */
    private static final String[] COLUMNS = {
            "Name", "Songs", "Duration", "Year", "Created at"
    };

    /**
     * Creates a new instance of {@code AlbumQueryHandler} and sets the view.
     *
     * @param v the {@link SearchResultView}
     */
    public AlbumQueryHandler(SearchResultView v)
    {
        super(v);
    }

    /**
     * {@inheritDoc} This implementation calls the method for searching for
     * albums.
     */
    @Override
    protected void callService(MediaSearchServiceAsync service,
            MediaSearchParameters searchParams, SearchIterator searchIterator,
            AsyncCallback<SearchResult<AlbumInfo>> callback)
    {
        service.searchAlbums(searchParams, searchIterator, callback);
    }

    /**
     * {@inheritDoc} This implementation creates a specialized
     * {@code ResultData} object for album information.
     */
    @Override
    protected ResultData createResult(SearchResult<AlbumInfo> result)
    {
        return new AlbumResultData(result.getResults());
    }

    /**
     * A specialized result data implementation for a list of albums.
     */
    private class AlbumResultData extends AbstractResultData<AlbumInfo>
    {
        /**
         * Creates a new instance of {@code AlbumResultData} and initializes it
         * with the given data list.
         *
         * @param data the list with data about albums
         */
        public AlbumResultData(List<AlbumInfo> data)
        {
            super(COLUMNS, data);
        }

        /**
         * {@inheritDoc} This implementation maps the passed in column index to
         * a property of the given {@link AlbumInfo} object.
         */
        @Override
        protected Object getPropertyForColumn(AlbumInfo data, int col)
        {
            switch (col)
            {
            case 0:
                return data.getName();
            case 1:
                return data.getNumberOfSongs();
            case 2:
                return data.getFormattedDuration();
            case 3:
                return data.getInceptionYear();
            case 4:
                return getFormatter().formatDate(data.getCreationDate());
            default:
                throw new IllegalArgumentException("Invalid column index: "
                        + col);
            }
        }

        /**
         * {@inheritDoc} This implementation returns the ID of the given album.
         */
        @Override
        protected Object getIDOfObject(AlbumInfo data)
        {
            return data.getAlbumID();
        }
    }
}
