package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.cellview.client.CellTable;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.LinkColumn;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * <p>
 * The concrete overview page for songs.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongOverviewPage extends AbstractOverviewTable<SongInfo>
{
    /** The key provider. */
    private static final ProvidesKey<SongInfo> KEY_PROVIDER =
            new ProvidesKey<SongInfo>()
            {
                @Override
                public Object getKey(SongInfo item)
                {
                    return item.getSongID();
                }
            };

    /**
     * Creates a new instance of {@code SongOverviewPage}.
     */
    public SongOverviewPage()
    {
        super(KEY_PROVIDER, new SongOverviewQueryHandler());
    }

    /**
     * Initializes the handlers for multiple elements.
     */
    @Override
    protected void initMultiElementHandlers()
    {
        addMultiElementHandler(getImageResources().removeItem(), ACTION_REMOVE,
                new RemoveElementHandler(createRemoveSongHandler(), this,
                        getRemoveController()));
    }

    /**
     * Initializes the columns of the main table.
     *
     * @param table the table to be initialized
     */
    @Override
    protected void initCellTableColumns(CellTable<SongInfo> table)
    {
        Column<SongInfo, String> colName = createNameColumn();
        addColumn(colName, "Name", "name", true);
        cellTable.setColumnWidth(colName, 20, Unit.PCT);
        Column<SongInfo, String> colArtist = createArtistColumn();
        addColumn(colArtist, "Artist", null, false);
        cellTable.setColumnWidth(colArtist, 20, Unit.PCT);
        Column<SongInfo, String> colDuration = createDurationColumn();
        addColumn(colDuration, "Duration", "duration", false);
        cellTable.setColumnWidth(colDuration, 8, Unit.PCT);
        Column<SongInfo, String> colYear = createYearColumn();
        addColumn(colYear, "Year", "inceptionYear", false);
        cellTable.setColumnWidth(colYear, 8, Unit.PCT);
        Column<SongInfo, String> colAlbum = createAlbumColumn();
        addColumn(colAlbum, "Album", null, false);
        cellTable.setColumnWidth(colAlbum, 20, Unit.PCT);
        Column<SongInfo, String> colTrack = createTrackColumn();
        addColumn(colTrack, "Track", "trackNo", false);
        cellTable.setColumnWidth(colTrack, 5, Unit.PCT);
        Column<SongInfo, String> colPlayCount = createPlayCountColumn();
        addColumn(colPlayCount, "Played", "playCount", false);
        cellTable.setColumnWidth(colPlayCount, 5, Unit.PCT);
        Column<SongInfo, String> colDate = createDateColumn();
        addColumn(colDate, "Date", "creationDate", false);
        cellTable.setColumnWidth(colDate, 14, Unit.PCT);
    }

    /**
     * Creates the column for the name of the song.
     *
     * @return the name column
     */
    Column<SongInfo, String> createNameColumn()
    {
        Column<SongInfo, String> col =
                new LinkColumn<SongInfo>(getPageManager(), Pages.SONGDETAILS)
                {
                    @Override
                    public Object getID(SongInfo obj)
                    {
                        return obj.getSongID();
                    }

                    @Override
                    public String getValue(SongInfo object)
                    {
                        return object.getName();
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the column for the artist.
     *
     * @return the artist column
     */
    Column<SongInfo, String> createArtistColumn()
    {
        Column<SongInfo, String> col =
                new LinkColumn<SongInfo>(getPageManager(), Pages.ARTISTDETAILS)
                {
                    @Override
                    public Object getID(SongInfo obj)
                    {
                        return obj.getArtistID();
                    }

                    @Override
                    public String getValue(SongInfo object)
                    {
                        return object.getArtistName();
                    }
                };
        return col;
    }

    /**
     * Creates the column for the duration.
     *
     * @return the duration column
     */
    Column<SongInfo, String> createDurationColumn()
    {
        Column<SongInfo, String> col =
                new Column<SongInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(SongInfo object)
                    {
                        return object.getFormattedDuration();
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the column for the song's inception year.
     *
     * @return the year column
     */
    Column<SongInfo, String> createYearColumn()
    {
        Column<SongInfo, String> col =
                new Column<SongInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(SongInfo object)
                    {
                        return AbstractOverviewTable.toString(object
                                .getInceptionYear());
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the column for the album.
     *
     * @return the album column
     */
    Column<SongInfo, String> createAlbumColumn()
    {
        Column<SongInfo, String> col =
                new LinkColumn<SongInfo>(getPageManager(), Pages.ALBUMDETAILS)
                {
                    @Override
                    public Object getID(SongInfo obj)
                    {
                        return obj.getAlbumID();
                    }

                    @Override
                    public String getValue(SongInfo object)
                    {
                        return object.getAlbumName();
                    }
                };
        return col;
    }

    /**
     * Creates the column for the track number.
     *
     * @return the track column
     */
    Column<SongInfo, String> createTrackColumn()
    {
        Column<SongInfo, String> col =
                new Column<SongInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(SongInfo object)
                    {
                        return AbstractOverviewTable.toString(object
                                .getTrackNo());
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the column for the number of times the song was played.
     *
     * @return the column for the play count
     */
    Column<SongInfo, String> createPlayCountColumn()
    {
        Column<SongInfo, String> col =
                new Column<SongInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(SongInfo object)
                    {
                        return String.valueOf(object.getPlayCount());
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the column for the creation date.
     *
     * @return the date column
     */
    Column<SongInfo, String> createDateColumn()
    {
        Column<SongInfo, String> col =
                new Column<SongInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(SongInfo object)
                    {
                        return getFormatter().formatDate(
                                object.getCreationDate());
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the handler for removing songs.
     *
     * @return the remove service handler
     */
    RemoveServiceHandler createRemoveSongHandler()
    {
        return new RemoveServiceHandler()
        {
            @Override
            public void removeElement(BasicMediaServiceAsync service,
                    Object elemID, AsyncCallback<Boolean> callback)
            {
                service.removeSong((String) elemID, callback);
            }
        };
    }
}
