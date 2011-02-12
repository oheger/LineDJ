package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Hyperlink;

import de.oliver_heger.mediastore.client.ComparatorMapGridTableModel;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.AlbumComparators;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;

/**
 * <p>
 * A special table model for displaying {@link AlbumInfo} objects.
 * </p>
 * <p>
 * This table model allows displaying a few properties about the albums related
 * to an artist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class AlbumGridTableModel extends ComparatorMapGridTableModel<AlbumInfo>
{
    /** Constant for the name property. */
    private static final String PROP_NAME = "name";

    /** The page manager. */
    private final PageManager pageManager;

    /**
     * Creates a new instance of {@code AlbumGridTableModel} and initializes it.
     *
     * @param grid the managed grid
     * @param pm the page manager
     */
    public AlbumGridTableModel(Grid grid, PageManager pm)
    {
        super(grid, ComparatorMapGridTableModel
                .comparatorMapForEnum(AlbumComparators.values()));
        pageManager = pm;
    }

    /**
     * Returns the page manager used by this instance.
     *
     * @return the page manager
     */
    public PageManager getPageManager()
    {
        return pageManager;
    }

    /**
     * {@inheritDoc} This implementation supports some properties of
     * {@link AlbumInfo} objects.
     */
    @Override
    protected void writeCell(int row, int col, String property, AlbumInfo obj)
    {
        if (PROP_NAME.equals(property))
        {
            getGrid().setWidget(row, col, createAlbumNameLink(obj));
        }
        else
        {
            unknownProperty(row, col, property);
        }
    }

    /**
     * Creates a hyper link for the album name property. This link points to the
     * details page of the referenced album.
     *
     * @param ai the album info object
     * @return the hyper link for this album
     */
    private Hyperlink createAlbumNameLink(AlbumInfo ai)
    {
        return new Hyperlink(ai.getName(), getPageManager()
                .createPageSpecification(Pages.ALBUMDETAILS)
                .withParameter(ai.getAlbumID()).toToken());
    }
}
