package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Hyperlink;

import de.oliver_heger.mediastore.client.ComparatorMapGridTableModel;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.SongComparators;
import de.oliver_heger.mediastore.shared.model.SongInfo;

/**
 * <p>
 * A specialized table model implementation for {@link SongInfo} objects.
 * </p>
 * <p>
 * This model implementation allows displaying some information about songs on
 * detail pages for other entities.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class SongGridTableModel extends ComparatorMapGridTableModel<SongInfo>
{
    /** Constant for the name property. */
    private static final String PROP_NAME = "name";

    /** Constant for the duration property. */
    private static final String PROP_DURATION = "duration";

    /** Constant for the play count property. */
    private static final String PROP_PLAYCOUNT = "playCount";

    /** Stores the current page manager. */
    private final PageManager pageManager;

    /**
     * Creates a new instance of {@code SongGridTableModel} and initializes it
     * with the underlying grid and the current page manager. The page manager
     * may be needed for rendering some properties.
     *
     * @param grid the grid
     * @param pm the {@link PageManager}
     */
    public SongGridTableModel(Grid grid, PageManager pm)
    {
        super(grid, comparatorMapForEnum(SongComparators.values()));
        pageManager = pm;
    }

    /**
     * Returns the current page manager.
     *
     * @return the {@link PageManager}
     */
    public PageManager getPageManager()
    {
        return pageManager;
    }

    /**
     * Writes the specified property of the {@link SongInfo} object in the
     * current cell of the underlying grid. This implementation supports a
     * subset of {@link SongInfo} properties. The song name is rendered as a
     * link to the song detail page.
     *
     * @param row the row index
     * @param col the column index
     * @param property the name of the property
     * @param obj the current data object
     */
    @Override
    protected void writeCell(int row, int col, String property, SongInfo obj)
    {
        if (PROP_NAME.equals(property))
        {
            getGrid().setWidget(row, col, createSongNameLink(obj));
        }
        else if (PROP_DURATION.equals(property))
        {
            getGrid().setText(row, col, obj.getFormattedDuration());
        }
        else if (PROP_PLAYCOUNT.equals(property))
        {
            getGrid().setText(row, col, String.valueOf(obj.getPlayCount()));
        }
        else
        {
            unknownProperty(row, col, property);
        }
    }

    /**
     * Creates a hyper link for the song name property. This link points to the
     * details page of the referenced song.
     *
     * @param si the song info object
     * @return the hyper link for this song
     */
    private Hyperlink createSongNameLink(SongInfo si)
    {
        return new Hyperlink(si.getName(), getPageManager()
                .createPageSpecification(Pages.SONGDETAILS)
                .withParameter(si.getSongID()).toToken());
    }
}
