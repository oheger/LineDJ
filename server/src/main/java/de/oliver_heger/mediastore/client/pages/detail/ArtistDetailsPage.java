package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Collection;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.DisclosurePanel;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;

/**
 * <p>
 * A widget implementing the details page of an artist.
 * </p>
 * <p>
 * This page displays all information available about an artist including the
 * synonyms, the songs, and the albums. The page expects the ID of the artist to
 * display as default parameter of its configuration. It loads the data of this
 * artist and displays it.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistDetailsPage extends AbstractDetailsPage<ArtistDetailInfo>
{
    /** Constant for the header for the song panel. */
    private static final String HEADER_SONG_PANEL = "Songs";

    /** Constant for the header for the album panel. */
    private static final String HEADER_ALBUM_PANEL = "Albums";

    /** Constant for the opening bracket. */
    private static final String OPENING_BRACKET = " (";

    /** Constant for the closing bracket. */
    private static final String CLOSING_BRACKET = ")";

    /** Our binder. */
    private static ArtistDetailsPageUiBinder uiBinder = GWT
            .create(ArtistDetailsPageUiBinder.class);

    /** The element for the name of the artist. */
    @UiField
    SpanElement spanArtistName;

    /** The element for the creation date of the artist. */
    @UiField
    SpanElement spanCreationDate;

    /** The element for the synonyms of the artist. */
    @UiField
    SpanElement spanSynonyms;

    /** The panel for the songs of the artist. */
    @UiField
    DisclosurePanel pnlSongs;

    /** The table which displays the songs of the artist. */
    @UiField
    Grid tabSongs;

    /** The panel for the albums related to the artist. */
    @UiField
    DisclosurePanel pnlAlbums;

    /** The table for the albums related to the artist. */
    @UiField
    AlbumDetailsTable tabAlbums;

    /** The entity handler for fetching artist details. */
    private final DetailsEntityHandler<ArtistDetailInfo> entityHandler;

    /** The model for the table with the songs of this artist. */
    private SongGridTableModel songModel;

    /**
     * Creates a new instance of {@code ArtistDetailsPage}.
     */
    public ArtistDetailsPage()
    {
        initWidget(uiBinder.createAndBindUi(this));
        entityHandler = new ArtistDetailsEntityHandler();
    }

    /**
     * {@inheritDoc} This implementation also initializes the models for grids
     * used on this page.
     */
    @Override
    public void initialize(PageManager pm)
    {
        super.initialize(pm);

        songModel = new SongGridTableModel(tabSongs, pm);
        tabAlbums.initialize(pm);
    }

    /**
     * Returns the handler for performing a details query for the current
     * artist. This implementation returns an {@link ArtistDetailsEntityHandler}
     * object.
     *
     * @return the details query handler
     */
    @Override
    protected DetailsEntityHandler<ArtistDetailInfo> getDetailsEntityHandler()
    {
        return entityHandler;
    }

    /**
     * Fills the page with the information about the passed in data object.
     *
     * @param data the data object
     */
    @Override
    protected void fillPage(ArtistDetailInfo data)
    {
        spanArtistName.setInnerText(data.getName());
        spanCreationDate.setInnerText(getFormatter().formatDate(
                data.getCreationDate()));
        spanSynonyms.setInnerText(formatSynonyms(data.getSynonymData()));

        fillSongsTable(data);
        fillAlbumsTable(data);
    }

    /**
     * Clears all fields in this page.
     */
    @Override
    protected void clearPage()
    {
        fillPage(new ArtistDetailInfo());
    }

    /**
     * Returns the handler for synonym searches. This implementation returns an
     * {@link ArtistSynonymQueryHandler} object.
     *
     * @param searchService the search service
     * @return the synonym query handler
     */
    @Override
    protected SynonymQueryHandler getSynonymQueryHandler(
            MediaSearchServiceAsync searchService)
    {
        return new ArtistSynonymQueryHandler(searchService);
    }

    /**
     * Populates the table with the songs of the artist. This method is called
     * when the page is to be filled with a new data object. It mainly delegates
     * to the table model in order to get the grid populated.
     *
     * @param data the data object
     */
    protected void fillSongsTable(ArtistDetailInfo data)
    {
        updateTableHeader(data.getSongs(), HEADER_SONG_PANEL, pnlSongs);
        getSongTableModel().initData(data.getSongs());
    }

    /**
     * Populates the table with the albums related to the artist. This method is
     * called when the page is to be filled with a new data object. The actual
     * work to populate the table is done by the table model.
     *
     * @param data the data object
     */
    protected void fillAlbumsTable(ArtistDetailInfo data)
    {
        updateTableHeader(data.getAlbums(), HEADER_ALBUM_PANEL, pnlAlbums);
        tabAlbums.setData(data.getAlbums());
    }

    /**
     * Returns the model for the table with the songs of this artist.
     *
     * @return the song table model
     */
    SongGridTableModel getSongTableModel()
    {
        return songModel;
    }

    /**
     * Updates the text of a disclosure panel containing a table with the number
     * of items which are currently displayed. If there are items, the panel is
     * opened.
     *
     * @param items the collection with items
     * @param text the prefix text for the header message
     * @param pnl the {@code DisclosurePanel}
     */
    private void updateTableHeader(Collection<?> items, String text,
            DisclosurePanel pnl)
    {
        int count = items.size();
        StringBuilder buf = new StringBuilder();
        buf.append(text).append(OPENING_BRACKET);
        buf.append(count).append(CLOSING_BRACKET);
        pnl.getHeaderTextAccessor().setText(buf.toString());
        pnl.setOpen(count > 0);
    }

    /**
     * The binder interface used for this component.
     */
    interface ArtistDetailsPageUiBinder extends
            UiBinder<Widget, ArtistDetailsPage>
    {
    }
}
