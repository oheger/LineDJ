package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.cellview.client.CellTable;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.ColumnSortEvent;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.LinkColumn;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;

/**
 * <p>
 * A specialized details table implementation for displaying a list of artist
 * information.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistDetailsTable extends AbstractDetailsTable<ArtistInfo>
{
    /** The key provider for artist objects. */
    private static final ProvidesKey<ArtistInfo> KEY_PROVIDER =
            new ProvidesKey<ArtistInfo>()
            {
                @Override
                public Object getKey(ArtistInfo item)
                {
                    return item.getArtistID();
                }
            };

    /**
     * Creates a new instance of {@code ArtistDetailsTable} with default
     * settings.
     */
    public ArtistDetailsTable()
    {
        this(null);
    }

    /**
     * Creates a new instance of {@code ArtistDetailsTable} which uses the
     * specified data provider.
     *
     * @param provider the data provider to be used
     */
    public ArtistDetailsTable(ListDataProvider<ArtistInfo> provider)
    {
        super(KEY_PROVIDER, new ArtistTableInitializer(), provider);
    }

    /**
     * An implementation of the {@code TableInitializer} interface for
     * initializing the artist table.
     */
    private static class ArtistTableInitializer implements
            TableInitializer<ArtistInfo>
    {
        @Override
        public void initializeTable(CellTable<ArtistInfo> table,
                ColumnSortEvent.ListHandler<ArtistInfo> sortHandler,
                PageManager pm)
        {
            Column<ArtistInfo, String> col =
                    new LinkColumn<ArtistInfo>(pm, Pages.ARTISTDETAILS)
                    {
                        @Override
                        public Object getID(ArtistInfo obj)
                        {
                            return obj.getArtistID();
                        }

                        @Override
                        public String getValue(ArtistInfo object)
                        {
                            return object.getName();
                        }
                    };
            table.addColumn(col);
            table.setColumnWidth(col, 100, Unit.PX);
        }
    }
}
