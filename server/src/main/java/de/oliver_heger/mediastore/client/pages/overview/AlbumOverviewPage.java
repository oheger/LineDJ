package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.cellview.client.CellTable;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;

/**
 * <p>
 * The concrete overview page for albums.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumOverviewPage extends AbstractOverviewTable<AlbumInfo>
{
    /** The key provider. */
    private static final ProvidesKey<AlbumInfo> KEY_PROVIDER =
            new ProvidesKey<AlbumInfo>()
            {
                @Override
                public Object getKey(AlbumInfo item)
                {
                    return item.getAlbumID();
                }
            };

    /**
     * Creates a new instance of {@code AlbumOverviewPage}.
     */
    public AlbumOverviewPage()
    {
        super(KEY_PROVIDER, new AlbumOverviewQueryHandler());
    }

    /**
     * Initializes the columns of the main table for this page.
     *
     * @param table the table control to be initialized
     */
    @Override
    protected void initCellTableColumns(CellTable<AlbumInfo> table)
    {
        Column<AlbumInfo, ?> colName = createNameColumn();
        addColumn(colName, "Name", "searchName", true);
        cellTable.setColumnWidth(colName, 40, Unit.PCT);
        Column<AlbumInfo, ?> colSongCnt = createSongCountColumn();
        addColumn(colSongCnt, "Songs", null, false);
        cellTable.setColumnWidth(colSongCnt, 15, Unit.PCT);
        Column<AlbumInfo, ?> colDuration = createDurationColumn();
        addColumn(colDuration, "Duration", null, false);
        cellTable.setColumnWidth(colDuration, 15, Unit.PCT);
        Column<AlbumInfo, ?> colYear = createYearColumn();
        addColumn(colYear, "Year", "inceptionYear", false);
        cellTable.setColumnWidth(colYear, 15, Unit.PCT);
        Column<AlbumInfo, ?> colDate = createDateColumn();
        addColumn(colDate, "Created at", "creationDate", false);
        cellTable.setColumnWidth(colDate, 15, Unit.PCT);
    }

    /**
     * Initializes the handlers for multiple elements.
     */
    @Override
    protected void initMultiElementHandlers()
    {
        addMultiElementHandler(getImageResources().removeItem(), ACTION_REMOVE,
                new RemoveElementHandler(createRemoveAlbumHandler(), this,
                        getRemoveController()));
    };

    /**
     * Creates the column for displaying the album name. This is a link column.
     *
     * @return the column for the album name
     */
    Column<AlbumInfo, String> createNameColumn()
    {
        Column<AlbumInfo, String> col =
                new LinkColumn<AlbumInfo>(getPageManager(), Pages.ALBUMDETAILS)
                {
                    @Override
                    protected Object getID(AlbumInfo obj)
                    {
                        return obj.getAlbumID();
                    }

                    @Override
                    public String getValue(AlbumInfo object)
                    {
                        return object.getName();
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the column which displays the number of songs in this album.
     *
     * @return the column for the number of songs
     */
    Column<AlbumInfo, String> createSongCountColumn()
    {
        Column<AlbumInfo, String> col =
                new Column<AlbumInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(AlbumInfo object)
                    {
                        return String.valueOf(object.getNumberOfSongs());
                    }
                };
        return col;
    }

    /**
     * Creates the column for the duration of the album.
     *
     * @return the duration column
     */
    Column<AlbumInfo, String> createDurationColumn()
    {
        Column<AlbumInfo, String> col =
                new Column<AlbumInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(AlbumInfo object)
                    {
                        return object.getFormattedDuration();
                    }
                };
        return col;
    }

    /**
     * Creates the column for the inception year of the album.
     *
     * @return the column for the inception year
     */
    Column<AlbumInfo, String> createYearColumn()
    {
        Column<AlbumInfo, String> col =
                new Column<AlbumInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(AlbumInfo object)
                    {
                        return AlbumOverviewPage.toString(object
                                .getInceptionYear());
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the column for the creation date.
     *
     * @return the creation date column
     */
    Column<AlbumInfo, String> createDateColumn()
    {
        Column<AlbumInfo, String> col =
                new Column<AlbumInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(AlbumInfo object)
                    {
                        return getFormatter().formatDate(
                                object.getCreationDate());
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates a remove service handler for removing an album.
     *
     * @return the remove service handler
     */
    RemoveServiceHandler createRemoveAlbumHandler()
    {
        return new RemoveServiceHandler()
        {
            @Override
            public void removeElement(BasicMediaServiceAsync service,
                    Object elemID, AsyncCallback<Boolean> callback)
            {
                service.removeAlbum((Long) elemID, callback);
            }
        };
    }
}
