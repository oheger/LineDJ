package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.DisclosurePanel;
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
    SongDetailsTable tabSongs;

    /** The panel for the albums related to the artist. */
    @UiField
    DisclosurePanel pnlAlbums;

    /** The table for the albums related to the artist. */
    @UiField
    AlbumDetailsTable tabAlbums;

    /** The entity handler for fetching artist details. */
    private final DetailsEntityHandler<ArtistDetailInfo> entityHandler;

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

        tabAlbums.initialize(pm);
        tabSongs.initialize(pm);
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
        fillTable(pnlSongs, tabSongs, HEADER_SONG_PANEL, data.getSongs(), 1);
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
        fillTable(pnlAlbums, tabAlbums, HEADER_ALBUM_PANEL, data.getAlbums(), 1);
    }

    /**
     * The binder interface used for this component.
     */
    interface ArtistDetailsPageUiBinder extends
            UiBinder<Widget, ArtistDetailsPage>
    {
    }
}
