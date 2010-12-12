package de.oliver_heger.mediastore.client.pages.detail;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Widget;

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
public class ArtistDetailsPage extends Composite
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

    /** The button for editing synonyms. */
    @UiField
    Button btnEditSynonyms;

    /**
     * Creates a new instance of {@code ArtistDetailsPage}.
     */
    public ArtistDetailsPage()
    {
        initWidget(uiBinder.createAndBindUi(this));
    }

    @UiHandler("btnEditSynonyms")
    void onClick(ClickEvent e)
    {
        Window.alert("Hello!");
    }

    /**
     * The binder interface used for this component.
     */
    interface ArtistDetailsPageUiBinder extends
            UiBinder<Widget, ArtistDetailsPage>
    {
    }
}
