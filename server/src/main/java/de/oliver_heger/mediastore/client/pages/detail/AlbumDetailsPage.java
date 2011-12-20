package de.oliver_heger.mediastore.client.pages.detail;

import java.util.List;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.DisclosurePanel;
import com.google.gwt.user.client.ui.Hyperlink;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.AlbumDetailInfo;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;

/**
 * <p>
 * This class implements the details page for albums.
 * </p>
 * <p>
 * This page expects the ID of the album to be displayed in its page
 * configuration. It loads the details of this album and displays all data
 * available.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumDetailsPage extends AbstractDetailsPage<AlbumDetailInfo>
{
    /** Our binder. */
    private static AlbumDetailsPageUiBinder uiBinder = GWT
            .create(AlbumDetailsPageUiBinder.class);

    /** The name of the songs table. */
    private static final String SONG_TABLE_NAME = "Songs";

    /** The name of the artists table. */
    private static final String ARTIST_TABLE_NAME = "Artists";

    /** The span for the album name. */
    @UiField
    SpanElement spanName;

    /** The hyper link to the album's main artist. */
    @UiField
    Hyperlink lnkArtist;

    /** A label which is displayed if there are multiple artists. */
    @UiField
    Label labMultiArtists;

    /** The span for the album's duration. */
    @UiField
    SpanElement spanDuration;

    /** The span for the album's inception year. */
    @UiField
    SpanElement spanYear;

    /** The span for the album's creation date. */
    @UiField
    SpanElement spanCreationDate;

    /** The span for the album's synonyms. */
    @UiField
    SpanElement spanSynonyms;

    /** The table for the songs of this album. */
    @UiField
    SongDetailsTable tabSongs;

    /** The table for the artists of this album. */
    @UiField
    ArtistDetailsTable tabArtists;

    /** The panel for displaying the associated songs. */
    @UiField
    DisclosurePanel pnlSongs;

    /** The panel for displaying the associated artists. */
    @UiField
    DisclosurePanel pnlArtists;

    /** The details handler used by this page. */
    private final DetailsEntityHandler<AlbumDetailInfo> detailsHandler;

    /** The table model for the songs of this album. */
    private SongGridTableModel songModel;

    /**
     * Creates a new instance of {@code AlbumDetailsPage}.
     */
    public AlbumDetailsPage()
    {
        initWidget(uiBinder.createAndBindUi(this));
        detailsHandler = new AlbumDetailsEntityHandler();
    }

    /**
     * Initializes this page. This implementation also creates the table models.
     *
     * @param pm the page manager
     */
    @Override
    public void initialize(PageManager pm)
    {
        super.initialize(pm);

        tabArtists.initialize(pm);
        tabSongs.initialize(pm);
    }

    /**
     * Returns the {@link DetailsEntityHandler} used by this page.
     *
     * @return the details handler
     */
    @Override
    protected DetailsEntityHandler<AlbumDetailInfo> getDetailsEntityHandler()
    {
        return detailsHandler;
    }

    /**
     * Fills the page with data. The handling of artists is a bit more complex:
     * If there is no artist, the artist link is not displayed. If there is
     * exactly one artist, the link points to this artist's details page. If
     * there are multiple artists, the link is not active, but shows a message
     * that there are multiple artists; the table with the artists is open.
     *
     * @param data the data to be displayed
     */
    @Override
    protected void fillPage(AlbumDetailInfo data)
    {
        spanCreationDate.setInnerText(getFormatter().formatDate(
                data.getCreationDate()));
        spanDuration.setInnerText(data.getFormattedDuration());
        spanName.setInnerText(data.getName());
        spanYear.setInnerText((data.getInceptionYear() != null) ? String
                .valueOf(data.getInceptionYear()) : null);
        spanSynonyms.setInnerText(formatSynonyms(data.getSynonymData()));

        initArtistLink(data.getArtists());

        fillTable(pnlArtists, tabArtists, ARTIST_TABLE_NAME, data.getArtists(),
                2);
        fillTable(pnlSongs, tabSongs, SONG_TABLE_NAME, data.getSongs(), 1);
    }

    /**
     * Clears this page. All fields are set to empty values.
     */
    @Override
    protected void clearPage()
    {
        fillPage(new AlbumDetailInfo());
    }

    /**
     * Returns the handler for synonym queries. This implementation creates a
     * new album synonym query handler.
     *
     * @return the handler for querying synonyms
     */
    @Override
    protected SynonymQueryHandler getSynonymQueryHandler(
            MediaSearchServiceAsync searchService)
    {
        return new AlbumSynonymQueryHandler(searchService);
    }

    /**
     * Returns the table model for the song table.
     *
     * @return the songs table model
     */
    SongGridTableModel getSongTableModel()
    {
        return songModel;
    }

    /**
     * Initializes the link for the artist.
     *
     * @param artists the list with all artists
     */
    private void initArtistLink(List<ArtistInfo> artists)
    {
        if (artists.size() == 1)
        {
            lnkArtist.setVisible(true);
            ArtistInfo art = artists.get(0);
            lnkArtist.setText(art.getName());
            lnkArtist.setTargetHistoryToken(getPageManager()
                    .createPageSpecification(Pages.ARTISTDETAILS)
                    .withParameter(art.getArtistID()).toToken());
        }
        else
        {
            lnkArtist.setVisible(false);
        }

        labMultiArtists.setVisible(artists.size() > 1);
    }

    /**
     * The binder interface used for this component.
     */
    interface AlbumDetailsPageUiBinder extends
            UiBinder<Widget, AlbumDetailsPage>
    {
    }
}
