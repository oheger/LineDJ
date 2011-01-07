package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Set;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Hyperlink;
import com.google.gwt.user.client.ui.Image;

import de.oliver_heger.mediastore.client.DisplayErrorPanel;
import de.oliver_heger.mediastore.client.I18NFormatter;
import de.oliver_heger.mediastore.client.pageman.PageConfiguration;
import de.oliver_heger.mediastore.client.pageman.PageConfigurationSupport;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.BasicMediaService;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.HasSynonyms;
import de.oliver_heger.mediastore.shared.search.MediaSearchService;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;

/**
 * <p>
 * An abstract base class for details pages.
 * </p>
 * <p>
 * A details page presents all information available about a selected data
 * object like an artist or a song. This base class implements some common logic
 * required by all kinds of details pages. It especially takes care that the
 * details information for the current data object is fetched from the server
 * when the page is opened. There is already support for a progress indicator
 * and error handling. Concrete subclasses have to define the actual UI based on
 * their specific requirements. They also have to provide some information about
 * the concrete type of data object to be processed and about UI elements
 * serving a specific purpose (e.g. the error panel).
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects this page is about
 */
public abstract class AbstractDetailsPage<T extends HasSynonyms> extends
        Composite implements PageConfigurationSupport, SynonymEditResultsProcessor
{
    /** Constant for the synonym separator. */
    private static final String SYN_SEPARATOR = ", ";

    /** The image with the progress indicator. */
    @UiField
    Image progressIndicator;

    /** The error panel. */
    @UiField
    DisplayErrorPanel pnlError;

    /** The link to the overview page. */
    @UiField
    Hyperlink lnkOverview;

    /** The button which invokes the synonym editor. */
    @UiField
    Button btnEditSynonyms;

    /** The component for editing synonyms. */
    @UiField
    SynonymEditor synEditor;

    /** The callback used for fetch details server calls. */
    private final FetchDetailsCallback fetchDetailsCallback;

    /** The callback for updating synonyms. */
    private final UpdateSynonymsCallback updateSynonymsCallback;

    /** The formatter. */
    private final I18NFormatter formatter;

    /** The current page manager. */
    private PageManager pageManager;

    /** The current object displayed by this page. */
    private T currentEntity;

    /** The ID of the current entity as passed as page parameter. */
    private String currentEntityID;

    /**
     * Creates a new instance of {@code AbstractDetailsPage}.
     */
    protected AbstractDetailsPage()
    {
        fetchDetailsCallback = new FetchDetailsCallback();
        updateSynonymsCallback = new UpdateSynonymsCallback();
        formatter = new I18NFormatter();
    }

    /**
     * Initializes this page and passes a reference to the current
     * {@link PageManager}. This method is called directly after this object was
     * created.
     *
     * @param pm the {@link PageManager} reference
     */
    public void initialize(PageManager pm)
    {
        pageManager = pm;
        initSynonymEditor();
        initOverviewLink();
    }

    /**
     * Returns the current {@link PageManager}.
     *
     * @return the {@link PageManager}
     */
    public PageManager getPageManager()
    {
        return pageManager;
    }

    /**
     * Returns the ID (as a string) of the entity currently displayed on this
     * page. This ID is set when the page is opened.
     *
     * @return the ID of the current entity
     */
    public String getCurrentEntityID()
    {
        return currentEntityID;
    }

    /**
     * Notifies this page that it was opened with a new page configuration. This
     * implementation extracts the ID of the element to display from the page
     * configuration. It then initiates a server call in order to fetch the
     * data.
     *
     * @param config the page configuration
     */
    @Override
    public void setPageConfiguration(PageConfiguration config)
    {
        currentEntityID = config.getStringParameter();
        refresh();
    }

    /**
     * The synonyms of the current entity have been changed. This method
     * delegates to the {@link DetailsEntityHandler} to perform the update. Then
     * it refreshes this page.
     *
     * @param updateData the object describing the synonym changes
     */
    @Override
    public void synonymsChanged(SynonymUpdateData updateData)
    {
        beginServerCall();
        getDetailsEntityHandler().updateSynonyms(getMediaService(),
                getCurrentEntityID(), updateData, getUpdateSynonymsCallback());
    }

    /**
     * Returns the entity object that is currently displayed by this page.
     *
     * @return the current entity
     */
    public T getCurrentEntity()
    {
        return currentEntity;
    }

    /**
     * Returns the formatter object.
     *
     * @return the formatter object
     */
    protected I18NFormatter getFormatter()
    {
        return formatter;
    }

    /**
     * Returns the callback object required for the server call for fetching
     * details information. This implementation returns an object which
     * delegates to {@link #fillPage(Object)} when the results from the server
     * become available. If an error occurs, the error panel is initialized.
     *
     * @return the callback object
     */
    protected AsyncCallback<T> getFetchDetailsCallback()
    {
        return fetchDetailsCallback;
    }

    /**
     * Returns the callback object required for the server call for updating
     * synonyms. The callback returned here refreshes the page when the synonyms
     * have been updated successfully.
     *
     * @return the callback object
     */
    protected AsyncCallback<Void> getUpdateSynonymsCallback()
    {
        return updateSynonymsCallback;
    }

    /**
     * Returns a reference to the media service.
     *
     * @return the media service
     */
    protected BasicMediaServiceAsync getMediaService()
    {
        return GWT.create(BasicMediaService.class);
    }

    /**
     * Returns a reference to the media search service.
     *
     * @return the search service
     */
    protected MediaSearchServiceAsync getSearchService()
    {
        return GWT.create(MediaSearchService.class);
    }

    /**
     * Formats the synonyms of a data object. This implementation creates a
     * comma separated string with all synonym names.
     *
     * @param synonyms the set of synonyms (may be <b>null</b>)
     * @return the formatted synonyms as string
     */
    protected String formatSynonyms(Set<String> synonyms)
    {
        StringBuilder buf = new StringBuilder();

        if (synonyms != null)
        {
            boolean first = true;
            for (String syn : synonyms)
            {
                if (first)
                {
                    first = false;
                }
                else
                {
                    buf.append(SYN_SEPARATOR);
                }
                buf.append(syn);
            }
        }

        return buf.toString();
    }

    /**
     * Returns the {@link DetailsEntityHandler} object to be used for this page.
     *
     * @return the details entity handler
     */
    protected abstract DetailsEntityHandler<T> getDetailsEntityHandler();

    /**
     * Updates the fields of this page with the details of the specified data
     * object. This method is called when detail information from the server
     * becomes available. A concrete implementation has to setup its fields so
     * that the data is displayed.
     *
     * @param data the data object with details about the current element
     */
    protected abstract void fillPage(T data);

    /**
     * Clears all fields of this page. This method is called if no data can be
     * retrieved from the server. In this case it has to be ensured that the UI
     * does not display any stale data, so all fields have to be cleared.
     */
    protected abstract void clearPage();

    /**
     * Returns the {@link SynonymQueryHandler} object to be used for this page.
     * This object is used by the synonym editor for search operations.
     *
     * @param searchService the search service
     * @return the synonym query handler
     */
    protected abstract SynonymQueryHandler getSynonymQueryHandler(
            MediaSearchServiceAsync searchService);

    /**
     * Sets the current entity.
     *
     * @param currentEntity the entity
     */
    void setCurrentEntity(T currentEntity)
    {
        this.currentEntity = currentEntity;
    }

    /**
     * Refreshes the data of this page. Loads the entity again and updates all
     * fields.
     */
    void refresh()
    {
        beginServerCall();
        BasicMediaServiceAsync service = getMediaService();
        getDetailsEntityHandler().fetchDetails(service, currentEntityID,
                getFetchDetailsCallback());
    }

    /**
     * The link for navigating to the overview page was clicked. The
     * corresponding page is opened.
     *
     * @param e the click event
     */
    @UiHandler("lnkOverview")
    void onClickOverview(ClickEvent e)
    {
        getPageManager().createPageSpecification(Pages.OVERVIEW).open();
    }

    /**
     * The button for editing synonyms has been clicked. This handler method
     * opens the synonym editor.
     *
     * @param e the click event
     */
    @UiHandler("btnEditSynonyms")
    void onClickEditSynonyms(ClickEvent e)
    {
        synEditor.edit(getCurrentEntity());
    }

    /**
     * Prepares the UI for a server call. Some controls have to be disabled, the
     * progress indicator is displayed.
     */
    void beginServerCall()
    {
        progressIndicator.setVisible(true);
        pnlError.clearError();
        btnEditSynonyms.setEnabled(false);
    }

    /**
     * The server call is complete. This method disables the progress
     * indicator and updates the current state of this object.
     *
     * @param current the new current object of this page
     */
    void endOfServerCall(T current)
    {
        btnEditSynonyms.setEnabled(current != null);
        setCurrentEntity(current);
        progressIndicator.setVisible(false);
    }

    /**
     * Initializes the synonym editor.
     */
    private void initSynonymEditor()
    {
        synEditor
                .setSynonymQueryHandler(getSynonymQueryHandler(getSearchService()));
        synEditor.setResultsProcessor(this);
    }

    /**
     * Initializes the link back to the overview page.
     */
    private void initOverviewLink()
    {
        String token =
                getPageManager().createPageSpecification(Pages.OVERVIEW)
                        .toToken();
        lnkOverview.setTargetHistoryToken(token);
    }

    /**
     * An abstract base class for service callback implementations. This base
     * class already implements error handling by activating the error panel.
     *
     * @param <R> the result type of the callback
     */
    private abstract class AbstractDetailsCallback<R> implements
            AsyncCallback<R>
    {
        /**
         * An error occurred while calling the server. This implementation
         * clears all fields and sets the error state of the error panel.
         *
         * @param caught the exception
         */
        @Override
        public void onFailure(Throwable caught)
        {
            pnlError.displayError(caught);
            clearPage();
            endOfServerCall(null);
        }
    }

    /**
     * A callback implementation for making the server call to fetch the details
     * of the current element.
     */
    private class FetchDetailsCallback extends AbstractDetailsCallback<T>
    {
        /**
         * Data could be fetched successfully from the server. This method takes
         * care that this data is displayed.
         */
        @Override
        public void onSuccess(T result)
        {
            fillPage(result);
            endOfServerCall(result);
        }
    }

    /**
     * A callback implementation for controlling the server calls for updating
     * synonyms. This implementation just triggers a refresh after the synonyms
     * have been updated successfully.
     */
    private class UpdateSynonymsCallback extends AbstractDetailsCallback<Void>
    {
        @Override
        public void onSuccess(Void result)
        {
            refresh();
        }
    }
}
