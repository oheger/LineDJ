package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

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

    /** The query handler for fetching artist details. */
    private final DetailsQueryHandler<ArtistDetailInfo> queryHandler;

    /**
     * Creates a new instance of {@code ArtistDetailsPage}.
     */
    public ArtistDetailsPage()
    {
        initWidget(uiBinder.createAndBindUi(this));
        queryHandler = new ArtistDetailsQueryHandler();
    }

    /**
     * Returns the handler for performing a details query for the current
     * artist. This implementation returns an {@link ArtistDetailsQueryHandler}
     * object.
     *
     * @return the details query handler
     */
    @Override
    protected DetailsQueryHandler<ArtistDetailInfo> getDetailsQueryHandler()
    {
        return queryHandler;
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
     * The binder interface used for this component.
     */
    interface ArtistDetailsPageUiBinder extends
            UiBinder<Widget, ArtistDetailsPage>
    {
    }
}
