package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.cellview.client.CellTable;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.ProvidesKey;

import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;

/**
 * <p>
 * The concrete overview page for artists.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistOverviewPage extends AbstractOverviewTable<ArtistInfo>
{
    /** The key provider. */
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
     * Creates a new instance of {@code ArtistOverviewPage}.
     */
    public ArtistOverviewPage()
    {
        super(KEY_PROVIDER, new ArtistOverviewQueryHandler());
    }

    /**
     * {@inheritDoc} Adds the columns for displaying artists.
     */
    @Override
    protected void initCellTableColumns(CellTable<ArtistInfo> table)
    {
        Column<ArtistInfo, String> colName = createArtistNameColumn();
        addColumn(colName, "Name", "searchName", true);
        cellTable.setColumnWidth(colName, 75, Unit.PCT);
        Column<ArtistInfo, String> colDate = createDateColumn();
        addColumn(colDate, "Created at", "creationDate", false);
        cellTable.setColumnWidth(colDate, 25, Unit.PCT);
    }

    /**
     * {@inheritDoc} This implementation adds the handlers for artists.
     */
    @Override
    protected void initMultiElementHandlers()
    {
        addMultiElementHandler(getImageResources().removeItem(), ACTION_REMOVE,
                new RemoveElementHandler(createRemoveArtistHandler(), this,
                        getRemoveController()));
    };

    /**
     * Creates the column for displaying the name of the artist.
     *
     * @return the artist name column
     */
    Column<ArtistInfo, String> createArtistNameColumn()
    {
        Column<ArtistInfo, String> col =
                new LinkColumn<ArtistInfo>(getPageManager(),
                        Pages.ARTISTDETAILS)
                {
                    @Override
                    protected Object getID(ArtistInfo obj)
                    {
                        return obj.getArtistID();
                    }

                    @Override
                    public String getValue(ArtistInfo obj)
                    {
                        return obj.getName();
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates the column for displaying the creation date.
     *
     * @return the created-at column
     */
    Column<ArtistInfo, String> createDateColumn()
    {
        Column<ArtistInfo, String> col =
                new Column<ArtistInfo, String>(new TextCell())
                {
                    @Override
                    public String getValue(ArtistInfo object)
                    {
                        return getFormatter().formatDate(
                                object.getCreationDate());
                    }
                };
        col.setSortable(true);
        return col;
    }

    /**
     * Creates a remove service handler for removing an artist.
     *
     * @return the handler
     */
    RemoveServiceHandler createRemoveArtistHandler()
    {
        return new RemoveServiceHandler()
        {
            @Override
            public void removeElement(BasicMediaServiceAsync service,
                    Object elemID, AsyncCallback<Boolean> callback)
            {
                service.removeArtist((Long) elemID, callback);
            }
        };
    }
}
