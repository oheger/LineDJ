package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.cellview.client.CellTable;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.LinkColumn;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;

/**
 * <p>
 * A specialized details table implementation for displaying a list with data
 * objects with album information.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumDetailsTable extends AbstractDetailsTable<AlbumInfo>
{
    /** The key provider. */
    private static final ProvidesKey<AlbumInfo> KEY_PROVIDER =
            new ProvidesKey<AlbumInfo>()
            {
                @Override
                public Object getKey(AlbumInfo item)
                {
                    return extractAlbumKey(item);
                }
            };

    /** Constant for an empty string. */
    private static final String EMPTY = "";

    /**
     * Creates a new instance of {@code AlbumDetailsTable} with default
     * settings.
     */
    public AlbumDetailsTable()
    {
        super(KEY_PROVIDER, new AlbumTableInitializer(), null);
    }

    /**
     * Extracts the key of an album data object. This implementation returns the
     * album's ID.
     *
     * @param item the album data object
     * @return the key for this data object
     */
    private static Object extractAlbumKey(AlbumInfo item)
    {
        return item.getAlbumID();
    }

    /**
     * Null-safe string transformation method.
     *
     * @param obj the object to be converted
     * @return the string representation for this object
     */
    private static String toString(Object obj)
    {
        return (obj != null) ? obj.toString() : EMPTY;
    }

    /**
     * An initializer implementation for the album table.
     */
    private static class AlbumTableInitializer implements
            TableInitializer<AlbumInfo>
    {
        @Override
        public void initializeTable(CellTable<AlbumInfo> table,
                PageManager pageManager)
        {
            Column<AlbumInfo, String> colName =
                    new LinkColumn<AlbumInfo>(pageManager, Pages.ALBUMDETAILS)
                    {
                        @Override
                        public Object getID(AlbumInfo obj)
                        {
                            return extractAlbumKey(obj);
                        }

                        @Override
                        public String getValue(AlbumInfo object)
                        {
                            return object.getName();
                        }
                    };
            table.addColumn(colName, "Name");
            table.setColumnWidth(colName, 50, Unit.PCT);

            Column<AlbumInfo, String> colDuration =
                    new Column<AlbumInfo, String>(new TextCell())
                    {
                        @Override
                        public String getValue(AlbumInfo object)
                        {
                            return object.getFormattedDuration();
                        }
                    };
            table.addColumn(colDuration, "Duration");
            table.setColumnWidth(colDuration, 25, Unit.PCT);

            Column<AlbumInfo, String> colCount =
                    new Column<AlbumInfo, String>(new TextCell())
                    {
                        @Override
                        public String getValue(AlbumInfo object)
                        {
                            return String.valueOf(object.getNumberOfSongs());
                        }
                    };
            table.addColumn(colCount, "Songs");
            table.setColumnWidth(colCount, 10, Unit.PCT);

            Column<AlbumInfo, String> colYear =
                    new Column<AlbumInfo, String>(new TextCell())
                    {
                        @Override
                        public String getValue(AlbumInfo object)
                        {
                            return AlbumDetailsTable.toString(object
                                    .getInceptionYear());
                        }
                    };
            table.addColumn(colYear, "Year");
            table.setColumnWidth(colYear, 15, Unit.PCT);
        }
    }
}
