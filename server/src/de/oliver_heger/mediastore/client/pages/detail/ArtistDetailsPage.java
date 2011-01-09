package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.DisclosurePanel;
import com.google.gwt.user.client.ui.FlexTable;
import com.google.gwt.user.client.ui.Hyperlink;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;
import de.oliver_heger.mediastore.shared.model.SongInfo;
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
    private static final String HEADER_SONG_PANEL = "Songs (";

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

    /** The label for the songs of the artist. */
    @UiField
    DisclosurePanel pnlSongs;

    /** The table which displays the songs of the artist. */
    @UiField
    FlexTable tabSongs;

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
        spanSynonyms.setInnerText(formatSynonyms(data.getSynonyms()));

        fillSongsTable(data);
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
     * when the page is to be filled with a new data object.
     *
     * @param data the data object
     */
    protected void fillSongsTable(ArtistDetailInfo data)
    {
        StringBuilder buf = new StringBuilder();
        buf.append(HEADER_SONG_PANEL);
        buf.append(data.getSongs().size()).append(CLOSING_BRACKET);
        pnlSongs.getHeaderTextAccessor().setText(buf.toString());
        tabSongs.removeAllRows();
        tabSongs.setText(0, 0, "Name");
        tabSongs.setText(0, 1, "Duration");
        tabSongs.setText(0, 2, "Played");

        int row = 1;
        for (SongInfo si : data.getSongs())
        {
            Hyperlink link =
                    new Hyperlink(si.getName(), getPageManager()
                            .createPageSpecification(Pages.SONGDETAILS)
                            .withParameter(si.getSongID()).toToken());
            tabSongs.setWidget(row, 0, link);
            tabSongs.setText(row, 1, si.getFormattedDuration());
            tabSongs.setText(row, 2, String.valueOf(si.getPlayCount()));
            row++;
        }
    }

    /**
     * The binder interface used for this component.
     */
    interface ArtistDetailsPageUiBinder extends
            UiBinder<Widget, ArtistDetailsPage>
    {
    }
}
