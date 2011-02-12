package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Hyperlink;

import de.oliver_heger.mediastore.client.ComparatorMapGridTableModel;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.ArtistComparators;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;

/**
 * <p>
 * A specialized table model implementation for displaying properties of artists
 * on a details page.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class ArtistGridTableModel extends ComparatorMapGridTableModel<ArtistInfo>
{
    /** Constant for the name property. */
    private static final String PROP_NAME = "name";

    /** Stores the page manager. */
    private final PageManager pageManager;

    /**
     * Creates a new instance of {@code ArtistGridTableModel} and initializes
     * it.
     *
     * @param grid the underlying grid
     * @param pm the page manager
     */
    public ArtistGridTableModel(Grid grid, PageManager pm)
    {
        super(grid, comparatorMapForEnum(ArtistComparators.values()));
        pageManager = pm;
    }

    /**
     * Returns the {@link PageManager} used by this model.
     *
     * @return the page manager
     */
    public PageManager getPageManager()
    {
        return pageManager;
    }

    /**
     * {@inheritDoc} This implementation supports the name property of an
     * artist.
     */
    @Override
    protected void writeCell(int row, int col, String property, ArtistInfo obj)
    {
        if (PROP_NAME.equals(property))
        {
            Hyperlink link = createArtistNameLink(obj);
            getGrid().setWidget(row, col, link);
        }
        else
        {
            unknownProperty(row, col, property);
        }
    }

    /**
     * Creates the link for the name of an artist.
     *
     * @param obj the artist info object
     * @return the link for the artist name
     */
    private Hyperlink createArtistNameLink(ArtistInfo obj)
    {
        Hyperlink link = new Hyperlink();
        link.setText(obj.getName());
        link.setTargetHistoryToken(getPageManager()
                .createPageSpecification(Pages.ARTISTDETAILS)
                .withParameter(obj.getArtistID()).toToken());
        return link;
    }
}
