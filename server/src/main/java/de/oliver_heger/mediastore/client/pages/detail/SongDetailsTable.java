package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.cellview.client.CellTable;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.ColumnSortEvent.ListHandler;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.LinkColumn;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.SongComparators;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * <p>
 * A specialized details table implementation which displays song info objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongDetailsTable extends AbstractDetailsTable<SongInfo>
{
    /** The key provider. */
    private static ProvidesKey<SongInfo> KEY_PROVIDER =
            new ProvidesKey<SongInfo>()
            {
                @Override
                public Object getKey(SongInfo item)
                {
                    return extractSongKey(item);
                }
            };

    /**
     * Creates a new instance of {@code SongDetailsTable}.
     */
    public SongDetailsTable()
    {
        super(KEY_PROVIDER, new SongTableInitializer(), null);
    }

    /**
     * Extracts the key from the specified song info object.
     *
     * @param item the affected info object
     * @return the key of this song
     */
    private static String extractSongKey(SongInfo item)
    {
        return item.getSongID();
    }

    /**
     * The {@code TableInitializer} implementation for the songs details table.
     */
    private static class SongTableInitializer implements
            TableInitializer<SongInfo>
    {
        @Override
        public void initializeTable(CellTable<SongInfo> table,
                ListHandler<SongInfo> sortHandler, PageManager pageManager)
        {
            Column<SongInfo, String> colName =
                    new LinkColumn<SongInfo>(pageManager, Pages.SONGDETAILS)
                    {
                        @Override
                        public Object getID(SongInfo obj)
                        {
                            return extractSongKey(obj);
                        }

                        @Override
                        public String getValue(SongInfo object)
                        {
                            return object.getName();
                        }
                    };
            table.addColumn(colName, "Name");
            table.setColumnWidth(colName, 50, Unit.PCT);
            colName.setSortable(true);
            sortHandler.setComparator(colName, SongComparators.NAME_COMPARATOR);

            Column<SongInfo, String> colDuration =
                    new Column<SongInfo, String>(new TextCell())
                    {
                        @Override
                        public String getValue(SongInfo object)
                        {
                            return object.getFormattedDuration();
                        }
                    };
            table.addColumn(colDuration, "Duration");
            table.setColumnWidth(colDuration, 30, Unit.PCT);
            colDuration.setSortable(true);
            sortHandler.setComparator(colDuration,
                    SongComparators.DURATION_PROPERTY_COMPARATOR);

            Column<SongInfo, String> colPlay =
                    new Column<SongInfo, String>(new TextCell())
                    {
                        @Override
                        public String getValue(SongInfo object)
                        {
                            return String.valueOf(object.getPlayCount());
                        }
                    };
            table.addColumn(colPlay, "Played");
            table.setColumnWidth(colPlay, 20, Unit.PCT);
            colPlay.setSortable(true);
            sortHandler.setComparator(colPlay,
                    SongComparators.PLAYCOUNT_PROPERTY_COMPARATOR);
        }
    }
}
