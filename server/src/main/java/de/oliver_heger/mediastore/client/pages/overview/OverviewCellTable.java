package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.cellview.client.CellTable;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.SimplePager;
import com.google.gwt.user.cellview.client.SimplePager.TextLocation;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.AsyncDataProvider;
import com.google.gwt.view.client.HasData;
import com.google.gwt.view.client.ProvidesKey;
import com.google.gwt.view.client.Range;

import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchService;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchResult;

public class OverviewCellTable extends Composite
{
    /** The key provider.*/
    private static final ProvidesKey<SongInfo> KEY_PROVIDER = new ProvidesKey<SongInfo>()
    {
        @Override
        public Object getKey(SongInfo item)
        {
            return item.getSongID();
        }
    };

    /** The binder instance. */
    private static OverviewCellTableUiBinder uiBinder = GWT
            .create(OverviewCellTableUiBinder.class);

    @UiField(provided = true)
    CellTable<SongInfo> cellTable;

    @UiField(provided = true)
    SimplePager pager;

    /** The search service.*/
    private MediaSearchServiceAsync searchService;

    public OverviewCellTable()
    {
        initCellTable();
        initDataProvider();
        initWidget(uiBinder.createAndBindUi(this));
    }

    /**
     * Returns the search service.
     *
     * @return the search service
     */
    MediaSearchServiceAsync getSearchService()
    {
        if (searchService == null)
        {
            searchService = GWT.create(MediaSearchService.class);
        }
        return searchService;
    }

    /**
     * Creates a callback object to be passed to the search service.
     * @return the callback object
     */
    AsyncCallback<SearchResult<SongInfo>> createSearchCallback()
    {
        return new AsyncCallback<SearchResult<SongInfo>>()
        {

            @Override
            public void onFailure(Throwable caught)
            {
                // TODO Implementation
                throw new UnsupportedOperationException("Not yet implemented!");
            }

            @Override
            public void onSuccess(SearchResult<SongInfo> result)
            {
                cellTable.setRowCount((int) result.getSearchIterator().getRecordCount());
                cellTable.setRowData(result.getSearchParameters().getFirstResult(), result.getResults());
            }
        };
    }

    /**
     * Creates the controls for the cell table.
     */
    private void initCellTable()
    {
        cellTable = new CellTable<SongInfo>(KEY_PROVIDER);

        SimplePager.Resources pagerResources = GWT.create(SimplePager.Resources.class);
        pager = new SimplePager(TextLocation.CENTER, pagerResources, false, 0, true);
        pager.setDisplay(cellTable);

        Column<SongInfo, String> nameCol = new Column<SongInfo, String>(new TextCell())
        {
            @Override
            public String getValue(SongInfo object)
            {
                return object.getName();
            }
        };
        cellTable.addColumn(nameCol, "Title");
    }

    /**
     * Creates the data provider for the cell table.
     */
    private void initDataProvider()
    {
        AsyncDataProvider<SongInfo> provider = new AsyncDataProvider<SongInfo>()
        {
            @Override
            protected void onRangeChanged(HasData<SongInfo> display)
            {
                Range range = display.getVisibleRange();
                MediaSearchParameters params = new MediaSearchParameters();
                params.setFirstResult(range.getStart());
                params.setMaxResults(range.getLength());
                getSearchService().searchSongs(params, null, createSearchCallback());
            }
        };
        provider.addDataDisplay(cellTable);
    }

    /**
     * The UI binder.
     */
    interface OverviewCellTableUiBinder extends
            UiBinder<Widget, OverviewCellTable>
    {
    }
}
