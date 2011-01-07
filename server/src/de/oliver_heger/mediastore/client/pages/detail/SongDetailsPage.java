package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Hyperlink;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.model.SongDetailInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;

/**
 * <p>
 * This class implements the details page for songs.
 * </p>
 * <p>
 * This page expects the ID of the song to be displayed in its page
 * configuration. It loads the details of this song and displays all data
 * available.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongDetailsPage extends AbstractDetailsPage<SongDetailInfo>
{
    /** Our binder. */
    private static SongDetailsPageUiBinder uiBinder = GWT
            .create(SongDetailsPageUiBinder.class);

    /** The span for the song name. */
    @UiField
    SpanElement spanName;

    /** The hyper link to the song's artist. */
    @UiField
    Hyperlink lnkArtist;

    /** The span for the song's duration. */
    @UiField
    SpanElement spanDuration;

    /** The span for the song's inception year. */
    @UiField
    SpanElement spanYear;

    /** The span for the song's track number. */
    @UiField
    SpanElement spanTrack;

    /** The span for the song's play count. */
    @UiField
    SpanElement spanPlayCount;

    /** The span for the song's creation date. */
    @UiField
    SpanElement spanCreationDate;

    /** The span for the song's synonyms. */
    @UiField
    SpanElement spanSynonyms;

    /** The entity details handler used by this page. */
    private final DetailsEntityHandler<SongDetailInfo> detailsHandler;

    /**
     * Creates a new instance of {@code SongDetailsPage}.
     */
    public SongDetailsPage()
    {
        initWidget(uiBinder.createAndBindUi(this));
        detailsHandler = new SongDetailsEntityHandler();
    }

    /**
     * Returns the {@link DetailsEntityHandler} used by this page.
     *
     * @return the {@link DetailsEntityHandler}
     */
    @Override
    protected DetailsEntityHandler<SongDetailInfo> getDetailsEntityHandler()
    {
        return detailsHandler;
    }

    /**
     * Fills the fields of the page with the properties of the specified data
     * object.
     *
     * @param data the data object
     */
    @Override
    protected void fillPage(SongDetailInfo data)
    {
        spanName.setInnerText(data.getName());
        spanCreationDate.setInnerText(getFormatter().formatDate(
                data.getCreationDate()));
        spanDuration.setInnerText(data.getFormattedDuration());
        spanPlayCount.setInnerText(String.valueOf(data.getPlayCount()));
        spanTrack.setInnerText(formatObject(data.getTrackNo()));
        spanYear.setInnerText(formatObject(data.getInceptionYear()));
        spanSynonyms.setInnerText(formatSynonyms(data.getSynonyms()));

        initializeArtistLink(data);
    }

    /**
     * Clears all fields on the details page. This implementation just populates
     * the fields with an empty data object.
     */
    @Override
    protected void clearPage()
    {
        fillPage(new SongDetailInfo());
        spanPlayCount.setInnerText(null);
    }

    /**
     * Returns the {@link SynonymQueryHandler} used by this page.
     *
     * @return the {@link SynonymQueryHandler}
     */
    @Override
    protected SynonymQueryHandler getSynonymQueryHandler(
            MediaSearchServiceAsync searchService)
    {
        return new SongSynonymQueryHandler(searchService);
    }

    /**
     * Initializes the link to the artist of the song.
     *
     * @param data the data object
     */
    private void initializeArtistLink(SongDetailInfo data)
    {
        if (data.getArtistID() != null)
        {
            lnkArtist.setText(data.getArtistName());
            lnkArtist.setTargetHistoryToken(getPageManager()
                    .createPageSpecification(Pages.ARTISTDETAILS)
                    .withParameter(data.getArtistID()).toToken());
            lnkArtist.setVisible(true);
        }
        else
        {
            lnkArtist.setVisible(false);
        }
    }

    /**
     * The binder interface used for this component.
     */
    interface SongDetailsPageUiBinder extends UiBinder<Widget, SongDetailsPage>
    {
    }
}
